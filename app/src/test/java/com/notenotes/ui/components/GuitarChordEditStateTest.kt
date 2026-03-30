package com.notenotes.ui.components

import com.notenotes.model.MusicalNote
import org.junit.Assert.*
import org.junit.Test

class GuitarChordEditStateTest {

    @Test
    fun testInitializationFromNote() {
        // A note with primary on string 0 (High e) fret 0, and chord on string 1 fret 1
        val testNote = MusicalNote(
            pitches = listOf(40, 45), // random midi ints
            // Use canonical human 1-based string numbers (1 = High E, 6 = Low E)
            tabPositions = listOf(Pair(6, 0), Pair(5, 1)),
            durationTicks = 4,
            type = "quarter"
        )

        val state = GuitarChordEditState(testNote)

        assertEquals(6, state.editedPrimaryString)
        assertEquals(0, state.editedPrimaryFret)
        
        assertEquals(1, state.editableChordPitches.size)
        assertEquals(5, state.editableChordPositions[0].first)
        assertEquals(1, state.editableChordPositions[0].second)

        assertFalse(state.hasPendingChanges)
    }

    @Test
    fun testHasPendingChanges_WhenStringChanges() {
        val state = GuitarChordEditState(null) // Empty note defaults to 0,0
        assertFalse(state.hasPendingChanges)

        state.editedPrimaryFret = 5
        assertTrue(state.hasPendingChanges)
    }

    @Test
    fun testRemovePrimaryNote_PromotesFirstChordNote() {
        // Note with primary and 2 chord notes
        val testNote = MusicalNote(
            pitches = listOf(40, 45, 50),
            tabPositions = listOf(Pair(6, 0), Pair(5, 1), Pair(4, 2)),
            durationTicks = 4,
            type = "quarter"
        )

        val state = GuitarChordEditState(testNote)
        
        val promoted = state.removePrimaryNote()

        assertTrue("Should successfully promote chord note", promoted)
        
        // Assert old second note is now primary (human numbering)
        assertEquals(5, state.editedPrimaryString)
        assertEquals(1, state.editedPrimaryFret)

        // Assert old third note is now first chord note (human numbering)
        assertEquals(1, state.editableChordPitches.size)
        assertEquals(4, state.editableChordPositions[0].first)
        assertEquals(2, state.editableChordPositions[0].second)
    }

    @Test
    fun testRemovePrimaryNote_NoChordNotesAvailable() {
        val testNote = MusicalNote(
            pitches = listOf(40),
            tabPositions = listOf(Pair(6, 0)),
            durationTicks = 4,
            type = "quarter"
        )

        val state = GuitarChordEditState(testNote)
        val promoted = state.removePrimaryNote()

        assertFalse("Should return false indicating the whole chord should be deleted", promoted)
    }
}
