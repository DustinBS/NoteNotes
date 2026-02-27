package com.notenotes.processing

import com.notenotes.model.PitchDetectionResult

/**
 * Harmonic Product Spectrum (HPS) pitch detection algorithm.
 *
 * Reference: Schroeder (1968), Noll (1969)
 *
 * Computes FFT magnitude spectrum, then downsamples by factors 2, 3, 4, 5
 * and multiplies (in log domain: sums). The peak of the product spectrum
 * corresponds to the fundamental frequency.
 *
 * Advantages:
 * - Very robust to harmonics (fundamentally solves the octave-error problem)
 * - Works well for signals with strong harmonic content (guitar strings)
 *
 * Disadvantages:
 * - Frequency resolution limited by FFT bin size (uses larger frameSize=4096)
 * - Slower than time-domain methods
 */
class HpsPitchDetector(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 4096,       // larger for better frequency resolution
    private val hopSize: Int = 1024,
    private val numHarmonics: Int = 5,       // downsample factors: 1..numHarmonics
    private val minFrequency: Double = 60.0, // below E2 (82 Hz) we still want headroom
    private val maxFrequency: Double = 2000.0,
    private val silenceThreshold: Double = 100.0,
    private val medianFilterSize: Int = 5
) {

    private val window = FFT.hannWindow(frameSize)

    /**
     * Detect pitches in the given audio samples using Harmonic Product Spectrum.
     *
     * @param samples 16-bit PCM audio samples (mono)
     * @return List of PitchDetectionResult, one per analyzed frame
     */
    fun detectPitches(samples: ShortArray): List<PitchDetectionResult> {
        if (samples.size < frameSize) return emptyList()

        val rawResults = mutableListOf<PitchDetectionResult>()
        var frameStart = 0

        while (frameStart + frameSize <= samples.size) {
            val timeSeconds = frameStart.toDouble() / sampleRate

            // Extract frame and apply window
            val real = DoubleArray(frameSize) { i ->
                samples[frameStart + i].toDouble() * window[i]
            }
            val imag = DoubleArray(frameSize)

            // Check for silence
            val rms = computeRms(real)
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

            // FFT
            FFT.fft(real, imag)
            val magnitudes = FFT.magnitudeSpectrum(real, imag)

            // Determine bin range for our frequency limits
            val minBin = (minFrequency * frameSize / sampleRate).toInt().coerceAtLeast(1)
            val maxBin = (maxFrequency * frameSize / sampleRate).toInt().coerceAtMost(magnitudes.size / numHarmonics)

            if (maxBin <= minBin) {
                rawResults.add(PitchDetectionResult(
                    frequencyHz = -1.0,
                    confidence = 0.0,
                    isPitched = false,
                    timeSeconds = timeSeconds
                ))
                frameStart += hopSize
                continue
            }

            // Compute HPS in log domain (sum of logs = log of product)
            val hps = DoubleArray(maxBin - minBin)
            for (bin in minBin until maxBin) {
                var logSum = 0.0
                var validHarmonics = 0
                for (h in 1..numHarmonics) {
                    val harmonicBin = bin * h
                    if (harmonicBin < magnitudes.size && magnitudes[harmonicBin] > 0) {
                        logSum += kotlin.math.ln(magnitudes[harmonicBin])
                        validHarmonics++
                    }
                }
                hps[bin - minBin] = if (validHarmonics > 0) logSum else Double.NEGATIVE_INFINITY
            }

            // Find peak in HPS
            var peakIdx = 0
            var peakVal = Double.NEGATIVE_INFINITY
            for (i in hps.indices) {
                if (hps[i] > peakVal) {
                    peakVal = hps[i]
                    peakIdx = i
                }
            }

            val peakBin = peakIdx + minBin

            if (peakVal == Double.NEGATIVE_INFINITY) {
                rawResults.add(PitchDetectionResult(
                    frequencyHz = -1.0,
                    confidence = 0.0,
                    isPitched = false,
                    timeSeconds = timeSeconds
                ))
                frameStart += hopSize
                continue
            }

            // Parabolic interpolation for sub-bin accuracy
            val interpolatedBin = parabolicInterpolation(hps, peakIdx) + minBin
            val frequency = interpolatedBin * sampleRate.toDouble() / frameSize

            // Compute confidence: ratio of peak to average of neighbors
            val confidence = computeConfidence(hps, peakIdx)

            val isPitched = frequency in minFrequency..maxFrequency && confidence > 0.3

            rawResults.add(PitchDetectionResult(
                frequencyHz = if (isPitched) frequency else -1.0,
                confidence = if (isPitched) confidence else 0.0,
                isPitched = isPitched,
                timeSeconds = timeSeconds
            ))

            frameStart += hopSize
        }

        // Post-processing: octave jump correction
        return correctOctaveJumps(rawResults)
    }

    /**
     * Compute confidence as how prominent the peak is relative to neighbors.
     * Uses the ratio of the peak to the mean of nearby bins.
     */
    private fun computeConfidence(hps: DoubleArray, peakIdx: Int): Double {
        if (hps.isEmpty()) return 0.0

        val peakVal = hps[peakIdx]
        if (peakVal == Double.NEGATIVE_INFINITY) return 0.0

        // Compare peak to the median of surrounding bins (±20 bins)
        val neighborRadius = 20
        val start = maxOf(0, peakIdx - neighborRadius)
        val end = minOf(hps.size, peakIdx + neighborRadius + 1)
        val neighbors = mutableListOf<Double>()
        for (i in start until end) {
            if (i != peakIdx && hps[i] != Double.NEGATIVE_INFINITY) {
                neighbors.add(hps[i])
            }
        }

        if (neighbors.isEmpty()) return 0.5

        neighbors.sort()
        val medianNeighbor = neighbors[neighbors.size / 2]

        // Convert log difference to a confidence score
        val logDiff = peakVal - medianNeighbor
        // logDiff of ~5 = very confident, ~1 = marginal
        return (logDiff / 5.0).coerceIn(0.0, 1.0)
    }

    /**
     * Parabolic interpolation for sub-bin accuracy.
     */
    private fun parabolicInterpolation(data: DoubleArray, idx: Int): Double {
        if (idx <= 0 || idx >= data.size - 1) return idx.toDouble()

        val s0 = data[idx - 1]
        val s1 = data[idx]
        val s2 = data[idx + 1]

        if (s0 == Double.NEGATIVE_INFINITY || s2 == Double.NEGATIVE_INFINITY) return idx.toDouble()

        val denominator = 2.0 * (2.0 * s1 - s2 - s0)
        return if (denominator == 0.0) idx.toDouble()
        else idx + (s2 - s0) / denominator
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

    private fun computeRms(frame: DoubleArray): Double {
        var sum = 0.0
        for (sample in frame) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / frame.size)
    }
}
