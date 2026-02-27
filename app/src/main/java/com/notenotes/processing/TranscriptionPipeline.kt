package com.notenotes.processing

import android.util.Log
import com.notenotes.model.*
import com.notenotes.util.PitchUtils

private const val TAG = "NNPipeline"

/**
 * Main transcription pipeline that orchestrates all processing steps.
 * 
 * Flow: Raw Audio → Pitch Detection → Onset Detection → Key Detection 
 *       → Time Sig Detection → Rhythm Quantization → TranscriptionResult
 */
class TranscriptionPipeline(
    private val sampleRate: Int = 44100,
    private val defaultTempoBpm: Int = 120
) {

    private val pitchDetector = YinPitchDetector(sampleRate = sampleRate)
    private val onsetDetector = OnsetDetector(sampleRate = sampleRate)
    private val keyDetector = KeyDetector()
    private val timeSignatureDetector = TimeSignatureDetector(sampleRate = sampleRate)
    private val rhythmQuantizer = RhythmQuantizer()

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

        // Step 1: Detect pitches
        Log.d(TAG, "process: Step 1 - Pitch detection...")
        val pitchResults = pitchDetector.detectPitches(samples)
        val pitchedFrames = pitchResults.filter { it.isPitched }
        Log.d(TAG, "process: Step 1 done - ${pitchResults.size} total frames, ${pitchedFrames.size} pitched")

        if (pitchedFrames.isEmpty()) {
            Log.w(TAG, "process: No pitched frames found, returning empty result")
            return emptyResult()
        }

        // Step 2: Detect onsets
        Log.d(TAG, "process: Step 2 - Onset detection...")
        val onsetTimes = onsetDetector.detectOnsets(samples)
        Log.d(TAG, "process: Step 2 done - ${onsetTimes.size} onsets: ${onsetTimes.take(10)}")

        // Step 3: Combine pitch + onset into detected notes
        Log.d(TAG, "process: Step 3 - Building detected notes...")
        val detectedNotes = buildDetectedNotes(pitchedFrames, onsetTimes, samples.size)
        Log.d(TAG, "process: Step 3 done - ${detectedNotes.size} detected notes")

        if (detectedNotes.isEmpty()) {
            Log.w(TAG, "process: No detected notes after combining pitch+onset")
            return emptyResult()
        }

        // Step 4: Detect key signature
        Log.d(TAG, "process: Step 4 - Key detection...")
        val keySignature = userKeySignature ?: run {
            val midiNotes = detectedNotes.map { it.midiNote }
            keyDetector.detectKey(midiNotes)
        }
        Log.d(TAG, "process: Step 4 done - key=$keySignature")

        // Step 5: Estimate tempo
        Log.d(TAG, "process: Step 5 - Tempo estimation...")
        val tempoBpm = userTempoBpm ?: run {
            if (onsetTimes.size >= 3) {
                timeSignatureDetector.estimateTempo(onsetTimes).toInt()
            } else {
                defaultTempoBpm
            }
        }
        Log.d(TAG, "process: Step 5 done - tempo=${tempoBpm}bpm")

        // Step 6: Detect time signature
        Log.d(TAG, "process: Step 6 - Time signature detection...")
        val timeSignature = userTimeSignature ?: run {
            timeSignatureDetector.detectTimeSignature(
                onsetTimesSeconds = onsetTimes,
                tempoBpm = tempoBpm.toDouble()
            )
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
     * Build detected notes from pitch detection results and onset times.
     * 
     * Associates each onset with the dominant pitch during that note's duration.
     */
    private fun buildDetectedNotes(
        pitchedFrames: List<PitchDetectionResult>,
        onsetTimes: List<Double>,
        totalSamples: Int
    ): List<DetectedNote> {
        val totalDuration = totalSamples.toDouble() / sampleRate

        if (onsetTimes.isEmpty()) {
            // No onsets detected — treat all pitched frames as one long note
            return buildNotesFromPitchRuns(pitchedFrames, totalDuration)
        }

        val notes = mutableListOf<DetectedNote>()

        for (i in onsetTimes.indices) {
            val startTime = onsetTimes[i]
            val endTime = if (i + 1 < onsetTimes.size) onsetTimes[i + 1] else totalDuration

            if (endTime <= startTime) continue

            // Find pitched frames within this onset window
            val framesInWindow = pitchedFrames.filter { 
                it.timeSeconds >= startTime - 0.01 && it.timeSeconds < endTime 
            }

            if (framesInWindow.isEmpty()) continue

            // Use the median frequency as the note pitch (robust to outliers)
            val frequencies = framesInWindow.map { it.frequencyHz }.sorted()
            val medianFreq = frequencies[frequencies.size / 2]
            val midiNote = PitchUtils.frequencyToMidi(medianFreq)

            if (midiNote < 0) continue

            val avgConfidence = framesInWindow.map { it.confidence }.average()

            notes.add(DetectedNote(
                midiNote = midiNote,
                frequencyHz = medianFreq,
                onsetSeconds = startTime,
                durationSeconds = endTime - startTime,
                confidence = avgConfidence
            ))
        }

        return notes
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
