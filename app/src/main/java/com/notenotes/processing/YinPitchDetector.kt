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
    private val silenceThreshold: Double = 100.0  // RMS amplitude below this = silence
) {

    /**
     * Detect pitches in the given audio samples.
     * @param samples 16-bit PCM audio samples (mono)
     * @return List of PitchDetectionResult, one per analyzed frame
     */
    fun detectPitches(samples: ShortArray): List<PitchDetectionResult> {
        if (samples.size < frameSize) return emptyList()

        val results = mutableListOf<PitchDetectionResult>()
        var frameStart = 0

        while (frameStart + frameSize <= samples.size) {
            val frame = FloatArray(frameSize) { i -> samples[frameStart + i].toFloat() }
            val timeSeconds = frameStart.toDouble() / sampleRate

            // Check for silence
            val rms = computeRms(frame)
            if (rms < silenceThreshold) {
                results.add(PitchDetectionResult(
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

            // Step 3: Absolute threshold
            val tauEstimate = absoluteThreshold(cmndf, halfFrame)

            if (tauEstimate == -1) {
                // No pitch found
                results.add(PitchDetectionResult(
                    frequencyHz = -1.0,
                    confidence = 0.0,
                    isPitched = false,
                    timeSeconds = timeSeconds
                ))
            } else {
                // Step 4: Parabolic interpolation
                val betterTau = parabolicInterpolation(cmndf, tauEstimate)
                val frequency = sampleRate.toDouble() / betterTau
                val confidence = 1.0 - cmndf[tauEstimate].coerceIn(0.0, 1.0)

                // Validate frequency range (20 Hz to 5000 Hz)
                val isPitched = frequency in 20.0..5000.0 && confidence > 0.5

                results.add(PitchDetectionResult(
                    frequencyHz = if (isPitched) frequency else -1.0,
                    confidence = if (isPitched) confidence else 0.0,
                    isPitched = isPitched,
                    timeSeconds = timeSeconds
                ))
            }

            frameStart += hopSize
        }

        return results
    }

    /**
     * Detect pitches and return only the pitched frames (convenience method).
     */
    fun detectPitchedFrames(samples: ShortArray): List<PitchDetectionResult> {
        return detectPitches(samples).filter { it.isPitched }
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
     * Step 3: Absolute threshold.
     * Find the first tau where cmndf dips below the threshold, then find the local minimum.
     * Returns the tau index, or -1 if no pitch found.
     */
    private fun absoluteThreshold(cmndf: DoubleArray, maxTau: Int): Int {
        // Min period: sample_rate / max_freq. For 5000 Hz at 44100: ~9 samples
        val minTau = (sampleRate / 5000.0).toInt().coerceAtLeast(2)
        // Max period: sample_rate / min_freq. For 20 Hz at 44100: ~2205 samples
        val maxSearchTau = minOf(maxTau, (sampleRate / 20.0).toInt())

        // Find first dip below threshold
        var tau = minTau
        while (tau < maxSearchTau) {
            if (cmndf[tau] < threshold) {
                // Found a dip — now find the local minimum
                while (tau + 1 < maxSearchTau && cmndf[tau + 1] < cmndf[tau]) {
                    tau++
                }
                return tau
            }
            tau++
        }

        // If no dip below threshold, find the global minimum as fallback
        var minVal = Double.MAX_VALUE
        var minIdx = -1
        for (t in minTau until maxSearchTau) {
            if (cmndf[t] < minVal) {
                minVal = cmndf[t]
                minIdx = t
            }
        }
        // Only return if the value is reasonably low
        return if (minVal < 0.5) minIdx else -1
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
