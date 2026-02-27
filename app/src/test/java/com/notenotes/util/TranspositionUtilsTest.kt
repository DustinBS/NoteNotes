package com.notenotes.util

import com.notenotes.model.InstrumentProfile
import org.junit.Assert.*
import org.junit.Test

class TranspositionUtilsTest {

    /** T8.1: MIDI 60, Piano → 60 (no transposition) */
    @Test
    fun concertToWritten_piano_noTransposition() {
        val written = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.PIANO)
        assertEquals(60, written)
    }

    /** T8.2: MIDI 60, Guitar → 72 (written pitched up octave) */
    @Test
    fun concertToWritten_guitar_upOctave() {
        val written = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.GUITAR)
        assertEquals(72, written)
    }

    /** T8.3: MIDI 60, Tenor Sax → 74 (written pitched up major 9th: octave + major 2nd) */
    @Test
    fun concertToWritten_tenorSax_upMajorSecond() {
        val written = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.BB_TENOR_SAX)
        assertEquals(74, written)
    }

    /** T8.4: MIDI 60, Alto Sax → 69 (written pitched up major 6th) */
    @Test
    fun concertToWritten_altoSax_upMajorSixth() {
        val written = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.EB_ALTO_SAX)
        assertEquals(69, written)
    }

    /** T8.5: MIDI 60, Bass Guitar → 72 (written pitched up octave) */
    @Test
    fun concertToWritten_bassGuitar_upOctave() {
        val written = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.BASS_GUITAR)
        assertEquals(72, written)
    }

    /** Round-trip: transposeForInstrument then concertPitchFromWritten should return original for Piano */
    @Test
    fun roundTrip_piano_returnsOriginal() {
        val original = 60
        val written = TranspositionUtils.transposeForInstrument(original, InstrumentProfile.PIANO)
        val concert = TranspositionUtils.concertPitchFromWritten(written, InstrumentProfile.PIANO)
        assertEquals(original, concert)
    }

    /** Round-trip: transposeForInstrument then concertPitchFromWritten should return original for Guitar */
    @Test
    fun roundTrip_guitar_returnsOriginal() {
        val original = 60
        val written = TranspositionUtils.transposeForInstrument(original, InstrumentProfile.GUITAR)
        val concert = TranspositionUtils.concertPitchFromWritten(written, InstrumentProfile.GUITAR)
        assertEquals(original, concert)
    }

    /** Round-trip: transposeForInstrument then concertPitchFromWritten should return original for Tenor Sax */
    @Test
    fun roundTrip_tenorSax_returnsOriginal() {
        val original = 60
        val written = TranspositionUtils.transposeForInstrument(original, InstrumentProfile.BB_TENOR_SAX)
        val concert = TranspositionUtils.concertPitchFromWritten(written, InstrumentProfile.BB_TENOR_SAX)
        assertEquals(original, concert)
    }

    /** Round-trip: transposeForInstrument then concertPitchFromWritten should return original for Alto Sax */
    @Test
    fun roundTrip_altoSax_returnsOriginal() {
        val original = 60
        val written = TranspositionUtils.transposeForInstrument(original, InstrumentProfile.EB_ALTO_SAX)
        val concert = TranspositionUtils.concertPitchFromWritten(written, InstrumentProfile.EB_ALTO_SAX)
        assertEquals(original, concert)
    }

    /** Round-trip: transposeForInstrument then concertPitchFromWritten should return original for Bass Guitar */
    @Test
    fun roundTrip_bassGuitar_returnsOriginal() {
        val original = 60
        val written = TranspositionUtils.transposeForInstrument(original, InstrumentProfile.BASS_GUITAR)
        val concert = TranspositionUtils.concertPitchFromWritten(written, InstrumentProfile.BASS_GUITAR)
        assertEquals(original, concert)
    }
}
