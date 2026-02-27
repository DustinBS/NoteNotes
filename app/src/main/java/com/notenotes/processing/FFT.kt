package com.notenotes.processing

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radix-2 Cooley-Tukey FFT implementation in pure Kotlin.
 * No external dependencies needed. Handles power-of-2 sizes up to 16384.
 */
object FFT {

    /**
     * In-place radix-2 Cooley-Tukey FFT.
     * @param real Real part of input/output. Length must be a power of 2.
     * @param imag Imaginary part of input/output. Same length as real.
     */
    fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n == imag.size) { "real and imag must have same length" }
        require(n > 0 && (n and (n - 1)) == 0) { "Length must be a power of 2, got $n" }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                // Swap real[i] <-> real[j]
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
        }

        // Butterfly operations
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wReal = cos(angle)
            val wImag = sin(angle)
            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until len / 2) {
                    val halfIdx = i + k + len / 2
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[halfIdx] * curReal - imag[halfIdx] * curImag
                    val vI = real[halfIdx] * curImag + imag[halfIdx] * curReal
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[halfIdx] = uR - vR
                    imag[halfIdx] = uI - vI
                    val newCurReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = newCurReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Compute the magnitude spectrum from complex FFT output.
     * Returns only the first half (positive frequencies), length = N/2.
     */
    fun magnitudeSpectrum(real: DoubleArray, imag: DoubleArray): DoubleArray {
        val halfN = real.size / 2
        return DoubleArray(halfN) { k ->
            sqrt(real[k] * real[k] + imag[k] * imag[k])
        }
    }

    /**
     * Compute power spectrum (magnitude squared) from complex FFT output.
     * Returns only the first half (positive frequencies), length = N/2.
     * Avoids the sqrt, faster for relative comparisons.
     */
    fun powerSpectrum(real: DoubleArray, imag: DoubleArray): DoubleArray {
        val halfN = real.size / 2
        return DoubleArray(halfN) { k ->
            real[k] * real[k] + imag[k] * imag[k]
        }
    }

    /**
     * Generate a Hann (Hanning) window of the specified size.
     * w[n] = 0.5 * (1 - cos(2*PI*n / (N-1)))
     */
    fun hannWindow(size: Int): DoubleArray {
        if (size <= 1) return DoubleArray(size) { 1.0 }
        val factor = 2.0 * Math.PI / (size - 1)
        return DoubleArray(size) { i ->
            0.5 * (1.0 - cos(factor * i))
        }
    }

    /**
     * Convert a frequency in Hz to an FFT bin index.
     */
    fun frequencyToBin(frequencyHz: Double, sampleRate: Int, fftSize: Int): Double {
        return frequencyHz * fftSize / sampleRate
    }

    /**
     * Convert an FFT bin index to a frequency in Hz.
     */
    fun binToFrequency(bin: Double, sampleRate: Int, fftSize: Int): Double {
        return bin * sampleRate.toDouble() / fftSize
    }
}
