package com.notenotes.processing

import com.notenotes.model.PitchDetectionResult

/**
 * YIN pitch detection algorithm implementation.
 * 
 * Reference: "YIN, a fundamental frequency estimator for speech and music"
 * de Cheveigné & Kawahara, JASA 2002
 * 
 * This is a custom implementation to avoid GPL-licensed TarsosDSP dependency.
 */
class YinPitchDetector(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 2048,
    private val hopSize: Int = 1024,
    private val threshold: Double = 0.15,
    private val silenceThreshold: Double = 100.0,  // RMS amplitude below this = silence
    private val medianFilterSize: Int = 5           // median filter window for octave correction
) {

    /**
     * Pitch candidate from CMNDF analysis.
     */
    private data class PitchCandidate(
        val tau: Int,
        val frequency: Double,
        val confidence: Double
    )

    /**
     * Detect pitches in the given audio samples.
     * Applies multi-candidate selection and post-hoc octave jump correction.
     *
     * @param samples 16-bit PCM audio samples (mono)
     * @return List of PitchDetectionResult, one per analyzed frame
     */
    fun detectPitches(samples: ShortArray): List<PitchDetectionResult> {
        if (samples.size < frameSize) return emptyList()

        val rawResults = mutableListOf<PitchDetectionResult>()
        var frameStart = 0

        while (frameStart + frameSize <= samples.size) {
            val frame = FloatArray(frameSize) { i -> samples[frameStart + i].toFloat() }
            val timeSeconds = frameStart.toDouble() / sampleRate

            // Check for silence
            val rms = computeRms(frame)
            if (rms < silenceThreshold) {
                rawResults.add(PitchDetectionResult(
                    frequencyHz = -1.0,
                    confidence = 0.0,
                    isPitched = false,
                    timeSeconds = timeSeconds
                ))
                frameStart += hopSize
                continue
            }

            // Step 1: Difference function
            val halfFrame = frameSize / 2
            val difference = differenceFunction(frame, halfFrame)

            // Step 2: Cumulative mean normalized difference
            val cmndf = cumulativeMeanNormalizedDifference(difference, halfFrame)

            // Step 3: Multi-candidate threshold - collect ALL dips below threshold
            val candidates = findAllCandidates(cmndf, halfFrame)

            if (candidates.isEmpty()) {
                rawResults.add(PitchDetectionResult(
                    frequencyHz = -1.0,
                    confidence = 0.0,
                    isPitched = false,
                    timeSeconds = timeSeconds
                ))
            } else {
                // Pick the best candidate: prefer the smallest tau (first CMNDF dip = fundamental)
                // among candidates with similar confidence, to avoid subharmonic artifacts
                val best = selectBestCandidate(candidates)

                val isPitched = best.frequency in 20.0..5000.0 && best.confidence > 0.5

                rawResults.add(PitchDetectionResult(
                    frequencyHz = if (isPitched) best.frequency else -1.0,
                    confidence = if (isPitched) best.confidence else 0.0,
                    isPitched = isPitched,
                    timeSeconds = timeSeconds
                ))
            }

            frameStart += hopSize
        }

        // Post-processing: octave jump correction + median filter
        return correctOctaveJumps(rawResults)
    }

    /**
     * Detect pitches and return only the pitched frames (convenience method).
     */
    fun detectPitchedFrames(samples: ShortArray): List<PitchDetectionResult> {
        return detectPitches(samples).filter { it.isPitched }
    }

    /**
     * Find all CMNDF dips below threshold (multi-candidate approach).
     * Returns candidates sorted by confidence (best first).
     */
    private fun findAllCandidates(cmndf: DoubleArray, maxTau: Int): List<PitchCandidate> {
        val minTau = (sampleRate / 5000.0).toInt().coerceAtLeast(2)
        val maxSearchTau = minOf(maxTau, (sampleRate / 20.0).toInt())
        val candidates = mutableListOf<PitchCandidate>()

        var tau = minTau
        while (tau < maxSearchTau) {
            if (cmndf[tau] < threshold) {
                // Find local minimum of this dip
                var minTauLocal = tau
                while (minTauLocal + 1 < maxSearchTau && cmndf[minTauLocal + 1] < cmndf[minTauLocal]) {
                    minTauLocal++
                }

                val betterTau = parabolicInterpolation(cmndf, minTauLocal)
                val frequency = sampleRate.toDouble() / betterTau
                val confidence = 1.0 - cmndf[minTauLocal].coerceIn(0.0, 1.0)

                if (frequency in 20.0..5000.0) {
                    candidates.add(PitchCandidate(minTauLocal, frequency, confidence))
                }

                // Skip past this dip
                tau = minTauLocal + 1
                // Skip until we rise above threshold again
                while (tau < maxSearchTau && cmndf[tau] < threshold) tau++
            } else {
                tau++
            }
        }

        // If no candidates below threshold, try global minimum as fallback
        if (candidates.isEmpty()) {
            var minVal = Double.MAX_VALUE
            var minIdx = -1
            for (t in minTau until maxSearchTau) {
                if (cmndf[t] < minVal) {
                    minVal = cmndf[t]
                    minIdx = t
                }
            }
            if (minIdx >= 0 && minVal < 0.5) {
                val betterTau = parabolicInterpolation(cmndf, minIdx)
                val frequency = sampleRate.toDouble() / betterTau
                val confidence = 1.0 - minVal.coerceIn(0.0, 1.0)
                if (frequency in 20.0..5000.0) {
                    candidates.add(PitchCandidate(minIdx, frequency, confidence))
                }
            }
        }

        return candidates.sortedByDescending { it.confidence }
    }

    /**
     * Select the best pitch candidate.
     * 
     * Strategy: Among candidates with confidence within 15% of the best,
     * prefer the one with the SMALLEST tau (highest frequency / first CMNDF dip).
     * 
     * In YIN's CMNDF, the first dip below threshold corresponds to the fundamental.
     * Dips at larger taus (2*, 3*, ...) are subharmonic artifacts that occur because
     * the signal also repeats at multiples of its period.
     */
    private fun selectBestCandidate(candidates: List<PitchCandidate>): PitchCandidate {
        if (candidates.size == 1) return candidates[0]

        val bestConfidence = candidates[0].confidence
        val confThreshold = bestConfidence * 0.85 // within 15%

        // Among high-confidence candidates, prefer the first dip (smallest tau)
        val viableCandidates = candidates.filter { it.confidence >= confThreshold }

        // Pick the candidate with the smallest tau (highest frequency, first dip = fundamental)
        return viableCandidates.minByOrNull { it.tau } ?: candidates[0]
    }

    /**
     * Post-processing: correct octave jumps in the pitch trajectory.
     * 
     * Uses median filtering: for each pitched frame, compare to the median
     * of nearby frames. If it differs by ~12 semitones (octave), snap it.
     */
    private fun correctOctaveJumps(results: List<PitchDetectionResult>): List<PitchDetectionResult> {
        if (results.size < 3) return results

        val pitchedIndices = results.indices.filter { results[it].isPitched }
        if (pitchedIndices.size < 3) return results

        val corrected = results.toMutableList()

        // Extract MIDI notes for pitched frames
        val midiNotes = pitchedIndices.map { idx ->
            val freq = results[idx].frequencyHz
            69.0 + 12.0 * kotlin.math.ln(freq / 440.0) / kotlin.math.ln(2.0)
        }

        // Apply median filter to detect octave outliers
        val halfWindow = medianFilterSize / 2
        for (i in pitchedIndices.indices) {
            val windowStart = maxOf(0, i - halfWindow)
            val windowEnd = minOf(pitchedIndices.size, i + halfWindow + 1)
            val windowMidis = midiNotes.subList(windowStart, windowEnd).sorted()
            val medianMidi = windowMidis[windowMidis.size / 2]

            val currentMidi = midiNotes[i]
            val diff = currentMidi - medianMidi

            // If off by roughly an octave (10-14 semitones), correct
            if (kotlin.math.abs(diff) in 10.0..14.0) {
                val correctedFreq = if (diff > 0) {
                    results[pitchedIndices[i]].frequencyHz / 2.0  // was an octave too high
                } else {
                    results[pitchedIndices[i]].frequencyHz * 2.0  // was an octave too low
                }
                val correctedConfidence = results[pitchedIndices[i]].confidence * 0.9 // slightly reduce confidence

                corrected[pitchedIndices[i]] = results[pitchedIndices[i]].copy(
                    frequencyHz = correctedFreq,
                    confidence = correctedConfidence
                )
            }
        }

        return corrected
    }

    /**
     * Step 1: Compute the difference function d(tau).
     * d(tau) = sum_{j=0}^{W-1} (x_j - x_{j+tau})^2
     */
    private fun differenceFunction(frame: FloatArray, maxTau: Int): DoubleArray {
        val diff = DoubleArray(maxTau)
        for (tau in 0 until maxTau) {
            var sum = 0.0
            for (j in 0 until maxTau) {
                val delta = (frame[j] - frame[j + tau]).toDouble()
                sum += delta * delta
            }
            diff[tau] = sum
        }
        return diff
    }

    /**
     * Step 2: Cumulative mean normalized difference function d'(tau).
     * d'(0) = 1
     * d'(tau) = d(tau) / ((1/tau) * sum_{j=1}^{tau} d(j))   for tau > 0
     */
    private fun cumulativeMeanNormalizedDifference(difference: DoubleArray, maxTau: Int): DoubleArray {
        val cmndf = DoubleArray(maxTau)
        cmndf[0] = 1.0
        var runningSum = 0.0

        for (tau in 1 until maxTau) {
            runningSum += difference[tau]
            cmndf[tau] = if (runningSum == 0.0) 1.0
                         else difference[tau] * tau / runningSum
        }
        return cmndf
    }

    /**
     * Step 4: Parabolic interpolation for sub-sample accuracy.
     * Fits a parabola through cmndf[tau-1], cmndf[tau], cmndf[tau+1]
     * and returns the interpolated minimum position.
     */
    private fun parabolicInterpolation(cmndf: DoubleArray, tau: Int): Double {
        if (tau <= 0 || tau >= cmndf.size - 1) return tau.toDouble()

        val s0 = cmndf[tau - 1]
        val s1 = cmndf[tau]
        val s2 = cmndf[tau + 1]

        val denominator = 2.0 * (2.0 * s1 - s2 - s0)
        return if (denominator == 0.0) tau.toDouble()
               else tau + (s2 - s0) / denominator
    }

    /**
     * Compute RMS (root mean square) amplitude of a frame.
     */
    private fun computeRms(frame: FloatArray): Double {
        var sum = 0.0
        for (sample in frame) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / frame.size)
    }
}
