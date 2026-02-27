package com.notenotes.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes PCM audio data to WAV file format.
 * WAV is RIFF format with PCM data.
 */
object WavWriter {

    /**
     * Write 16-bit mono PCM samples to a WAV file.
     */
    fun writeWav(samples: ShortArray, file: File, sampleRate: Int = 44100) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2  // 2 bytes per 16-bit sample
        val fileSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            buffer.put("RIFF".toByteArray())
            buffer.putInt(fileSize)
            buffer.put("WAVE".toByteArray())

            // fmt sub-chunk
            buffer.put("fmt ".toByteArray())
            buffer.putInt(16)  // sub-chunk size
            buffer.putShort(1)  // PCM format
            buffer.putShort(numChannels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign.toShort())
            buffer.putShort(bitsPerSample.toShort())

            // data sub-chunk header
            buffer.put("data".toByteArray())
            buffer.putInt(dataSize)

            fos.write(buffer.array())

            // Write PCM data in chunks to avoid allocating one huge buffer
            val chunkSize = 8192  // samples per chunk
            val chunkBuffer = ByteBuffer.allocate(chunkSize * 2).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 0
            while (offset < samples.size) {
                chunkBuffer.clear()
                val end = minOf(offset + chunkSize, samples.size)
                for (i in offset until end) {
                    chunkBuffer.putShort(samples[i])
                }
                fos.write(chunkBuffer.array(), 0, (end - offset) * 2)
                offset = end
            }
        }
    }

    /**
     * Read PCM samples from a WAV file.
     * Returns the samples as ShortArray and the sample rate.
     */
    fun readWav(file: File): Pair<ShortArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            // Skip RIFF header (12 bytes)
            raf.seek(12)

            var sampleRate = 44100
            var dataSize = 0
            var dataOffset = 0L

            // Parse chunks
            while (raf.filePointer < raf.length()) {
                val chunkId = ByteArray(4)
                raf.read(chunkId)
                val chunkSize = readInt32LE(raf)

                when (String(chunkId)) {
                    "fmt " -> {
                        raf.skipBytes(2) // audio format
                        raf.skipBytes(2) // num channels
                        sampleRate = readInt32LE(raf).toInt()
                        raf.skipBytes(chunkSize.toInt() - 8) // skip rest of fmt
                    }
                    "data" -> {
                        dataSize = chunkSize.toInt()
                        dataOffset = raf.filePointer
                        break
                    }
                    else -> {
                        raf.skipBytes(chunkSize.toInt())
                    }
                }
            }

            // Read sample data
            raf.seek(dataOffset)
            val numSamples = dataSize / 2
            val samples = ShortArray(numSamples)
            val data = ByteArray(dataSize)
            raf.read(data)

            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                samples[i] = buf.getShort()
            }

            return Pair(samples, sampleRate)
        }
    }

    private fun readInt32LE(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.read(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
    }
}
