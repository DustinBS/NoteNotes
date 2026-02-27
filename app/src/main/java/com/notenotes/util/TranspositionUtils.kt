package com.notenotes.util

import com.notenotes.model.InstrumentProfile
import com.notenotes.model.MusicalNote

/**
 * Handles transposition of notes for different instruments.
 * All detection is done in concert pitch. Transposition is applied only at notation/export stage.
 */
object TranspositionUtils {

    /**
     * Transpose a MIDI note number for the given instrument.
     * Concert pitch -> written pitch (for notation/export).
     */
    fun transposeForInstrument(concertMidiNote: Int, instrument: InstrumentProfile): Int {
        val transposed = concertMidiNote + instrument.transposeSemitones
        return transposed.coerceIn(0, 127)
    }

    /**
     * Reverse transposition: written pitch -> concert pitch.
     */
    fun concertPitchFromWritten(writtenMidiNote: Int, instrument: InstrumentProfile): Int {
        val concert = writtenMidiNote - instrument.transposeSemitones
        return concert.coerceIn(0, 127)
    }

    /**
     * Transpose a list of MusicalNotes for the given instrument.
     */
    fun transposeNotes(notes: List<MusicalNote>, instrument: InstrumentProfile): List<MusicalNote> {
        if (instrument.transposeSemitones == 0) return notes
        return notes.map { note ->
            if (note.isRest) note
            else note.copy(
                midiPitch = transposeForInstrument(note.midiPitch, instrument),
                chordPitches = note.chordPitches.map { transposeForInstrument(it, instrument) }
            )
        }
    }
}
