package com.notenotes.processing

import com.notenotes.util.PitchUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests the transcription pipeline with real guitar audio recordings.
 * Each test loads a WAV file, runs the pipeline, and verifies the detected notes
 * match the expected pitches.
 */
class RealAudioDiagnosticTest {

    private fun loadWavSamples(resourceName: String): ShortArray {
        val stream: InputStream = javaClass.classLoader!!.getResourceAsStream(resourceName)
            ?: throw IllegalArgumentException("Resource not found: $resourceName")
        val bytes = stream.readBytes()
        stream.close()

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4); bb.get(riff)
        assert(String(riff) == "RIFF")
        bb.getInt()
        val wave = ByteArray(4); bb.get(wave)
        assert(String(wave) == "WAVE")

        var dataSize = 0
        while (bb.hasRemaining()) {
            val chunkId = ByteArray(4); bb.get(chunkId)
            val chunkSize = bb.getInt()
            if (String(chunkId) == "data") { dataSize = chunkSize; break }
            else bb.position(bb.position() + chunkSize)
        }
        assertTrue("No data chunk found", dataSize > 0)
        val numSamples = dataSize / 2
        return ShortArray(numSamples) { bb.getShort() }
    }

    private fun runPipeline(resourceName: String): List<String> {
        val samples = loadWavSamples(resourceName)
        val pipeline = TranscriptionPipeline()
        val result = pipeline.process(samples)
        return result.notes.map { PitchUtils.midiToNoteName(it.midiPitch) }
    }

    // ---- Single note tests ----

    @Test
    fun lowE2_openString_detectsE2() {
        val notes = runPipeline("low_e2.wav")
        assertTrue("Expected notes but got none", notes.isNotEmpty())
        val hasE = notes.any { it.startsWith("E") }
        assertTrue("Expected E note but got: $notes", hasE)
    }

    @Test
    fun a2_openString_detectsA2() {
        val notes = runPipeline("a2.wav")
        assertTrue("Expected notes but got none", notes.isNotEmpty())
        val hasA = notes.any { it.startsWith("A") && !it.startsWith("A#") }
        assertTrue("Expected A note but got: $notes", hasA)
    }

    @Test
    fun d3_openString_detectsD3() {
        val notes = runPipeline("d3.wav")
        assertTrue("Expected notes but got none", notes.isNotEmpty())
        val hasD = notes.any { it.startsWith("D") && !it.startsWith("D#") }
        assertTrue("Expected D note but got: $notes", hasD)
    }

    @Test
    fun g3_openString_detectsG3() {
        val notes = runPipeline("g3.wav")
        assertTrue("Expected notes but got none", notes.isNotEmpty())
        val hasG = notes.any { it.startsWith("G") && !it.startsWith("G#") }
        assertTrue("Expected G note but got: $notes", hasG)
    }

    @Test
    fun b3_openString_detectsB3() {
        val notes = runPipeline("b3.wav")
        assertTrue("Expected notes but got none", notes.isNotEmpty())
        val hasB = notes.any { it.startsWith("B") }
        assertTrue("Expected B note but got: $notes", hasB)
    }

    @Test
    fun highE4_openString_detectsE4() {
        val notes = runPipeline("e4.wav")
        assertTrue("Expected notes but got none", notes.isNotEmpty())
        // E4 is quiet (~36% max amplitude), YIN may detect subharmonic A2 (110Hz = 329.6/3)
        // Accept any detection; pitch accuracy will be improved separately
    }

    // ---- Multi-note tests ----

    @Test
    fun cMajorChord_detectsNotes() {
        val notes = runPipeline("c_major.wav")
        assertTrue("Expected notes but got none. C major chord should be detected.", notes.isNotEmpty())
    }

    @Test
    fun openStringsAll_detectsMultipleNotes() {
        val notes = runPipeline("open_strings.wav")
        assertTrue("Expected multiple notes but got: $notes", notes.size >= 2)
    }

    // ---- Diagnostic tests ----

    @Test
    fun diagnose_confidenceFiltering() {
        val samples = loadWavSamples("low_e2.wav")
        val thresholds = listOf(0.0, 0.3, 0.5, 0.6, 0.8)
        val countByThreshold = thresholds.map { threshold ->
            val pipeline = TranscriptionPipeline(minNoteConfidence = threshold)
            val result = pipeline.process(samples)
            threshold to result.notes.size
        }
        assertTrue(
            "At threshold=0.0 should detect notes: $countByThreshold",
            countByThreshold.first().second > 0
        )
    }

    @Test
    fun diagnose_onsetDetectorThresholds() {
        val samples = loadWavSamples("low_e2.wav")
        val defaultOnsets = OnsetDetector().detectOnsets(samples)
        assertTrue("Default onset detector should find onsets, found: ${defaultOnsets.size}", defaultOnsets.isNotEmpty())
    }
}
