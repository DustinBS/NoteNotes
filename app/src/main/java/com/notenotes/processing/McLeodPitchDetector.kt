package com.notenotes.processing

import com.notenotes.model.PitchDetectionResult

/**
 * McLeod Pitch Method (MPM) pitch detection algorithm.
 *
 * Reference: "A Smarter Way to Find Pitch" - Philip McLeod, Geoff Wyvill (2005)
 *
 * Uses the Normalized Square Difference Function (NSDF) instead of
 * YIN's CMNDF. Key advantages:
 * - Better handling of low-amplitude signals
 * - More robust peak-picking via positive zero crossings
 * - Naturally normalized to [-1, 1] range
 */
class McLeodPitchDetector(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 2048,
    private val hopSize: Int = 1024,
    private val cutoff: Double = 0.93,          // key max selection cutoff
    private val smallCutoff: Double = 0.5,      // minimum peak height to consider
    private val silenceThreshold: Double = 100.0,
    private val medianFilterSize: Int = 5
) {

    /**
     * Detect pitches in the given audio samples using MPM.
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

            // Compute NSDF
            val nsdf = computeNsdf(frame)

            // Find positive peaks after zero crossings
            val peaks = findPositivePeaks(nsdf)

            if (peaks.isEmpty()) {
                rawResults.add(PitchDetectionResult(
                    frequencyHz = -1.0,
                    confidence = 0.0,
                    isPitched = false,
                    timeSeconds = timeSeconds
                ))
            } else {
                // MPM key max selection: find highest peak, then pick first peak >= cutoff * highest
                val highestPeak = peaks.maxByOrNull { it.second }!!
                val threshold = highestPeak.second * cutoff

                // Pick the first peak that exceeds the cutoff threshold
                val selectedPeak = peaks.firstOrNull { it.second >= threshold } ?: highestPeak

                // Parabolic interpolation for sub-sample accuracy
                val tau = parabolicInterpolation(nsdf, selectedPeak.first)
                val frequency = sampleRate.toDouble() / tau
                val confidence = selectedPeak.second.coerceIn(0.0, 1.0)

                val isPitched = frequency in 20.0..5000.0 && confidence > 0.4

                rawResults.add(PitchDetectionResult(
                    frequencyHz = if (isPitched) frequency else -1.0,
                    confidence = if (isPitched) confidence else 0.0,
                    isPitched = isPitched,
                    timeSeconds = timeSeconds
                ))
            }

            frameStart += hopSize
        }

        // Post-processing: octave jump correction
        return correctOctaveJumps(rawResults)
    }

    /**
     * Compute the Normalized Square Difference Function (NSDF).
     *
     * NSDF(tau) = 2 * r(tau) / (m(tau))
     * where r(tau) is autocorrelation and m(tau) is the normalization term.
     *
     * The NSDF ranges from -1 to +1.
     */
    private fun computeNsdf(frame: FloatArray): DoubleArray {
        val n = frame.size
        val nsdf = DoubleArray(n)

        for (tau in 0 until n) {
            var acf = 0.0   // autocorrelation
            var m = 0.0     // normalization term: sum of x[j]^2 + x[j+tau]^2
            val limit = n - tau

            for (j in 0 until limit) {
                acf += frame[j].toDouble() * frame[j + tau].toDouble()
                m += frame[j].toDouble() * frame[j].toDouble() +
                     frame[j + tau].toDouble() * frame[j + tau].toDouble()
            }

            nsdf[tau] = if (m > 0.0) 2.0 * acf / m else 0.0
        }

        return nsdf
    }

    /**
     * Find positive peaks in the NSDF that occur after zero crossings.
     * Returns list of (tau, peakValue) pairs.
     *
     * Critical: We MUST skip the initial positive region of the NSDF
     * (from tau=0 to the first zero crossing) because it corresponds to
     * a period of 0 (infinite frequency), not a real pitch.
     * Only consider peaks in positive regions that appear AFTER
     * the NSDF has first gone negative.
     */
    private fun findPositivePeaks(nsdf: DoubleArray): List<Pair<Int, Double>> {
        val minTau = (sampleRate / 5000.0).toInt().coerceAtLeast(2)
        val maxTau = minOf(nsdf.size - 1, (sampleRate / 20.0).toInt())
        val peaks = mutableListOf<Pair<Int, Double>>()

        // We must see a negative value first before we start looking for peaks
        var hasSeenNegative = false
        var inPositiveRegion = false
        var peakTau = -1
        var peakVal = Double.MIN_VALUE

        for (tau in minTau..maxTau) {
            if (nsdf[tau] < 0) {
                // Entering or continuing in negative region
                hasSeenNegative = true
                if (inPositiveRegion && peakTau >= 0 && peakVal >= smallCutoff) {
                    // We just left a positive region — save the peak we found
                    peaks.add(Pair(peakTau, peakVal))
                }
                inPositiveRegion = false
                peakTau = -1
                peakVal = Double.MIN_VALUE
            } else if (hasSeenNegative) {
                // In a positive region AFTER we've seen a negative crossing
                inPositiveRegion = true
                if (nsdf[tau] > peakVal) {
                    peakVal = nsdf[tau]
                    peakTau = tau
                }
            }
            // else: still in the initial positive region before first zero crossing — skip
        }

        // Don't forget the last peak
        if (inPositiveRegion && peakTau >= 0 && peakVal >= smallCutoff) {
            peaks.add(Pair(peakTau, peakVal))
        }

        return peaks
    }

    /**
     * Parabolic interpolation for sub-sample accuracy.
     */
    private fun parabolicInterpolation(data: DoubleArray, tau: Int): Double {
        if (tau <= 0 || tau >= data.size - 1) return tau.toDouble()

        val s0 = data[tau - 1]
        val s1 = data[tau]
        val s2 = data[tau + 1]

        val denominator = 2.0 * (2.0 * s1 - s2 - s0)
        return if (denominator == 0.0) tau.toDouble()
        else tau + (s2 - s0) / denominator
    }

    /**
     * Post-processing: correct octave jumps via median filtering.
     */
    private fun correctOctaveJumps(results: List<PitchDetectionResult>): List<PitchDetectionResult> {
        if (results.size < 3) return results

        val pitchedIndices = results.indices.filter { results[it].isPitched }
        if (pitchedIndices.size < 3) return results

        val corrected = results.toMutableList()
        val midiNotes = pitchedIndices.map { idx ->
            val freq = results[idx].frequencyHz
            69.0 + 12.0 * kotlin.math.ln(freq / 440.0) / kotlin.math.ln(2.0)
        }

        val halfWindow = medianFilterSize / 2
        for (i in pitchedIndices.indices) {
            val windowStart = maxOf(0, i - halfWindow)
            val windowEnd = minOf(pitchedIndices.size, i + halfWindow + 1)
            val windowMidis = midiNotes.subList(windowStart, windowEnd).sorted()
            val medianMidi = windowMidis[windowMidis.size / 2]

            val currentMidi = midiNotes[i]
            val diff = currentMidi - medianMidi

            if (kotlin.math.abs(diff) in 10.0..14.0) {
                val correctedFreq = if (diff > 0) {
                    results[pitchedIndices[i]].frequencyHz / 2.0
                } else {
                    results[pitchedIndices[i]].frequencyHz * 2.0
                }
                corrected[pitchedIndices[i]] = results[pitchedIndices[i]].copy(
                    frequencyHz = correctedFreq,
                    confidence = results[pitchedIndices[i]].confidence * 0.9
                )
            }
        }

        return corrected
    }

    private fun computeRms(frame: FloatArray): Double {
        var sum = 0.0
        for (sample in frame) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / frame.size)
    }
}
