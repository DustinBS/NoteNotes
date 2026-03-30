package com.notenotes.ui.components

import com.notenotes.model.MusicalNote
import com.notenotes.util.GuitarUtils
import org.junit.Assert.*
import org.junit.Test

class GuitarChordEditStateMappingTest {

    @Test
    fun updateSelectedStringFret_sets_human_and_midi() {
        val state = GuitarChordEditState(null)

        // Simulate selecting human string 5 (A string) and fret 0
        state.updateSelectedStringFret(5, 0)

        assertEquals(5, state.selectedStringIndex)
        assertEquals(5, state.editedPrimaryString)
        assertEquals(0, state.editedPrimaryFret)

        // Ensure computed MIDI matches explicit human helper
        val expectedMidi = GuitarUtils.toMidiHuman(5, 0)
        assertEquals(expectedMidi, state.editedPrimaryMidi)
    }

    @Test
    fun addChordNote_appends_human_position_and_selects_it() {
        val state = GuitarChordEditState(null)

        state.addChordNote(5, 0)

        // last chord position should match human 5, fret 0
        assertTrue(state.editableChordPositions.isNotEmpty())
        val last = state.editableChordPositions.last()
        assertEquals(5, last.first)
        assertEquals(0, last.second)

        // selection should be updated to new position
        assertEquals(5, state.selectedStringIndex)
        assertEquals(0, state.selectedFret)
    }

    @Test
    fun removePrimary_promotes_first_chord_note_correctly() {
        // Build a note with a primary on human 1 and a chord note on human 5
        val note = MusicalNote(
            pitches = listOf(GuitarUtils.toMidiHuman(1, 0), GuitarUtils.toMidiHuman(5, 0)),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0), Pair(5, 0))
        )

        val state = GuitarChordEditState(note)

        // Remove primary should promote the chord note
        val promoted = state.removePrimaryNote()
        assertTrue(promoted)

        // New primary should be the promoted human 5
        assertEquals(5, state.editedPrimaryString)
        assertEquals(0, state.editedPrimaryFret)
    }
}
