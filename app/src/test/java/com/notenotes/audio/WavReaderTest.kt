package com.notenotes.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for WavReader — the extracted WAV-parsing logic.
 *
 * Run with: gradlew testDebugUnitTest --tests "*WavReaderTest*"
 */
class WavReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ══════════════════════════════════════════════════════════════════════
    // readSamples — chunk-scanning approach
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun readSamples_validMono16bit_returnsSamples() {
        val file = createMinimalWav(sampleRate = 44100, channels = 1, samples = shortArrayOf(100, 200, -300, 400))
        val result = WavReader.readSamples(file)
        assertNotNull(result)
        assertEquals(4, result!!.size)
        assertEquals(100, result[0].toInt())
        assertEquals(200, result[1].toInt())
        assertEquals(-300, result[2].toInt())
        assertEquals(400, result[3].toInt())
    }

    @Test
    fun readSamples_singleSample_returnsSingleElement() {
        val file = createMinimalWav(sampleRate = 44100, channels = 1, samples = shortArrayOf(42))
        val result = WavReader.readSamples(file)
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(42, result[0].toInt())
    }

    @Test
    fun readSamples_nonExistentFile_returnsNull() {
        val file = File(tempFolder.root, "does_not_exist.wav")
        val result = WavReader.readSamples(file)
        assertNull(result)
    }

    @Test
    fun readSamples_notARiff_returnsNull() {
        val file = tempFolder.newFile("bad.wav")
        file.writeBytes("this is not a wav file".toByteArray())
        val result = WavReader.readSamples(file)
        assertNull(result)
    }

    // ══════════════════════════════════════════════════════════════════════
    // readFull — full header parsing
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun readFull_validFile_returnsWavDataWithMetadata() {
        val samples = shortArrayOf(1000, 2000, 3000, 4000, 5000)
        val file = createMinimalWav(sampleRate = 22050, channels = 1, samples = samples)
        val result = WavReader.readFull(file)

        assertNotNull(result)
        assertEquals(22050, result!!.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(16, result.bitsPerSample)
        assertEquals(5, result.samples.size)
        assertArrayEquals(samples, result.samples)
    }

    @Test
    fun readFull_nonExistentFile_returnsNull() {
        val file = File(tempFolder.root, "nope.wav")
        assertNull(WavReader.readFull(file))
    }

    @Test
    fun readFull_invalidHeader_returnsNull() {
        val file = tempFolder.newFile("bad.wav")
        file.writeBytes(ByteArray(100)) // all zeros — not RIFF
        assertNull(WavReader.readFull(file))
    }

    @Test
    fun readFull_durationMs_isCorrect() {
        // 44100 samples at 44100Hz mono = 1000ms
        val samples = ShortArray(44100) { (it % 1000).toShort() }
        val file = createMinimalWav(sampleRate = 44100, channels = 1, samples = samples)
        val result = WavReader.readFull(file)
        assertNotNull(result)
        assertEquals(1000, result!!.durationMs)
    }

    @Test
    fun readFull_stereo_frameCountIsHalfSamples() {
        // 88200 samples at 44100Hz stereo = 44100 frames = 1000ms
        val samples = ShortArray(88200) { (it % 1000).toShort() }
        val file = createMinimalWav(sampleRate = 44100, channels = 2, samples = samples)
        val result = WavReader.readFull(file)
        assertNotNull(result)
        assertEquals(2, result!!.channels)
        assertEquals(44100, result.frameCount)
        assertEquals(1000, result.durationMs)
    }

    @Test
    fun readFull_skipsExtraChunks() {
        // Create WAV with an extra chunk before data chunk
        val samples = shortArrayOf(111, 222, 333)
        val file = createWavWithExtraChunk(sampleRate = 44100, channels = 1, samples = samples)
        val result = WavReader.readFull(file)
        assertNotNull(result)
        assertEquals(3, result!!.samples.size)
        assertEquals(111, result.samples[0].toInt())
    }

    // ══════════════════════════════════════════════════════════════════════
    // WavData properties
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun wavData_durationMs_roundsCorrectly_regressionOffByOne() {
        // REGRESSION: Integer division (frameCount * 1000 / sampleRate) truncates.
        // 4410 frames at 44100 Hz = 100.0ms exactly, but integer truncation
        // would give 99ms.  Math.round on double arithmetic gives 100.
        val data = WavReader.WavData(ShortArray(4410), 44100, 1, 16)
        assertEquals("4410 frames at 44100 Hz should be exactly 100ms", 100, data.durationMs)
    }

    @Test
    fun wavData_durationMs_roundsUpCorrectly() {
        // 4411 frames at 44100 Hz = 100.0227ms → Math.round → 100
        val data = WavReader.WavData(ShortArray(4411), 44100, 1, 16)
        assertEquals(100, data.durationMs)
    }

    @Test
    fun wavData_durationMs_roundsHalfUp() {
        // 22050 frames at 44100 Hz = exactly 500ms (no rounding issue)
        val data = WavReader.WavData(ShortArray(22050), 44100, 1, 16)
        assertEquals(500, data.durationMs)
    }

    @Test
    fun wavData_durationMs_zeroSampleRate_returnsZero() {
        val data = WavReader.WavData(ShortArray(100), 0, 1, 16)
        assertEquals(0, data.durationMs)
    }

    @Test
    fun wavData_equality_contentBased() {
        val a = WavReader.WavData(shortArrayOf(1, 2, 3), 44100, 1, 16)
        val b = WavReader.WavData(shortArrayOf(1, 2, 3), 44100, 1, 16)
        assertEquals(a, b)
    }

    @Test
    fun wavData_equality_differentSamples() {
        val a = WavReader.WavData(shortArrayOf(1, 2, 3), 44100, 1, 16)
        val b = WavReader.WavData(shortArrayOf(1, 2, 4), 44100, 1, 16)
        assertNotEquals(a, b)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers — create valid WAV files for testing
    // ══════════════════════════════════════════════════════════════════════

    private fun createMinimalWav(sampleRate: Int, channels: Int, samples: ShortArray): File {
        val file = tempFolder.newFile("test_${System.nanoTime()}.wav")
        val dataSize = samples.size * 2
        val fmtChunkSize = 16
        val fileSize = 4 + (8 + fmtChunkSize) + (8 + dataSize) // WAVE + fmt chunk + data chunk

        val buf = ByteBuffer.allocate(12 + 8 + fmtChunkSize + 8 + dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buf.put("RIFF".toByteArray())
        buf.putInt(fileSize)
        buf.put("WAVE".toByteArray())

        // fmt chunk
        buf.put("fmt ".toByteArray())
        buf.putInt(fmtChunkSize)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2) // byteRate
        buf.putShort((channels * 2).toShort()) // blockAlign
        buf.putShort(16) // bitsPerSample

        // data chunk
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (s in samples) buf.putShort(s)

        file.writeBytes(buf.array())
        return file
    }

    private fun createWavWithExtraChunk(sampleRate: Int, channels: Int, samples: ShortArray): File {
        val file = tempFolder.newFile("test_extra_${System.nanoTime()}.wav")
        val dataSize = samples.size * 2
        val fmtChunkSize = 16
        val extraChunkSize = 10
        val fileSize = 4 + (8 + fmtChunkSize) + (8 + extraChunkSize) + (8 + dataSize)

        val buf = ByteBuffer.allocate(12 + 8 + fmtChunkSize + 8 + extraChunkSize + 8 + dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buf.put("RIFF".toByteArray())
        buf.putInt(fileSize)
        buf.put("WAVE".toByteArray())

        // fmt chunk
        buf.put("fmt ".toByteArray())
        buf.putInt(fmtChunkSize)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)
        buf.putShort((channels * 2).toShort())
        buf.putShort(16)

        // Extra chunk (e.g. "LIST")
        buf.put("LIST".toByteArray())
        buf.putInt(extraChunkSize)
        buf.put(ByteArray(extraChunkSize)) // padding

        // data chunk
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (s in samples) buf.putShort(s)

        file.writeBytes(buf.array())
        return file
    }
}
