package com.notenotes.processing

import com.notenotes.model.DetectedNote
import com.notenotes.model.MusicalNote
import com.notenotes.model.TimeSignature
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RhythmQuantizerTest {

    private lateinit var quantizer: RhythmQuantizer
    private val tempoBpm = 120
    private val timeSignature = TimeSignature.FOUR_FOUR

    @Before
    fun setUp() {
        quantizer = RhythmQuantizer()
    }

    /**
     * Helper: creates a single DetectedNote at MIDI 60 (C4) with the given
     * duration in seconds, starting at [startTimeSec].
     */
    private fun makeNote(
        durationSec: Double,
        startTimeSec: Double = 0.0,
        midiNote: Int = 60
    ): DetectedNote {
        return DetectedNote(
            midiNote = midiNote,
            frequencyHz = 261.63,
            onsetSeconds = startTimeSec,
            durationSeconds = durationSec,
            confidence = 0.8
        )
    }

    // T5.1: 0.5s duration → quarter note (type="quarter", durationTicks=4)
    @Test
    fun quantize_exactQuarterNoteDuration_returnsQuarterNote() {
        val notes = listOf(makeNote(durationSec = 0.5))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("quarter", note.type)
        assertEquals(4, note.durationTicks)
        assertFalse(note.dotted)
    }

    // T5.2: 0.48s → snaps to quarter note
    @Test
    fun quantize_slightlyShortQuarter_snapsToQuarterNote() {
        val notes = listOf(makeNote(durationSec = 0.48))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("quarter", note.type)
        assertEquals(4, note.durationTicks)
        assertFalse(note.dotted)
    }

    // T5.3: 0.53s → snaps to quarter note
    @Test
    fun quantize_slightlyLongQuarter_snapsToQuarterNote() {
        val notes = listOf(makeNote(durationSec = 0.53))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("quarter", note.type)
        assertEquals(4, note.durationTicks)
        assertFalse(note.dotted)
    }

    // T5.4: 0.25s → eighth note (type="eighth", durationTicks=2)
    @Test
    fun quantize_eighthNoteDuration_returnsEighthNote() {
        val notes = listOf(makeNote(durationSec = 0.25))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("eighth", note.type)
        assertEquals(2, note.durationTicks)
        assertFalse(note.dotted)
    }

    // T5.5: 1.0s → half note (type="half", durationTicks=8)
    @Test
    fun quantize_halfNoteDuration_returnsHalfNote() {
        val notes = listOf(makeNote(durationSec = 1.0))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("half", note.type)
        assertEquals(8, note.durationTicks)
        assertFalse(note.dotted)
    }

    // T5.6: 2.0s → whole note (type="whole", durationTicks=16)
    @Test
    fun quantize_wholeNoteDuration_returnsWholeNote() {
        val notes = listOf(makeNote(durationSec = 2.0))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("whole", note.type)
        assertEquals(16, note.durationTicks)
        assertFalse(note.dotted)
    }

    // T5.7: 0.125s → sixteenth note (type="16th", durationTicks=1)
    @Test
    fun quantize_sixteenthNoteDuration_returnsSixteenthNote() {
        val notes = listOf(makeNote(durationSec = 0.125))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("16th", note.type)
        assertEquals(1, note.durationTicks)
        assertFalse(note.dotted)
    }

    // T5.8: 0.75s → dotted quarter (type="quarter", dotted=true, durationTicks=6)
    @Test
    fun quantize_dottedQuarterDuration_returnsDottedQuarter() {
        val notes = listOf(makeNote(durationSec = 0.75))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("quarter", note.type)
        assertTrue(note.dotted)
        assertEquals(6, note.durationTicks)
    }

    // T5.9: 1.5s → dotted half (type="half", dotted=true, durationTicks=12)
    @Test
    fun quantize_dottedHalfDuration_returnsDottedHalf() {
        val notes = listOf(makeNote(durationSec = 1.5))
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(1, result.size)
        val note = result[0]
        assertEquals("half", note.type)
        assertTrue(note.dotted)
        assertEquals(12, note.durationTicks)
    }

    // T5.10: Multiple notes → correct count preserved
    @Test
    fun quantize_multipleNotes_preservesCount() {
        val notes = listOf(
            makeNote(durationSec = 0.5, startTimeSec = 0.0),
            makeNote(durationSec = 0.25, startTimeSec = 0.5),
            makeNote(durationSec = 0.25, startTimeSec = 0.75),
            makeNote(durationSec = 1.0, startTimeSec = 1.0)
        )
        val result = quantizer.quantize(notes, tempoBpm, timeSignature)
        assertEquals(4, result.size)
        assertEquals("quarter", result[0].type)
        assertEquals("eighth", result[1].type)
        assertEquals("eighth", result[2].type)
        assertEquals("half", result[3].type)
    }
}
