package com.notenotes.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any Android-supported audio format (MP3, M4A/AAC, OGG, FLAC, WAV)
 * to raw PCM 16-bit samples using MediaExtractor + MediaCodec.
 *
 * This is the fallback for non-WAV files where WavReader returns null.
 */
object AudioDecoder {

    private const val TAG = "NNAudioDecoder"
    private const val TIMEOUT_US = 10_000L

    /**
     * Decode an audio file to PCM samples + metadata.
     * Returns null on failure or if the file has no audio track.
     */
    fun decode(file: File): WavReader.WavData? {
        if (!file.exists()) return null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            // Find the first audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                Log.w(TAG, "No audio track found in ${file.name}")
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                extractor.release()
                return null
            }
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.d(TAG, "Decoding ${file.name}: $mime, ${sampleRate}Hz, ${channels}ch")

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmChunks = mutableListOf<ShortArray>()
            var totalSamples = 0
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val bytesRead = extractor.readSampleData(inputBuffer, 0)
                        if (bytesRead < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, bytesRead, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numShorts = shortBuf.remaining()
                        val chunk = ShortArray(numShorts)
                        shortBuf.get(chunk)
                        pcmChunks.add(chunk)
                        totalSamples += numShorts
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            if (totalSamples == 0) {
                Log.w(TAG, "Decoded 0 samples from ${file.name}")
                return null
            }

            // Merge all chunks
            val samples = ShortArray(totalSamples)
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, samples, offset, chunk.size)
                offset += chunk.size
            }

            Log.d(TAG, "Decoded ${file.name}: $totalSamples samples, ${sampleRate}Hz, ${channels}ch")
            return WavReader.WavData(samples, sampleRate, channels, 16)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding ${file.name}", e)
            extractor.release()
            return null
        }
    }

    /**
     * Decode an audio file and return just the raw PCM samples.
     * Returns null on failure.
     */
    fun decodeSamples(file: File): ShortArray? {
        return decode(file)?.samples
    }
}
