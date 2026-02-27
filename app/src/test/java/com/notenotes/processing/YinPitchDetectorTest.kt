package com.notenotes.processing

import com.notenotes.util.PitchUtils
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class YinPitchDetectorTest {

    private val defaultDetector = YinPitchDetector(sampleRate = 44100, frameSize = 2048, hopSize = 1024, threshold = 0.15)

    private fun generateSineWave(frequencyHz: Double, durationSec: Double, sampleRate: Int = 44100): ShortArray {
        val numSamples = (sampleRate * durationSec).toInt()
        return ShortArray(numSamples) { i ->
            (Short.MAX_VALUE * sin(2.0 * PI * frequencyHz * i / sampleRate)).toInt().toShort()
        }
    }

    // T1.1: 440 Hz sine wave, 1 second → A4 (MIDI 69) detected with >95% confidence
    @Test
    fun `T1_1 detect A4 440Hz with high confidence`() {
        val samples = generateSineWave(440.0, 1.0)
        val results = defaultDetector.detectPitches(samples)

        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect pitched frames for A4", pitchedResults.isNotEmpty())

        val avgMidi = pitchedResults.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()
        assertEquals("Average MIDI note should be ~69 (A4)", 69.0, avgMidi, 0.5)

        val avgConfidence = pitchedResults.map { it.confidence }.average()
        assertTrue("Confidence should be >0.95 for pure sine", avgConfidence > 0.95)
    }

    // T1.2: 261.63 Hz sine, 1 second → C4 (MIDI 60)
    @Test
    fun `T1_2 detect C4 261_63Hz`() {
        val samples = generateSineWave(261.63, 1.0)
        val results = defaultDetector.detectPitches(samples)

        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect pitched frames for C4", pitchedResults.isNotEmpty())

        val avgMidi = pitchedResults.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()
        assertEquals("Average MIDI note should be ~60 (C4)", 60.0, avgMidi, 0.5)
    }

    // T1.3: Silence (amplitude = 0) → No pitch detected (silence flag)
    @Test
    fun `T1_3 silence produces no pitched results`() {
        val samples = ShortArray(44100) { 0 }
        val results = defaultDetector.detectPitches(samples)

        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Silence should produce no pitched frames", pitchedResults.isEmpty())
    }

    // T1.4: White noise → No pitch detected or low confidence
    @Test
    fun `T1_4 white noise produces no pitch or low confidence`() {
        val random = java.util.Random(42)
        val samples = ShortArray(44100) { (random.nextGaussian() * Short.MAX_VALUE * 0.5).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
        val results = defaultDetector.detectPitches(samples)

        val pitchedResults = results.filter { it.isPitched }
        val totalFrames = results.size.coerceAtLeast(1)
        val pitchedRatio = pitchedResults.size.toDouble() / totalFrames

        assertTrue(
            "Most frames of white noise should not be pitched (pitched ratio: $pitchedRatio)",
            pitchedRatio < 0.3
        )
    }

    // T1.5: Sequence: A4 → B4 → C5 (each 0.5s) → Three pitches in order
    @Test
    fun `T1_5 sequence A4 B4 C5 detected in order`() {
        val a4 = generateSineWave(440.0, 0.5)    // MIDI 69
        val b4 = generateSineWave(493.88, 0.5)   // MIDI 71
        val c5 = generateSineWave(523.25, 0.5)   // MIDI 72

        val combined = ShortArray(a4.size + b4.size + c5.size)
        a4.copyInto(combined, 0)
        b4.copyInto(combined, a4.size)
        c5.copyInto(combined, a4.size + b4.size)

        val results = defaultDetector.detectPitches(combined)
        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect pitched frames in sequence", pitchedResults.size >= 3)

        // Split into three time regions (using seconds)
        val totalDurationSec = combined.size.toDouble() / 44100
        val thirdSec = totalDurationSec / 3.0

        val region1 = pitchedResults.filter { it.timeSeconds < thirdSec }
        val region2 = pitchedResults.filter { it.timeSeconds >= thirdSec && it.timeSeconds < 2 * thirdSec }
        val region3 = pitchedResults.filter { it.timeSeconds >= 2 * thirdSec }

        assertTrue("Region 1 should have pitched frames", region1.isNotEmpty())
        assertTrue("Region 2 should have pitched frames", region2.isNotEmpty())
        assertTrue("Region 3 should have pitched frames", region3.isNotEmpty())

        val avgMidi1 = region1.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()
        val avgMidi2 = region2.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()
        val avgMidi3 = region3.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()

        assertEquals("Region 1 should be A4 (MIDI 69)", 69.0, avgMidi1, 1.0)
        assertEquals("Region 2 should be B4 (MIDI 71)", 71.0, avgMidi2, 1.0)
        assertEquals("Region 3 should be C5 (MIDI 72)", 72.0, avgMidi3, 1.0)

        assertTrue("Pitches should be in ascending order", avgMidi1 < avgMidi2 && avgMidi2 <= avgMidi3)
    }

    // T1.6: Very low pitch: E2 (82.4 Hz) → E2 (MIDI 40) — use larger frameSize
    @Test
    fun `T1_6 detect low pitch E2 82_4Hz with larger frame`() {
        val lowPitchDetector = YinPitchDetector(sampleRate = 44100, frameSize = 4096, hopSize = 1024, threshold = 0.15)
        val samples = generateSineWave(82.4, 1.0)
        val results = lowPitchDetector.detectPitches(samples)

        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect pitched frames for E2", pitchedResults.isNotEmpty())

        val avgMidi = pitchedResults.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()
        assertEquals("Average MIDI note should be ~40 (E2)", 40.0, avgMidi, 1.0)
    }

    // T1.7: Very high pitch: C6 (1046.5 Hz) → C6 (MIDI 84)
    @Test
    fun `T1_7 detect high pitch C6 1046_5Hz`() {
        val samples = generateSineWave(1046.5, 1.0)
        val results = defaultDetector.detectPitches(samples)

        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect pitched frames for C6", pitchedResults.isNotEmpty())

        val avgMidi = pitchedResults.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()
        assertEquals("Average MIDI note should be ~84 (C6)", 84.0, avgMidi, 0.5)
    }

    // T1.8: A440 with slight vibrato (±5 Hz) → A4 (MIDI 69)
    @Test
    fun `T1_8 detect A4 with vibrato still resolves to MIDI 69`() {
        val sampleRate = 44100
        val durationSec = 1.0
        val numSamples = (sampleRate * durationSec).toInt()
        val vibratoRate = 5.0 // Hz vibrato rate
        val vibratoDepth = 5.0 // ±5 Hz

        val samples = ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val instantFreq = 440.0 + vibratoDepth * sin(2.0 * PI * vibratoRate * t)
            (Short.MAX_VALUE * sin(2.0 * PI * instantFreq * t)).toInt().toShort()
        }

        val results = defaultDetector.detectPitches(samples)
        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect pitched frames with vibrato", pitchedResults.isNotEmpty())

        val avgMidi = pitchedResults.map { PitchUtils.frequencyToMidi(it.frequencyHz).toDouble() }.average()
        assertEquals("Average MIDI note should still be ~69 (A4) with vibrato", 69.0, avgMidi, 1.0)
    }

    // T1.9: Pitch bend: A4 gliding to B4 over 1s → Sequence of intermediate pitches
    @Test
    fun `T1_9 pitch bend A4 to B4 produces intermediate pitches`() {
        val sampleRate = 44100
        val durationSec = 1.0
        val numSamples = (sampleRate * durationSec).toInt()
        val startFreq = 440.0  // A4
        val endFreq = 493.88   // B4

        val samples = ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val fraction = t / durationSec
            val freq = startFreq + (endFreq - startFreq) * fraction
            (Short.MAX_VALUE * sin(2.0 * PI * freq * t)).toInt().toShort()
        }

        val results = defaultDetector.detectPitches(samples)
        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect pitched frames during glide", pitchedResults.size >= 2)

        val frequencies = pitchedResults.map { it.frequencyHz }
        // Check that there are some frequencies between A4 and B4
        val intermediateFreqs = frequencies.filter { it > 445.0 && it < 490.0 }
        assertTrue(
            "Should have intermediate frequencies between A4 and B4 during glide",
            intermediateFreqs.isNotEmpty()
        )

        // Check general upward trend: first quarter average < last quarter average
        val quarterSize = pitchedResults.size / 4
        if (quarterSize > 0) {
            val firstQuarterAvg = pitchedResults.take(quarterSize).map { it.frequencyHz }.average()
            val lastQuarterAvg = pitchedResults.takeLast(quarterSize).map { it.frequencyHz }.average()
            assertTrue(
                "Frequency should trend upward (first quarter avg: $firstQuarterAvg, last quarter avg: $lastQuarterAvg)",
                firstQuarterAvg < lastQuarterAvg
            )
        }
    }

    // T1.10: detectPitches with A4 for 1 second → all pitched frames resolve to MIDI 69 with frequency near 440 Hz
    @Test
    fun `T1_10 detectPitches returns pitched frames with MIDI 69 for A4`() {
        val samples = generateSineWave(440.0, 1.0)
        val results = defaultDetector.detectPitches(samples)

        val pitchedResults = results.filter { it.isPitched }
        assertTrue("Should detect at least one pitched frame", pitchedResults.isNotEmpty())

        val a4Frames = pitchedResults.filter { PitchUtils.frequencyToMidi(it.frequencyHz) == 69 }
        assertTrue("Should have at least one frame with MIDI 69 (A4)", a4Frames.isNotEmpty())

        // Verify frequency is near 440 Hz for all pitched frames
        for (frame in pitchedResults) {
            assertTrue(
                "Frequency ${frame.frequencyHz} should be near 440 Hz",
                frame.frequencyHz in 430.0..450.0
            )
        }

        // Verify confidence is reasonable
        val avgConfidence = pitchedResults.map { it.confidence }.average()
        assertTrue("Average confidence should be > 0.5", avgConfidence > 0.5)

        // Verify timeSeconds values are non-negative and increasing
        for (i in 1 until pitchedResults.size) {
            assertTrue(
                "Time should be non-decreasing",
                pitchedResults[i].timeSeconds >= pitchedResults[i - 1].timeSeconds
            )
        }
    }
}
