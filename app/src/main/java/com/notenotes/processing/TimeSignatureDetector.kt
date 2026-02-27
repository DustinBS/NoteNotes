package com.notenotes.processing

import com.notenotes.model.TimeSignature
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Time signature detection via beat grouping analysis.
 * 
 * Analyzes onset patterns and accent levels to determine the most likely time signature.
 * 
 * Approach:
 * 1. Detect onsets and estimate beat positions
 * 2. Analyze accent patterns (louder onsets = likely downbeats)
 * 3. Try grouping beats into candidate meters
 * 4. Score each candidate by accent pattern alignment
 * 5. Default to 4/4 if ambiguous
 * 
 * Supported: 2/4, 3/4, 4/4, 5/4, 6/8, 3/8, 7/8, 2/2
 */
class TimeSignatureDetector(
    private val sampleRate: Int = 44100
) {

    /**
     * Detect time signature from onset times and optional amplitude levels.
     * 
     * @param onsetTimesSeconds list of onset times in seconds
     * @param onsetAmplitudes optional list of amplitudes at each onset (same length as onsetTimes)
     * @param tempoBpm estimated tempo in BPM (helps determine beat positions)
     * @return the most likely time signature
     */
    fun detectTimeSignature(
        onsetTimesSeconds: List<Double>,
        onsetAmplitudes: List<Double>? = null,
        tempoBpm: Double = 120.0
    ): TimeSignature {
        // Too few onsets to determine time signature
        if (onsetTimesSeconds.size < 4) return TimeSignature.FOUR_FOUR

        val beatDuration = 60.0 / tempoBpm  // seconds per beat

        // Quantize onsets to beat positions
        val beatPositions = onsetTimesSeconds.map { it / beatDuration }

        // Compute inter-onset intervals in beats
        val iois = mutableListOf<Double>()
        for (i in 1 until beatPositions.size) {
            iois.add(beatPositions[i] - beatPositions[i - 1])
        }

        if (iois.isEmpty()) return TimeSignature.FOUR_FOUR

        // Determine accent pattern if amplitudes provided
        val accents = if (onsetAmplitudes != null && onsetAmplitudes.size == onsetTimesSeconds.size) {
            normalizeAmplitudes(onsetAmplitudes)
        } else {
            // Without amplitude data, assume first onset is accented, then alternate
            List(onsetTimesSeconds.size) { if (it == 0) 1.0 else 0.5 }
        }

        // Score each candidate time signature
        val candidates = TimeSignature.SUPPORTED
        val scores = mutableMapOf<TimeSignature, Double>()

        for (candidate in candidates) {
            scores[candidate] = scoreTimeSignature(candidate, beatPositions, accents, tempoBpm)
        }

        // Return the highest scoring, defaulting to 4/4
        val best = scores.maxByOrNull { it.value }
        return if (best != null && best.value > 0.0) best.key else TimeSignature.FOUR_FOUR
    }

    /**
     * Score how well onsets fit a given time signature.
     */
    private fun scoreTimeSignature(
        timeSig: TimeSignature,
        beatPositions: List<Double>,
        accents: List<Double>,
        tempoBpm: Double
    ): Double {
        val beatsPerMeasure = when {
            timeSig.beatType == 8 && timeSig.beats % 3 == 0 -> timeSig.beats / 3.0  // compound time
            timeSig.beatType == 2 -> timeSig.beats * 2.0  // half note gets the beat
            else -> timeSig.beats.toDouble()
        }

        if (beatsPerMeasure <= 0) return 0.0

        var score = 0.0
        var count = 0

        for (i in beatPositions.indices) {
            val posInMeasure = beatPositions[i] % beatsPerMeasure
            val accent = accents[i]

            // Strong beats should have higher accents
            val isDownbeat = posInMeasure < 0.15 || abs(posInMeasure - beatsPerMeasure) < 0.15
            val isMidStrong = when {
                timeSig == TimeSignature.FOUR_FOUR && abs(posInMeasure - 2.0) < 0.15 -> true
                timeSig == TimeSignature.SIX_EIGHT && abs(posInMeasure - 1.0) < 0.15 -> true
                else -> false
            }

            if (isDownbeat) {
                score += accent * 2.0  // reward high accent on downbeat
            } else if (isMidStrong) {
                score += accent * 1.0
            } else {
                score += (1.0 - accent) * 0.5  // reward low accent on weak beats
            }
            count++
        }

        // Bonus: check if total duration fits neatly into measures
        if (beatPositions.isNotEmpty()) {
            val totalBeats = beatPositions.last()
            val numMeasures = totalBeats / beatsPerMeasure
            val remainder = numMeasures - numMeasures.toInt()
            if (remainder < 0.15 || remainder > 0.85) {
                score += 1.0  // fits neatly
            }
        }

        return if (count > 0) score / count else 0.0
    }

    /**
     * Estimate tempo (BPM) from onset times using inter-onset interval analysis.
     * Returns estimated BPM or 120.0 as default.
     */
    fun estimateTempo(onsetTimesSeconds: List<Double>): Double {
        if (onsetTimesSeconds.size < 3) return 120.0

        // Compute inter-onset intervals
        val iois = mutableListOf<Double>()
        for (i in 1 until onsetTimesSeconds.size) {
            val ioi = onsetTimesSeconds[i] - onsetTimesSeconds[i - 1]
            if (ioi > 0.1 && ioi < 2.0) {  // reasonable range: 30-600 BPM
                iois.add(ioi)
            }
        }

        if (iois.isEmpty()) return 120.0

        // Use median IOI as the beat duration estimate
        val sorted = iois.sorted()
        val medianIoi = sorted[sorted.size / 2]

        // BPM = 60 / beat_duration
        val bpm = 60.0 / medianIoi

        // Quantize to reasonable BPM range (40-240)
        var adjustedBpm = bpm
        while (adjustedBpm < 40) adjustedBpm *= 2
        while (adjustedBpm > 240) adjustedBpm /= 2

        return adjustedBpm.roundToInt().toDouble()
    }

    /**
     * Normalize amplitudes to 0.0-1.0 range.
     */
    private fun normalizeAmplitudes(amplitudes: List<Double>): List<Double> {
        val max = amplitudes.maxOrNull() ?: 1.0
        val min = amplitudes.minOrNull() ?: 0.0
        val range = max - min
        return if (range == 0.0) {
            amplitudes.map { 0.5 }
        } else {
            amplitudes.map { (it - min) / range }
        }
    }
}
