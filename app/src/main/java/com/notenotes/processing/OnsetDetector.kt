package com.notenotes.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Onset detector using high-frequency energy ratio (HFR) gating,
 * energy growth ratio, and adaptive thresholding.
 *
 * Key improvements over naive energy-difference detection:
 * 1. HFR gate: genuine attacks produce more high-frequency energy than sustain/decay
 * 2. Energy growth ratio: requires multiplicative energy increase, not just additive
 * 3. Higher minimum onset interval: prevents retriggering on decaying guitar strings
 * 4. Adaptive threshold with wider window for better noise immunity
 */
class OnsetDetector(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 1024,
    private val hopSize: Int = 512,
    private val energyThresholdMultiplier: Double = 2.0,
    private val minOnsetIntervalMs: Double = 150.0,   // 150ms between onsets (guitar pluck spacing)
    private val silenceThreshold: Double = 0.005,      // normalized energy below this = silence
    private val energyGrowthRatio: Double = 2.0,       // energy must double to be onset
    private val hfrThreshold: Double = 0.3             // HFR must exceed this for attack
) {

    /**
     * Per-frame analysis data for onset detection.
     */
    private data class FrameAnalysis(
        val energy: Double,
        val hfr: Double,           // high-frequency energy ratio (0-1)
        val spectralFlux: Double,
        val timeSeconds: Double
    )

    /**
     * Detect onset times in the given audio samples.
     * @param samples 16-bit PCM audio samples (mono)
     * @return List of onset times in seconds
     */
    fun detectOnsets(samples: ShortArray): List<Double> {
        if (samples.size < frameSize) return emptyList()

        // Convert to normalized float
        val floatSamples = FloatArray(samples.size) { samples[it].toFloat() / Short.MAX_VALUE }

        // Compute per-frame analysis
        val frames = analyzeFrames(floatSamples)

        if (frames.size < 2) return emptyList()

        // Pick onsets using HFR-gated energy growth
        return pickOnsets(frames)
    }

    /**
     * Analyze all frames: compute energy, HFR, and spectral flux for each.
     */
    private fun analyzeFrames(samples: FloatArray): List<FrameAnalysis> {
        val numFrames = (samples.size - frameSize) / hopSize + 1
        if (numFrames < 1) return emptyList()

        val frames = mutableListOf<FrameAnalysis>()
        val numBands = 8
        val bandSize = frameSize / (numBands * 2)
        var prevBandEnergies = DoubleArray(numBands)

        for (i in 0 until numFrames) {
            val start = i * hopSize
            val timeSeconds = start.toDouble() / sampleRate

            // Total frame energy
            var totalEnergy = 0.0
            for (j in 0 until frameSize) {
                if (start + j < samples.size) {
                    val s = samples[start + j].toDouble()
                    totalEnergy += s * s
                }
            }
            totalEnergy /= frameSize

            // High-frequency energy ratio (HFR):
            // Compute energy in top half of frame vs total
            // Attacks have transient broadband energy → higher HFR
            // Sustain/decay is dominated by lower harmonics → lower HFR
            val halfPoint = frameSize / 2
            var lowEnergy = 0.0
            var highEnergy = 0.0
            for (j in 0 until frameSize) {
                val idx = start + j
                if (idx < samples.size) {
                    val s = samples[idx].toDouble()
                    // Use zero-crossing rate as HF proxy in time domain
                    if (j < halfPoint) lowEnergy += s * s
                    else highEnergy += s * s
                }
            }
            // Compute zero-crossing rate as a better HF indicator
            var zeroCrossings = 0
            for (j in 1 until frameSize) {
                val idx = start + j
                val prevIdx = start + j - 1
                if (idx < samples.size && prevIdx < samples.size) {
                    if (samples[idx] * samples[prevIdx] < 0) zeroCrossings++
                }
            }
            val maxZcr = frameSize / 2.0
            val hfr = (zeroCrossings.toDouble() / maxZcr).coerceIn(0.0, 1.0)

            // Band energies for spectral flux
            val bandEnergies = DoubleArray(numBands)
            for (b in 0 until numBands) {
                var bEnergy = 0.0
                val bandStart = b * bandSize
                for (j in 0 until bandSize) {
                    val idx = start + bandStart + j
                    if (idx < samples.size) {
                        val s = samples[idx].toDouble()
                        bEnergy += s * s
                    }
                }
                bandEnergies[b] = bEnergy / bandSize
            }

            // Spectral flux: sum of positive band energy differences
            var flux = 0.0
            if (i > 0) {
                for (b in 0 until numBands) {
                    val diff = bandEnergies[b] - prevBandEnergies[b]
                    if (diff > 0) flux += diff
                }
            }

            prevBandEnergies = bandEnergies

            frames.add(FrameAnalysis(
                energy = totalEnergy,
                hfr = hfr,
                spectralFlux = flux,
                timeSeconds = timeSeconds
            ))
        }

        // Normalize spectral flux
        val maxFlux = frames.maxOf { it.spectralFlux }
        if (maxFlux > 0) {
            return frames.map { it.copy(spectralFlux = it.spectralFlux / maxFlux) }
        }

        return frames
    }

    /**
     * Pick onset times from frame analysis using:
     * 1. Energy growth ratio gate: energy must increase by energyGrowthRatio
     * 2. HFR gate: frame must have high-frequency content above threshold (indicates attack)
     * 3. Adaptive threshold on combined ODF
     * 4. Minimum onset interval
     */
    private fun pickOnsets(frames: List<FrameAnalysis>): List<Double> {
        val onsets = mutableListOf<Double>()
        val minIntervalFrames = (minOnsetIntervalMs * sampleRate / (1000.0 * hopSize)).toInt().coerceAtLeast(1)

        // Build onset detection function using energy ratio + HFR + spectral flux
        val odf = DoubleArray(frames.size)
        for (i in 1 until frames.size) {
            val prevEnergy = frames[i - 1].energy
            val currEnergy = frames[i].energy

            // Energy growth ratio: must be multiplicative increase
            val ratio = if (prevEnergy > silenceThreshold * 0.01)
                currEnergy / prevEnergy else if (currEnergy > silenceThreshold) 10.0 else 0.0

            // HFR score: how much high-frequency content is in this frame
            val hfrScore = frames[i].hfr

            // Determine if this is a silence → sound transition
            val fromSilence = prevEnergy < silenceThreshold

            // Only count as onset candidate if:
            // - Energy is growing significantly AND
            // - Has attack character (HFR) OR is coming from silence
            val isGrowing = ratio > energyGrowthRatio
            val hasAttackCharacter = hfrScore > hfrThreshold || fromSilence

            if (isGrowing && hasAttackCharacter) {
                // Combined score: higher energy growth and HFR = stronger onset
                odf[i] = (ratio - 1.0) * maxOf(hfrScore, 0.3) + frames[i].spectralFlux
            }
        }

        // Compute adaptive threshold
        val windowSize = 15
        val adaptiveThresholds = DoubleArray(frames.size)
        for (i in frames.indices) {
            val start = max(0, i - windowSize)
            val end = minOf(frames.size, i + windowSize + 1)
            val window = (start until end).map { odf[it] }.sorted()
            val median = window[window.size / 2]
            adaptiveThresholds[i] = median * energyThresholdMultiplier + 0.1
        }

        // Global threshold
        val nonZeroValues = odf.filter { it > 0 }
        val globalThreshold = if (nonZeroValues.isNotEmpty())
            nonZeroValues.average() * 0.5 else 0.1

        var lastOnsetFrame = -minIntervalFrames

        for (i in 1 until frames.size - 1) {
            if (odf[i] <= 0) continue

            // Must be local maximum
            val isLocalMax = odf[i] >= odf[i - 1] && odf[i] >= odf[i + 1]
            if (!isLocalMax) continue

            // Must exceed adaptive and global threshold
            if (odf[i] < adaptiveThresholds[i] || odf[i] < globalThreshold) continue

            // Must respect minimum interval
            if (i - lastOnsetFrame < minIntervalFrames) continue

            onsets.add(frames[i].timeSeconds)
            lastOnsetFrame = i
        }

        // Ensure onset at beginning if there's energy in early frames
        // Check the first 10 frames (not just frame[0]) since recordings may start with brief silence
        if (frames.isNotEmpty()) {
            val earlyFrameLimit = minOf(10, frames.size)
            val firstActiveFrame = (0 until earlyFrameLimit).firstOrNull {
                frames[it].energy > silenceThreshold
            }

            if (firstActiveFrame != null) {
                if (onsets.isEmpty()) {
                    onsets.add(0, frames[firstActiveFrame].timeSeconds)
                } else if (onsets.first() > 0.15) {
                    // There's a significant gap before first onset but audio is playing
                    onsets.add(0, frames[firstActiveFrame].timeSeconds)
                }
            }
        }

        return onsets
    }
}
