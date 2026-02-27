package com.notenotes.processing

import android.util.Log
import com.notenotes.model.*
import com.notenotes.util.PitchUtils

private const val TAG = "NNPipeline"

/**
 * Main transcription pipeline that orchestrates all processing steps.
 * 
 * Flow: Raw Audio → Pitch Detection → Onset Detection → Note Building
 *       → Same-Pitch Merging → Low-Confidence Filtering → Key Detection 
 *       → Time Sig Detection → Rhythm Quantization → TranscriptionResult
 *
 * Improvements over naive pipeline:
 * - Same-pitch merging: consecutive notes with identical MIDI pitch are merged
 * - Confidence filtering: removes spurious low-confidence detections
 * - Minimum event guards: prevents nonsense tempo/time sig with few notes
 */
class TranscriptionPipeline(
    private val sampleRate: Int = 44100,
    private val defaultTempoBpm: Int = 120,
    private val minNoteConfidence: Double = 0.3,   // minimum confidence to keep a note (lowered for real audio)
    private val samePitchMergeGapMs: Double = 100.0, // max gap between same-pitch notes to merge
    private val pitchAlgorithm: PitchAlgorithm = PitchAlgorithm.DEFAULT
) {

    private val pitchDetector: Any = createPitchDetector()
    private val polyphonicDetector = PolyphonicPitchDetector(sampleRate = sampleRate)
    private val onsetDetector = OnsetDetector(sampleRate = sampleRate)
    private val keyDetector = KeyDetector()
    private val timeSignatureDetector = TimeSignatureDetector(sampleRate = sampleRate)
    private val rhythmQuantizer = RhythmQuantizer()

    /**
     * Create the appropriate pitch detector(s) based on the selected algorithm.
     * For CONSENSUS mode, we create all three and vote.
     */
    private fun createPitchDetector(): Any {
        return when (pitchAlgorithm) {
            PitchAlgorithm.YIN -> YinPitchDetector(sampleRate = sampleRate)
            PitchAlgorithm.MPM -> McLeodPitchDetector(sampleRate = sampleRate)
            PitchAlgorithm.HPS -> HpsPitchDetector(sampleRate = sampleRate)
            PitchAlgorithm.CONSENSUS -> listOf(
                YinPitchDetector(sampleRate = sampleRate),
                McLeodPitchDetector(sampleRate = sampleRate),
                HpsPitchDetector(sampleRate = sampleRate)
            )
        }
    }

    /**
     * Detect pitches using the selected algorithm.
     * For CONSENSUS, runs all three and does per-frame majority voting.
     */
    private fun detectPitchesWithAlgorithm(samples: ShortArray): List<PitchDetectionResult> {
        Log.d(TAG, "detectPitchesWithAlgorithm: using ${pitchAlgorithm.displayName}")
        return when (pitchAlgorithm) {
            PitchAlgorithm.YIN -> (pitchDetector as YinPitchDetector).detectPitches(samples)
            PitchAlgorithm.MPM -> (pitchDetector as McLeodPitchDetector).detectPitches(samples)
            PitchAlgorithm.HPS -> (pitchDetector as HpsPitchDetector).detectPitches(samples)
            PitchAlgorithm.CONSENSUS -> {
                @Suppress("UNCHECKED_CAST")
                val detectors = pitchDetector as List<Any>
                val yinResults = (detectors[0] as YinPitchDetector).detectPitches(samples)
                val mpmResults = (detectors[1] as McLeodPitchDetector).detectPitches(samples)
                val hpsResults = (detectors[2] as HpsPitchDetector).detectPitches(samples)
                consensusVote(yinResults, mpmResults, hpsResults)
            }
        }
    }

    /**
     * Per-frame majority vote across three algorithm outputs.
     * Uses time-aligned frames; when algorithms agree (within ±1 semitone), 
     * picks the result with highest confidence.
     */
    private fun consensusVote(
        yin: List<PitchDetectionResult>,
        mpm: List<PitchDetectionResult>,
        hps: List<PitchDetectionResult>
    ): List<PitchDetectionResult> {
        val maxLen = maxOf(yin.size, mpm.size, hps.size)
        val result = mutableListOf<PitchDetectionResult>()

        for (i in 0 until maxLen) {
            val yf = yin.getOrNull(i)
            val mf = mpm.getOrNull(i)
            val hf = hps.getOrNull(i)

            val candidates = listOfNotNull(yf, mf, hf).filter { it.isPitched }

            if (candidates.isEmpty()) {
                // All unpitched — use YIN's frame as the unpitched marker
                result.add(yf ?: mf ?: hf ?: continue)
                continue
            }

            if (candidates.size == 1) {
                // Only one algorithm heard a pitch — use it but downweight confidence
                val c = candidates[0]
                result.add(c.copy(confidence = c.confidence * 0.7))
                continue
            }

            // 2+ pitched: find agreement by MIDI note grouping (±1 semitone)
            val midiGroups = mutableMapOf<Int, MutableList<PitchDetectionResult>>()
            for (c in candidates) {
                val midi = PitchUtils.frequencyToMidi(c.frequencyHz)
                val matchGroup = midiGroups.keys.find { kotlin.math.abs(it - midi) <= 1 }
                if (matchGroup != null) {
                    midiGroups[matchGroup]!!.add(c)
                } else {
                    midiGroups[midi] = mutableListOf(c)
                }
            }

            // Pick the largest group (majority), break ties by total confidence
            val bestGroup = midiGroups.values
                .sortedWith(compareByDescending<List<PitchDetectionResult>> { it.size }
                    .thenByDescending { grp -> grp.sumOf { it.confidence } })
                .first()

            // From the best group, pick the individual result with highest confidence
            val best = bestGroup.maxByOrNull { it.confidence }!!
            // Boost confidence when algorithms agree
            val boostFactor = if (bestGroup.size >= 2) 1.0 else 0.8
            result.add(best.copy(confidence = (best.confidence * boostFactor).coerceAtMost(1.0)))
        }

        Log.d(TAG, "consensusVote: ${result.size} frames, ${result.count { it.isPitched }} pitched")
        return result
    }

    /**
     * Process raw PCM audio samples and produce a transcription.
     * 
     * @param samples 16-bit PCM mono audio samples
     * @param userTempoBpm user-specified tempo, or null to estimate/use default
     * @param userKeySignature user-specified key, or null to auto-detect
     * @param userTimeSignature user-specified time sig, or null to auto-detect
     * @return TranscriptionResult with detected notes, key, time sig, and tempo
     */
    fun process(
        samples: ShortArray,
        userTempoBpm: Int? = null,
        userKeySignature: KeySignature? = null,
        userTimeSignature: TimeSignature? = null
    ): TranscriptionResult {
        Log.i(TAG, "process: Starting with ${samples.size} samples (${samples.size.toDouble() / sampleRate}s)")

        if (samples.isEmpty()) {
            Log.w(TAG, "process: Empty samples, returning empty result")
            return emptyResult()
        }

        // Step 1: Detect pitches (with octave correction)
        Log.d(TAG, "process: Step 1 - Pitch detection using ${pitchAlgorithm.displayName}...")
        val pitchResults = detectPitchesWithAlgorithm(samples)
        val pitchedFrames = pitchResults.filter { it.isPitched }
        Log.d(TAG, "process: Step 1 done - ${pitchResults.size} total frames, ${pitchedFrames.size} pitched")

        if (pitchedFrames.isEmpty()) {
            Log.w(TAG, "process: No pitched frames found, returning empty result")
            return emptyResult()
        }

        // Step 2: Detect onsets (with HFR gating)
        Log.d(TAG, "process: Step 2 - Onset detection...")
        val onsetTimes = onsetDetector.detectOnsets(samples)
        Log.d(TAG, "process: Step 2 done - ${onsetTimes.size} onsets: ${onsetTimes.take(10)}")

        // Step 3: Combine pitch + onset into detected notes (with polyphonic detection)
        Log.d(TAG, "process: Step 3 - Building detected notes (polyphonic-aware)...")
        var detectedNotes = buildDetectedNotes(pitchedFrames, onsetTimes, samples.size, samples)
        Log.d(TAG, "process: Step 3 done - ${detectedNotes.size} raw detected notes")

        if (detectedNotes.isEmpty()) {
            Log.w(TAG, "process: No detected notes after combining pitch+onset")
            return emptyResult()
        }

        // Step 3b: Filter low-confidence notes
        val beforeFilter = detectedNotes.size
        detectedNotes = detectedNotes.filter { it.confidence >= minNoteConfidence }
        Log.d(TAG, "process: Step 3b - Filtered ${beforeFilter - detectedNotes.size} low-confidence notes, ${detectedNotes.size} remain")

        if (detectedNotes.isEmpty()) {
            Log.w(TAG, "process: All notes filtered by confidence threshold")
            return emptyResult()
        }

        // Step 3c: Merge consecutive same-pitch notes
        val beforeMerge = detectedNotes.size
        detectedNotes = mergeSamePitchNotes(detectedNotes)
        Log.d(TAG, "process: Step 3c - Merged ${beforeMerge - detectedNotes.size} notes, ${detectedNotes.size} remain")

        // Log final detected notes for debugging
        for ((i, note) in detectedNotes.withIndex()) {
            val noteName = PitchUtils.midiToNoteName(note.midiNote)
            Log.d(TAG, "process: Note $i: $noteName (MIDI ${note.midiNote}) at ${String.format("%.3f", note.onsetSeconds)}s, dur=${String.format("%.3f", note.durationSeconds)}s, conf=${String.format("%.2f", note.confidence)}")
        }

        // Step 4: Detect key signature
        Log.d(TAG, "process: Step 4 - Key detection...")
        val keySignature = userKeySignature ?: run {
            val midiNotes = detectedNotes.map { it.midiNote }
            keyDetector.detectKey(midiNotes)
        }
        Log.d(TAG, "process: Step 4 done - key=$keySignature")

        // Step 5: Estimate tempo (with guards for few events)
        Log.d(TAG, "process: Step 5 - Tempo estimation...")
        val tempoBpm = userTempoBpm ?: run {
            estimateTempoSafely(detectedNotes, onsetTimes)
        }
        Log.d(TAG, "process: Step 5 done - tempo=${tempoBpm}bpm")

        // Step 6: Detect time signature (with guards for few events)
        Log.d(TAG, "process: Step 6 - Time signature detection...")
        val timeSignature = userTimeSignature ?: run {
            if (detectedNotes.size < 8) {
                // Too few notes for reliable time sig detection — default to 4/4
                Log.d(TAG, "process: Too few notes (${detectedNotes.size}) for time sig detection, defaulting to 4/4")
                TimeSignature.FOUR_FOUR
            } else {
                timeSignatureDetector.detectTimeSignature(
                    onsetTimesSeconds = detectedNotes.map { it.onsetSeconds },
                    tempoBpm = tempoBpm.toDouble()
                )
            }
        }
        Log.d(TAG, "process: Step 6 done - timeSig=$timeSignature")

        // Step 7: Quantize rhythms
        Log.d(TAG, "process: Step 7 - Rhythm quantization...")
        val musicalNotes = rhythmQuantizer.quantize(detectedNotes, tempoBpm, timeSignature)
        Log.i(TAG, "process: Step 7 done - ${musicalNotes.size} musical notes")

        val result = TranscriptionResult(
            notes = musicalNotes,
            keySignature = keySignature,
            timeSignature = timeSignature,
            tempoBpm = tempoBpm,
            divisions = 4
        )
        Log.i(TAG, "process: Complete! ${result.notes.size} notes, $keySignature, $timeSignature, ${tempoBpm}bpm")
        return result
    }

    /**
     * Estimate tempo safely handling edge cases.
     * When there are very few events, avoids returning extreme tempos.
     */
    private fun estimateTempoSafely(notes: List<DetectedNote>, onsetTimes: List<Double>): Int {
        // Use note onset times for tempo estimation (more reliable than raw onsets)
        val noteOnsets = notes.map { it.onsetSeconds }

        if (noteOnsets.size < 3) {
            Log.d(TAG, "estimateTempoSafely: Too few notes (${noteOnsets.size}), defaulting to $defaultTempoBpm")
            return defaultTempoBpm
        }

        val estimated = timeSignatureDetector.estimateTempo(noteOnsets).toInt()

        // Guard: if estimated tempo is extreme (< 40 or > 200), clamp to reasonable range
        val clamped = estimated.coerceIn(60, 180)
        if (clamped != estimated) {
            Log.d(TAG, "estimateTempoSafely: Clamped tempo from $estimated to $clamped")
        }

        return clamped
    }

    /**
     * Merge consecutive notes with the same MIDI pitch into single longer notes.
     * 
     * This fixes the common problem where guitar string decay causes
     * the same pitch to be re-detected as multiple short notes.
     *
     * Merging conditions:
     * - Same MIDI pitch
     * - Gap between end of note N and start of note N+1 is small (< samePitchMergeGapMs)
     * - OR note N+1 starts within note N's duration (overlapping)
     */
    private fun mergeSamePitchNotes(notes: List<DetectedNote>): List<DetectedNote> {
        if (notes.size <= 1) return notes

        val merged = mutableListOf<DetectedNote>()
        var current = notes[0]

        for (i in 1 until notes.size) {
            val next = notes[i]
            val currentEnd = current.onsetSeconds + current.durationSeconds
            val gap = next.onsetSeconds - currentEnd

            // Merge if same pitch and gap is small enough
            val samePitch = current.midiNote == next.midiNote &&
                    current.chordMidiNotes == next.chordMidiNotes  // also match chord composition
            val smallGap = gap < samePitchMergeGapMs / 1000.0
            val overlapping = next.onsetSeconds < currentEnd

            if (samePitch && (smallGap || overlapping)) {
                // Merge: extend current note to cover both
                val newEnd = maxOf(currentEnd, next.onsetSeconds + next.durationSeconds)
                current = DetectedNote(
                    midiNote = current.midiNote,
                    frequencyHz = (current.frequencyHz + next.frequencyHz) / 2.0,
                    onsetSeconds = current.onsetSeconds,
                    durationSeconds = newEnd - current.onsetSeconds,
                    confidence = maxOf(current.confidence, next.confidence)
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    /**
     * Build detected notes from pitch detection results and onset times.
     * Uses polyphonic detection (FFT-based) for each onset segment, falling
     * back to YIN-based monophonic detection when polyphonic finds only 1 note.
     */
    private fun buildDetectedNotes(
        pitchedFrames: List<PitchDetectionResult>,
        onsetTimes: List<Double>,
        totalSamples: Int,
        samples: ShortArray
    ): List<DetectedNote> {
        val totalDuration = totalSamples.toDouble() / sampleRate

        if (onsetTimes.isEmpty()) {
            // No onsets detected — build notes from pitch runs
            return buildNotesFromPitchRuns(pitchedFrames, totalDuration)
        }

        val notes = mutableListOf<DetectedNote>()

        for (i in onsetTimes.indices) {
            val startTime = onsetTimes[i]
            val endTime = if (i + 1 < onsetTimes.size) onsetTimes[i + 1] else totalDuration

            if (endTime <= startTime) continue

            // Try polyphonic detection on this segment
            val segmentStartSample = (startTime * sampleRate).toInt().coerceIn(0, samples.size - 1)
            // Use the first ~200ms after onset for FFT analysis (richest harmonics)
            val analysisWindowMs = 200.0
            val analysisSamples = (analysisWindowMs / 1000.0 * sampleRate).toInt()
            val segmentEndSample = minOf(
                segmentStartSample + analysisSamples,
                (endTime * sampleRate).toInt(),
                samples.size
            )

            if (segmentEndSample - segmentStartSample < 512) {
                // Too short for FFT — fall back to frame-based pitch
                val frameNote = buildNoteFromFrames(pitchedFrames, startTime, endTime)
                if (frameNote != null) notes.add(frameNote)
                continue
            }

            val segment = samples.copyOfRange(segmentStartSample, segmentEndSample)
            val polyResult = polyphonicDetector.detectPitches(segment, startTime)

            if (polyResult.pitches.size >= 2) {
                // Filter out weak pitches: require at least 20% of strongest pitch's confidence
                val strongPitches = polyResult.pitches.filter { it.confidence >= 0.20 }
                
                if (strongPitches.size >= 2) {
                    // Chord detected — create a polyphonic DetectedNote
                    val sortedPitches = strongPitches.sortedBy { it.midiNote }
                    val bassPitch = sortedPitches.first()
                    val additionalPitches = sortedPitches.drop(1).map { it.midiNote }
                    // Use max confidence (not average) so chords aren't penalized
                    val maxConfidence = strongPitches.maxOf { it.confidence }

                    // Identify chord
                    val allMidi = sortedPitches.map { it.midiNote }
                    val chord = ChordIdentifier.identifyChord(allMidi)

                    notes.add(DetectedNote(
                        midiNote = bassPitch.midiNote,
                        frequencyHz = bassPitch.frequencyHz,
                        onsetSeconds = startTime,
                        durationSeconds = endTime - startTime,
                        confidence = maxConfidence,
                        chordMidiNotes = additionalPitches,
                        chordName = chord?.name
                    ))
                    Log.d(TAG, "process: Chord at ${String.format("%.3f", startTime)}s: " +
                            "${chord?.name ?: "?"} = ${allMidi.map { PitchUtils.midiToNoteName(it) }}")
                } else {
                    // Only 1 strong pitch — treat as single note, cross-check with frame detection
                    val singlePitch = strongPitches.first()
                    val frameNote = buildNoteFromFrames(pitchedFrames, startTime, endTime)
                    if (frameNote != null && kotlin.math.abs(frameNote.midiNote - singlePitch.midiNote) <= 1) {
                        notes.add(frameNote)
                    } else if (frameNote != null) {
                        notes.add(frameNote) // Prefer frame-based detection for single notes
                    } else {
                        notes.add(DetectedNote(
                            midiNote = singlePitch.midiNote,
                            frequencyHz = singlePitch.frequencyHz,
                            onsetSeconds = startTime,
                            durationSeconds = endTime - startTime,
                            confidence = singlePitch.confidence
                        ))
                    }
                }
            } else if (polyResult.pitches.size == 1) {
                // Single note from FFT — cross-check with frame-based pitch for accuracy
                val fftNote = polyResult.pitches[0]
                val frameNote = buildNoteFromFrames(pitchedFrames, startTime, endTime)

                if (frameNote != null && kotlin.math.abs(frameNote.midiNote - fftNote.midiNote) <= 1) {
                    // Frame detection and FFT agree — prefer frame detection (more accurate for single notes)
                    notes.add(frameNote)
                } else if (frameNote != null) {
                    // Disagree — prefer whichever has higher confidence
                    notes.add(if (fftNote.confidence > 0.5) DetectedNote(
                        midiNote = fftNote.midiNote,
                        frequencyHz = fftNote.frequencyHz,
                        onsetSeconds = startTime,
                        durationSeconds = endTime - startTime,
                        confidence = fftNote.confidence
                    ) else frameNote)
                } else {
                    notes.add(DetectedNote(
                        midiNote = fftNote.midiNote,
                        frequencyHz = fftNote.frequencyHz,
                        onsetSeconds = startTime,
                        durationSeconds = endTime - startTime,
                        confidence = fftNote.confidence
                    ))
                }
            } else {
                // FFT found nothing — try frame-based pitch detection
                val frameNote = buildNoteFromFrames(pitchedFrames, startTime, endTime)
                if (frameNote != null) notes.add(frameNote)
            }
        }

        return notes
    }

    /**
     * Build a single DetectedNote from pitch frames within a time window.
     * Uses confidence-weighted median frequency.
     * Works with frames from any algorithm (YIN, MPM, HPS, or consensus).
     */
    private fun buildNoteFromFrames(
        pitchedFrames: List<PitchDetectionResult>,
        startTime: Double,
        endTime: Double
    ): DetectedNote? {
        val framesInWindow = pitchedFrames.filter {
            it.timeSeconds >= startTime - 0.01 && it.timeSeconds < endTime
        }

        if (framesInWindow.isEmpty()) return null

        // Use confidence-weighted median frequency for pitch selection
        val sortedByFreq = framesInWindow.sortedBy { it.frequencyHz }
        val totalConfidence = framesInWindow.sumOf { it.confidence }
        var accum = 0.0
        var medianFreq = sortedByFreq[sortedByFreq.size / 2].frequencyHz
        for (frame in sortedByFreq) {
            accum += frame.confidence
            if (accum >= totalConfidence / 2.0) {
                medianFreq = frame.frequencyHz
                break
            }
        }

        val midiNote = PitchUtils.frequencyToMidi(medianFreq)
        if (midiNote < 0) return null

        val avgConfidence = framesInWindow.map { it.confidence }.average()

        return DetectedNote(
            midiNote = midiNote,
            frequencyHz = medianFreq,
            onsetSeconds = startTime,
            durationSeconds = endTime - startTime,
            confidence = avgConfidence
        )
    }

    /**
     * Build notes from consecutive pitched frames when no onsets are detected.
     * Groups consecutive frames with the same pitch into single notes.
     */
    private fun buildNotesFromPitchRuns(
        pitchedFrames: List<PitchDetectionResult>,
        totalDuration: Double
    ): List<DetectedNote> {
        if (pitchedFrames.isEmpty()) return emptyList()

        val notes = mutableListOf<DetectedNote>()
        var runStart = pitchedFrames.first()
        var runMidi = PitchUtils.frequencyToMidi(runStart.frequencyHz)
        var runFrequencies = mutableListOf(runStart.frequencyHz)
        var runConfidences = mutableListOf(runStart.confidence)

        for (i in 1 until pitchedFrames.size) {
            val frame = pitchedFrames[i]
            val currentMidi = PitchUtils.frequencyToMidi(frame.frequencyHz)

            if (currentMidi == runMidi) {
                // Continue the run
                runFrequencies.add(frame.frequencyHz)
                runConfidences.add(frame.confidence)
            } else {
                // End the run, start a new one
                if (runMidi >= 0) {
                    val endTime = frame.timeSeconds
                    notes.add(DetectedNote(
                        midiNote = runMidi,
                        frequencyHz = runFrequencies.sorted()[runFrequencies.size / 2],
                        onsetSeconds = runStart.timeSeconds,
                        durationSeconds = endTime - runStart.timeSeconds,
                        confidence = runConfidences.average()
                    ))
                }
                runStart = frame
                runMidi = currentMidi
                runFrequencies = mutableListOf(frame.frequencyHz)
                runConfidences = mutableListOf(frame.confidence)
            }
        }

        // Don't forget the last run
        if (runMidi >= 0) {
            val lastFrame = pitchedFrames.last()
            notes.add(DetectedNote(
                midiNote = runMidi,
                frequencyHz = runFrequencies.sorted()[runFrequencies.size / 2],
                onsetSeconds = runStart.timeSeconds,
                durationSeconds = (lastFrame.timeSeconds - runStart.timeSeconds).coerceAtLeast(0.05),
                confidence = runConfidences.average()
            ))
        }

        return notes
    }

    /**
     * Create an empty transcription result for edge cases.
     */
    private fun emptyResult(): TranscriptionResult {
        return TranscriptionResult(
            notes = emptyList(),
            keySignature = KeySignature.C_MAJOR,
            timeSignature = TimeSignature.FOUR_FOUR,
            tempoBpm = defaultTempoBpm
        )
    }
}
