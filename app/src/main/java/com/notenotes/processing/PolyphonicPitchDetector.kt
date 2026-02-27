package com.notenotes.processing

import android.util.Log
import kotlin.math.*

private const val TAG = "NNPolyphonic"

/**
 * Polyphonic pitch detector using FFT + Iterative Harmonic Summation (Klapuri-style).
 *
 * Algorithm:
 * 1. Apply Hann window to audio frame, zero-pad to FFT size
 * 2. Compute FFT and magnitude spectrum
 * 3. Estimate noise floor (30th percentile)
 * 4. Iteratively:
 *    a. Harmonic summation → find strongest F0 candidate
 *    b. Verify against original spectrum (need ≥2 harmonics)
 *    c. Sub-harmonic artifact check
 *    d. Subtract harmonics from working spectrum
 *    e. Repeat up to maxPolyphony times
 * 5. Post-process: deduplicate, snap to MIDI, range-constrain
 */
class PolyphonicPitchDetector(
    private val sampleRate: Int = 44100,
    private val fftSize: Int = 8192,
    private val maxPolyphony: Int = 6,
    private val numHarmonics: Int = 6,
    private val minF0Hz: Double = 75.0,      // just below E2 (82.4 Hz), supports drop D
    private val maxF0Hz: Double = 1320.0,    // high E fret 12 area
    private val f0StepHz: Double = 1.0,      // resolution of F0 search
    private val toleranceBins: Int = 2,       // ± bins for harmonic search
    private val harmonicDecay: Double = 0.84, // weight decay per harmonic: w[h] = 0.84^(h-1)
    private val minGuitarMidi: Int = 40,     // E2
    private val maxGuitarMidi: Int = 88,     // E6
    private val minHarmonicsRequired: Int = 2,
    private val subtractionSigma: Double = 1.5 // Gaussian sigma for spectral subtraction
) {

    /** Pre-computed Hann window (reusable across frames) */
    private var cachedWindow: DoubleArray? = null
    private var cachedWindowSize: Int = 0

    /** Pre-computed harmonic weights: w[h] = harmonicDecay^h for h=0..numHarmonics-1 */
    private val harmonicWeights = DoubleArray(numHarmonics) { h -> harmonicDecay.pow(h.toDouble()) }

    /**
     * Result from polyphonic pitch detection on a single frame.
     */
    data class DetectedPitch(
        val frequencyHz: Double,
        val midiNote: Int,
        val confidence: Double,     // 0.0-1.0, relative to strongest F0
        val harmonicCount: Int      // how many harmonics were found above noise
    )

    /**
     * Result combining detected pitches with optional chord identification.
     */
    data class PolyphonicResult(
        val pitches: List<DetectedPitch>,
        val isChord: Boolean,
        val timeSeconds: Double = 0.0
    )

    /**
     * Detect pitches in an audio frame.
     *
     * @param samples Audio samples (mono, 16-bit converted to short or float).
     *                Can be any length; will be zero-padded to fftSize.
     * @param timeSeconds Time position of this frame for logging.
     * @return PolyphonicResult with 0-maxPolyphony detected pitches.
     */
    fun detectPitches(samples: ShortArray, timeSeconds: Double = 0.0): PolyphonicResult {
        // Convert ShortArray to doubles, normalized to [-1, 1]
        val doubleSamples = DoubleArray(samples.size) { samples[it].toDouble() / 32768.0 }
        return detectPitchesDouble(doubleSamples, timeSeconds)
    }

    /**
     * Detect pitches from double-precision samples (already normalized to [-1, 1]).
     */
    fun detectPitchesDouble(samples: DoubleArray, timeSeconds: Double = 0.0): PolyphonicResult {
        if (samples.isEmpty()) return PolyphonicResult(emptyList(), false, timeSeconds)

        // Check for silence (RMS below threshold)
        val rms = sqrt(samples.sumOf { it * it } / samples.size)
        if (rms < 0.005) {
            return PolyphonicResult(emptyList(), false, timeSeconds)
        }

        // Step 1: Apply Hann window
        val windowSize = minOf(samples.size, fftSize)
        val window = getHannWindow(windowSize)

        // Step 2: Prepare FFT input with windowing + zero-padding
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        for (i in 0 until windowSize) {
            real[i] = samples[i] * window[i]
        }
        // Remaining elements are already zero (zero-padding)

        // Step 3: FFT
        FFT.fft(real, imag)

        // Step 4: Magnitude spectrum (first half only)
        val originalMagnitude = FFT.magnitudeSpectrum(real, imag)
        val workingMagnitude = originalMagnitude.copyOf()

        // Step 5: Estimate noise floor (30th percentile of positive magnitudes)
        val noiseFloor = estimateNoiseFloor(originalMagnitude)
        val dynamicThreshold = noiseFloor * 3.0

        // Step 6: Iterative F0 detection
        val detectedPitches = mutableListOf<DetectedPitch>()
        var bestOverallScore = 0.0

        for (iteration in 0 until maxPolyphony) {
            // Find strongest F0 in working spectrum via harmonic summation
            val result = findStrongestF0(workingMagnitude, dynamicThreshold)
                ?: break // No more candidates above threshold

            if (iteration == 0) bestOverallScore = result.score

            // Early termination: if this iteration's score is very weak relative to first
            if (result.score < bestOverallScore * 0.15) break

            // Verify against original spectrum
            val verification = verifyF0(originalMagnitude, result.f0Hz, dynamicThreshold)
            if (verification.harmonicCount < minHarmonicsRequired) {
                // Not enough evidence — subtract and try next
                subtractHarmonics(workingMagnitude, result.f0Hz)
                continue
            }

            // Sub-harmonic artifact check
            if (isSubHarmonicArtifact(result.f0Hz, detectedPitches, originalMagnitude)) {
                subtractHarmonics(workingMagnitude, result.f0Hz)
                continue
            }

            // Harmonic artifact check: reject if this F0 is a harmonic of an existing pitch
            val isHarmonicOfExisting = detectedPitches.any { existing ->
                val ratio = result.f0Hz / existing.frequencyHz
                // Check if ratio is close to 2, 3, 4, 5 (i.e., this is a harmonic overtone)
                (2..5).any { h -> kotlin.math.abs(ratio - h.toDouble()) < 0.08 }
            }
            if (isHarmonicOfExisting) {
                Log.d(TAG, "Harmonic artifact: ${String.format("%.1f", result.f0Hz)} Hz is harmonic of existing pitch")
                subtractHarmonics(workingMagnitude, result.f0Hz)
                continue
            }

            // Range check
            val midiNote = frequencyToMidi(result.f0Hz)
            if (midiNote in minGuitarMidi..maxGuitarMidi) {
                val confidence = if (bestOverallScore > 0) result.score / bestOverallScore else 0.0
                detectedPitches.add(DetectedPitch(
                    frequencyHz = result.f0Hz,
                    midiNote = midiNote,
                    confidence = confidence.coerceIn(0.0, 1.0),
                    harmonicCount = verification.harmonicCount
                ))
            }

            // Subtract this F0's harmonics from working spectrum
            subtractHarmonics(workingMagnitude, result.f0Hz)
        }

        // Deduplicate: remove duplicate MIDI notes (can happen due to F0 search resolution)
        val unique = deduplicatePitches(detectedPitches)

        Log.d(TAG, "detectPitches at ${String.format("%.3f", timeSeconds)}s: " +
                "${unique.size} pitches: ${unique.map { "${it.midiNote}(${String.format("%.1f", it.frequencyHz)}Hz)" }}")

        return PolyphonicResult(
            pitches = unique.sortedBy { it.frequencyHz },
            isChord = unique.size >= 2,
            timeSeconds = timeSeconds
        )
    }

    // ---- Internal Data Structures ----

    private data class F0Result(val f0Hz: Double, val score: Double, val harmonicCount: Int)
    private data class VerificationResult(val harmonicCount: Int, val score: Double)

    // ---- Harmonic Summation ----

    /**
     * Scan all candidate F0 values and return the one with the highest harmonic summation score.
     */
    private fun findStrongestF0(magnitude: DoubleArray, dynamicThreshold: Double): F0Result? {
        val halfN = fftSize / 2
        var bestF0 = 0.0
        var bestScore = 0.0
        var bestHarmonicCount = 0

        // Coarse search: step through F0 candidates
        var f0 = minF0Hz
        while (f0 <= maxF0Hz) {
            var score = 0.0
            var harmonicCount = 0

            for (h in 1..numHarmonics) {
                val fh = f0 * h
                val bin = (fh * fftSize / sampleRate).roundToInt()
                if (bin >= halfN - toleranceBins) break

                // Find max magnitude within ± toleranceBins
                val lo = (bin - toleranceBins).coerceAtLeast(0)
                val hi = (bin + toleranceBins).coerceAtMost(halfN - 1)
                var maxMag = 0.0
                for (b in lo..hi) {
                    if (magnitude[b] > maxMag) maxMag = magnitude[b]
                }

                score += harmonicWeights[h - 1] * maxMag
                if (maxMag > dynamicThreshold) harmonicCount++
            }

            if (score > bestScore && harmonicCount >= minHarmonicsRequired) {
                bestScore = score
                bestF0 = f0
                bestHarmonicCount = harmonicCount
            }

            f0 += f0StepHz
        }

        if (bestScore <= 0.0) return null

        // Fine search: refine around best F0 with 0.1 Hz steps
        val refineStart = (bestF0 - f0StepHz).coerceAtLeast(minF0Hz)
        val refineEnd = (bestF0 + f0StepHz).coerceAtMost(maxF0Hz)
        f0 = refineStart
        while (f0 <= refineEnd) {
            var score = 0.0
            var harmonicCount = 0

            for (h in 1..numHarmonics) {
                val fh = f0 * h
                val bin = (fh * fftSize / sampleRate).roundToInt()
                if (bin >= halfN - toleranceBins) break

                val lo = (bin - toleranceBins).coerceAtLeast(0)
                val hi = (bin + toleranceBins).coerceAtMost(halfN - 1)
                var maxMag = 0.0
                for (b in lo..hi) {
                    if (magnitude[b] > maxMag) maxMag = magnitude[b]
                }

                score += harmonicWeights[h - 1] * maxMag
                if (maxMag > dynamicThreshold) harmonicCount++
            }

            if (score > bestScore) {
                bestScore = score
                bestF0 = f0
                bestHarmonicCount = harmonicCount
            }

            f0 += 0.1
        }

        return F0Result(bestF0, bestScore, bestHarmonicCount)
    }

    /**
     * Verify a candidate F0 against the original (unsubtracted) spectrum.
     */
    private fun verifyF0(
        originalMagnitude: DoubleArray,
        f0Hz: Double,
        dynamicThreshold: Double
    ): VerificationResult {
        val halfN = fftSize / 2
        var score = 0.0
        var harmonicCount = 0

        for (h in 1..numHarmonics) {
            val fh = f0Hz * h
            val bin = (fh * fftSize / sampleRate).roundToInt()
            if (bin >= halfN - toleranceBins) break

            val lo = (bin - toleranceBins).coerceAtLeast(0)
            val hi = (bin + toleranceBins).coerceAtMost(halfN - 1)
            var maxMag = 0.0
            for (b in lo..hi) {
                if (originalMagnitude[b] > maxMag) maxMag = originalMagnitude[b]
            }

            score += harmonicWeights[h - 1] * maxMag
            if (maxMag > dynamicThreshold) harmonicCount++
        }

        return VerificationResult(harmonicCount, score)
    }

    // ---- Sub-harmonic Artifact Detection ----

    /**
     * Check if the candidate F0 is a sub-harmonic artifact of an already-detected pitch.
     * For example, if A2 (110 Hz) is detected, 55 Hz might score high because
     * its harmonics (110, 165, 220...) overlap with A2's harmonics.
     */
    private fun isSubHarmonicArtifact(
        f0Hz: Double,
        alreadyFound: List<DetectedPitch>,
        originalMagnitude: DoubleArray
    ): Boolean {
        for (existing in alreadyFound) {
            val ratio = existing.frequencyHz / f0Hz
            // If f0 is approximately half or a third of an existing note
            if (abs(ratio - 2.0) < 0.08 || abs(ratio - 3.0) < 0.08) {
                // Check if f0 has its own unique harmonics (odd ones that don't align with existing)
                // For sub-harmonic at f0 = existing/2, the odd harmonics (f0, 3*f0, 5*f0) are unique
                val halfN = fftSize / 2
                var uniqueHarmonicEnergy = 0.0
                var sharedHarmonicEnergy = 0.0

                for (h in 1..numHarmonics) {
                    val fh = f0Hz * h
                    val bin = (fh * fftSize / sampleRate).roundToInt()
                    if (bin >= halfN - toleranceBins) break

                    val lo = (bin - toleranceBins).coerceAtLeast(0)
                    val hi = (bin + toleranceBins).coerceAtMost(halfN - 1)
                    var maxMag = 0.0
                    for (b in lo..hi) {
                        if (originalMagnitude[b] > maxMag) maxMag = originalMagnitude[b]
                    }

                    // Check if this harmonic coincides with existing note's harmonic series
                    val isShared = isNearHarmonic(fh, existing.frequencyHz, numHarmonics)
                    if (isShared) {
                        sharedHarmonicEnergy += maxMag
                    } else {
                        uniqueHarmonicEnergy += maxMag
                    }
                }

                // If unique harmonics are much weaker than shared ones, it's likely an artifact
                if (uniqueHarmonicEnergy < sharedHarmonicEnergy * 0.3) {
                    Log.d(TAG, "Sub-harmonic artifact: ${String.format("%.1f", f0Hz)} Hz " +
                            "is sub-harmonic of ${String.format("%.1f", existing.frequencyHz)} Hz")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Check if frequency fh is near any harmonic of fundamentalHz.
     */
    private fun isNearHarmonic(fh: Double, fundamentalHz: Double, maxHarmonic: Int): Boolean {
        for (h in 1..maxHarmonic) {
            val harmFreq = fundamentalHz * h
            if (abs(fh - harmFreq) / harmFreq < 0.03) { // within 3%
                return true
            }
        }
        return false
    }

    // ---- Spectral Subtraction ----

    /**
     * Subtract a detected F0's harmonics from the working magnitude spectrum
     * using Gaussian-shaped removal to avoid leaving artifacts.
     */
    private fun subtractHarmonics(
        magnitude: DoubleArray,
        f0Hz: Double,
        numSubHarmonics: Int = 10
    ) {
        val halfN = magnitude.size
        val radius = (3 * subtractionSigma).toInt()

        for (h in 1..numSubHarmonics) {
            val fh = f0Hz * h
            val bin = (fh * fftSize / sampleRate).roundToInt()
            if (bin >= halfN) break

            for (delta in -radius..radius) {
                val b = bin + delta
                if (b in 0 until halfN) {
                    val weight = exp(-0.5 * (delta.toDouble() / subtractionSigma).pow(2))
                    magnitude[b] *= (1.0 - weight).coerceAtLeast(0.0)
                }
            }
        }
    }

    // ---- Noise Floor ----

    /**
     * Estimate the noise floor as the 30th percentile of positive magnitudes.
     */
    private fun estimateNoiseFloor(magnitude: DoubleArray): Double {
        val positive = magnitude.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return 0.0
        val idx = (positive.size * 0.3).toInt().coerceIn(0, positive.size - 1)
        return positive[idx]
    }

    // ---- Deduplication ----

    /**
     * Remove duplicate detected pitches with the same MIDI note.
     * Keeps the one with highest confidence.
     */
    private fun deduplicatePitches(pitches: List<DetectedPitch>): List<DetectedPitch> {
        return pitches.groupBy { it.midiNote }
            .map { (_, group) -> group.maxByOrNull { it.confidence }!! }
    }

    // ---- Utility ----

    private fun frequencyToMidi(freq: Double): Int {
        if (freq <= 0) return -1
        return (69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)).roundToInt()
    }

    private fun getHannWindow(size: Int): DoubleArray {
        if (size == cachedWindowSize && cachedWindow != null) return cachedWindow!!
        val window = FFT.hannWindow(size)
        cachedWindow = window
        cachedWindowSize = size
        return window
    }
}
