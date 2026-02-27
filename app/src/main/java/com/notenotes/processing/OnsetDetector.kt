package com.notenotes.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Onset detector using energy-based detection and spectral flux.
 * 
 * Detects note attack points (onsets) in audio by analyzing:
 * 1. Energy changes between consecutive frames
 * 2. Spectral flux (increase in frequency content)
 * 
 * These well-documented DSP algorithms require no library dependency.
 */
class OnsetDetector(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 1024,
    private val hopSize: Int = 512,
    private val energyThresholdMultiplier: Double = 1.5,
    private val minOnsetIntervalMs: Double = 50.0,  // minimum ms between onsets
    private val silenceThreshold: Double = 0.01      // normalized energy below this = silence
) {

    /**
     * Detect onset times in the given audio samples.
     * @param samples 16-bit PCM audio samples (mono)
     * @return List of onset times in seconds
     */
    fun detectOnsets(samples: ShortArray): List<Double> {
        if (samples.size < frameSize) return emptyList()

        // Convert to normalized float
        val floatSamples = FloatArray(samples.size) { samples[it].toFloat() / Short.MAX_VALUE }

        // Compute onset detection function (combination of energy and spectral flux)
        val odf = computeOnsetDetectionFunction(floatSamples)

        if (odf.isEmpty()) return emptyList()

        // Peak picking with adaptive threshold
        return pickPeaks(odf)
    }

    /**
     * Compute the combined onset detection function.
     * Uses energy difference + spectral flux, normalized and combined.
     */
    private fun computeOnsetDetectionFunction(samples: FloatArray): List<Double> {
        val numFrames = (samples.size - frameSize) / hopSize + 1
        if (numFrames < 2) return emptyList()

        // Compute energy per frame
        val energies = DoubleArray(numFrames)
        for (i in 0 until numFrames) {
            val start = i * hopSize
            var energy = 0.0
            for (j in 0 until frameSize) {
                if (start + j < samples.size) {
                    val s = samples[start + j].toDouble()
                    energy += s * s
                }
            }
            energies[i] = energy / frameSize
        }

        // Compute spectral flux per frame
        val spectralFlux = computeSpectralFlux(samples, numFrames)

        // Combine: normalized energy difference + spectral flux
        val odf = mutableListOf<Double>()
        odf.add(0.0) // first frame has no predecessor
        for (i in 1 until numFrames) {
            val energyDiff = max(0.0, energies[i] - energies[i - 1])
            val flux = if (i < spectralFlux.size) spectralFlux[i] else 0.0

            // Normalize energy diff by max energy to make it comparable
            val maxEnergy = energies.max()
            val normEnergyDiff = if (maxEnergy > 0) energyDiff / maxEnergy else 0.0

            // Combined onset function
            odf.add(normEnergyDiff + flux)
        }

        return odf
    }

    /**
     * Compute spectral flux using a simple DFT-like approach.
     * We use a simplified spectral difference based on windowed energy bands.
     */
    private fun computeSpectralFlux(samples: FloatArray, numFrames: Int): DoubleArray {
        val numBands = 8  // divide spectrum into bands
        val bandSize = frameSize / (numBands * 2)
        val flux = DoubleArray(numFrames)

        var prevBandEnergies = DoubleArray(numBands)

        for (i in 0 until numFrames) {
            val start = i * hopSize
            val bandEnergies = DoubleArray(numBands)

            // Simple band energy using time-domain subframes (approximate spectral bands)
            for (b in 0 until numBands) {
                var energy = 0.0
                val bandStart = b * bandSize
                for (j in 0 until bandSize) {
                    val idx = start + bandStart + j
                    if (idx < samples.size) {
                        val s = samples[idx].toDouble()
                        energy += s * s
                    }
                }
                bandEnergies[b] = energy / bandSize
            }

            // Spectral flux: sum of positive differences in band energies
            if (i > 0) {
                var sf = 0.0
                for (b in 0 until numBands) {
                    val diff = bandEnergies[b] - prevBandEnergies[b]
                    if (diff > 0) sf += diff
                }
                flux[i] = sf
            }

            prevBandEnergies = bandEnergies
        }

        // Normalize flux
        val maxFlux = flux.max()
        if (maxFlux > 0) {
            for (i in flux.indices) {
                flux[i] /= maxFlux
            }
        }

        return flux
    }

    /**
     * Pick peaks from the onset detection function using adaptive thresholding.
     * Returns onset times in seconds.
     */
    private fun pickPeaks(odf: List<Double>): List<Double> {
        if (odf.isEmpty()) return emptyList()

        val onsets = mutableListOf<Double>()
        val minOnsetIntervalFrames = (minOnsetIntervalMs * sampleRate / (1000.0 * hopSize)).toInt().coerceAtLeast(1)

        // Compute adaptive threshold: median of local window * multiplier
        val windowSize = 10  // frames for local median
        val medianThresholds = DoubleArray(odf.size)
        for (i in odf.indices) {
            val windowStart = max(0, i - windowSize)
            val windowEnd = minOf(odf.size, i + windowSize + 1)
            val window = odf.subList(windowStart, windowEnd).sorted()
            medianThresholds[i] = window[window.size / 2] * energyThresholdMultiplier
        }

        // Global minimum threshold to avoid detecting noise
        val globalMean = odf.average()
        val globalThreshold = globalMean * 0.5

        var lastOnsetFrame = -minOnsetIntervalFrames

        for (i in 1 until odf.size - 1) {
            // Must be a local maximum
            if (odf[i] > odf[i - 1] && odf[i] >= odf[i + 1]) {
                // Must exceed adaptive threshold
                if (odf[i] > medianThresholds[i] && odf[i] > globalThreshold) {
                    // Must respect minimum interval
                    if (i - lastOnsetFrame >= minOnsetIntervalFrames) {
                        val timeSeconds = i.toDouble() * hopSize / sampleRate
                        onsets.add(timeSeconds)
                        lastOnsetFrame = i
                    }
                }
            }
        }

        // Always include onset at t=0 if there's energy
        if (onsets.isEmpty() && odf.any { it > silenceThreshold }) {
            // Check if the first few frames have significant energy
            val firstSignificant = odf.indexOfFirst { it > globalThreshold }
            if (firstSignificant >= 0) {
                onsets.add(0, firstSignificant.toDouble() * hopSize / sampleRate)
            }
        } else if (onsets.isNotEmpty() && onsets.first() > 0.1) {
            // If first onset is not near zero but there's audio from start, add onset at ~0
            val firstEnergetic = odf.indexOfFirst { it > silenceThreshold }
            if (firstEnergetic >= 0) {
                val t = firstEnergetic.toDouble() * hopSize / sampleRate
                if (t < onsets.first() - minOnsetIntervalMs / 1000.0) {
                    onsets.add(0, t)
                }
            }
        }

        return onsets
    }
}
