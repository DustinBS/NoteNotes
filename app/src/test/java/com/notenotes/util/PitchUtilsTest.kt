package com.notenotes.util

import org.junit.Assert.*
import org.junit.Test

class PitchUtilsTest {

    @Test
    fun frequencyToMidi_A4_returns69() {
        assertEquals(69, PitchUtils.frequencyToMidi(440.0))
    }

    @Test
    fun frequencyToMidi_C4_returns60() {
        assertEquals(60, PitchUtils.frequencyToMidi(261.63))
    }

    @Test
    fun frequencyToMidi_A5_returns81() {
        assertEquals(81, PitchUtils.frequencyToMidi(880.0))
    }

    @Test
    fun midiToFrequency_69_returns440() {
        assertEquals(440.0, PitchUtils.midiToFrequency(69), 0.01)
    }

    @Test
    fun midiToFrequency_60_returnsMiddleC() {
        assertEquals(261.63, PitchUtils.midiToFrequency(60), 0.1)
    }

    @Test
    fun midiToNoteName_69_returnsA4() {
        assertEquals("A4", PitchUtils.midiToNoteName(69))
    }

    @Test
    fun midiToNoteName_60_returnsC4() {
        assertEquals("C4", PitchUtils.midiToNoteName(60))
    }

    @Test
    fun midiToNoteName_61_containsSharpOrFlat() {
        val name = PitchUtils.midiToNoteName(61)
        assertTrue(
            "Expected note name to contain 'C#' or 'Db', but was: $name",
            name.contains("C#") || name.contains("Db")
        )
    }

    @Test
    fun midiToMusicXmlPitch_60_returnsCNatural4() {
        val (step, alter, octave) = PitchUtils.midiToMusicXmlPitch(60)
        assertEquals("C", step)
        assertEquals(0, alter)
        assertEquals(4, octave)
    }

    @Test
    fun midiToMusicXmlPitch_61_returnsCSharp4() {
        val (step, alter, octave) = PitchUtils.midiToMusicXmlPitch(61)
        assertEquals("C", step)
        assertEquals(1, alter)
        assertEquals(4, octave)
    }

    @Test
    fun generateSineWave_producesCorrectLengthAndNonZero() {
        val sampleRate = 44100
        val durationSec = 1.0
        val wave = PitchUtils.generateSineWave(440.0, durationSec, sampleRate)

        assertEquals(sampleRate, wave.size)
        assertTrue("Sine wave should contain non-zero values", wave.any { it != 0.toShort() })
    }

    @Test
    fun roundTrip_midiToFrequencyThenBack_returnsSameMidi() {
        for (midi in 21..108) {
            val freq = PitchUtils.midiToFrequency(midi)
            val result = PitchUtils.frequencyToMidi(freq)
            assertEquals("Round trip failed for MIDI note $midi", midi, result)
        }
    }
}
