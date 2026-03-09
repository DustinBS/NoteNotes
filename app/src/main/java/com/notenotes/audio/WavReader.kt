package com.notenotes.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared WAV file reading utilities.
 *
 * Extracts the duplicate RIFF/PCM parsing logic that was in
 * PreviewViewModel.readWavSamples() and PreviewViewModel.loadWaveformData().
 */
object WavReader {

    /**
     * Result of reading a WAV file.
     */
    data class WavData(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    ) {
        /** Number of PCM frames (samples / channels). */
        val frameCount: Int get() = if (channels > 0) samples.size / channels else samples.size

        /**
         * Duration of the audio in milliseconds.
         *
         * Uses [Math.round] on double arithmetic to avoid off-by-1ms errors
         * from integer division truncation.  This matters for NNT file
         * duration matching — without rounding, a file recorded at 44100 Hz
         * containing exactly 4410 frames would compute as 99ms (truncated)
         * instead of the correct 100ms.
         */
        val durationMs: Int get() = if (sampleRate > 0) Math.round(frameCount * 1000.0 / sampleRate).toInt() else 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WavData) return false
            return samples.contentEquals(other.samples) &&
                    sampleRate == other.sampleRate &&
                    channels == other.channels &&
                    bitsPerSample == other.bitsPerSample
        }

        override fun hashCode(): Int {
            var result = samples.contentHashCode()
            result = 31 * result + sampleRate
            result = 31 * result + channels
            result = 31 * result + bitsPerSample
            return result
        }
    }

    /**
     * Read PCM samples from a WAV file using chunk-scanning (handles non-standard headers).
     * Returns null if the file is not a valid WAV or an error occurs.
     */
    fun readSamples(file: File): ShortArray? {
        try {
            val raf = RandomAccessFile(file, "r")
            raf.seek(12) // skip RIFF header (4 RIFF + 4 size + 4 WAVE)
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val chunkSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt()

                if (String(chunkId) == "data") {
                    val dataBytes = ByteArray(chunkSize)
                    raf.readFully(dataBytes)
                    raf.close()
                    val bb = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                    return ShortArray(chunkSize / 2) { bb.getShort() }
                } else {
                    raf.seek(raf.filePointer + chunkSize.toLong())
                }
            }
            raf.close()
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Read a WAV file with full header parsing (sample rate, channels, bits).
     * Returns null if not a valid RIFF/WAV file.
     */
    fun readFull(file: File): WavData? {
        if (!file.exists()) return null
        try {
            val raf = RandomAccessFile(file, "r")
            val header = ByteArray(44)
            raf.readFully(header)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            bb.position(0)
            val riff = ByteArray(4); bb.get(riff)
            if (String(riff) != "RIFF") {
                raf.close()
                return null
            }
            bb.getInt() // fileSize
            val wave = ByteArray(4); bb.get(wave)
            if (String(wave) != "WAVE") {
                raf.close()
                return null
            }

            // fmt chunk
            bb.position(16)
            val fmtSize = bb.getInt()
            val audioFormat = bb.getShort().toInt() // 1 = PCM
            val channels = bb.getShort().toInt()
            val sampleRate = bb.getInt()
            bb.getInt() // byteRate
            bb.getShort() // blockAlign
            val bitsPerSample = bb.getShort().toInt()

            // Seek past fmt chunk to find data chunk
            raf.seek(20 + fmtSize.toLong())
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val chunkSizeBytes = ByteArray(4)
                raf.readFully(chunkSizeBytes)
                val chunkSize = ByteBuffer.wrap(chunkSizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt()

                if (String(chunkId) == "data") {
                    val numSamples = chunkSize / 2
                    val dataBytes = ByteArray(chunkSize)
                    raf.readFully(dataBytes)
                    raf.close()
                    val dataBB = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val samples = ShortArray(numSamples) { dataBB.getShort() }
                    return WavData(samples, sampleRate, channels, bitsPerSample)
                } else {
                    raf.seek(raf.filePointer + chunkSize.toLong())
                }
            }
            raf.close()
        } catch (_: Exception) {
        }
        return null
    }
}
