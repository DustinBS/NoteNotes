package com.notenotes.ui.components

import com.notenotes.model.MusicalNote
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for waveform caption mapping (string indexing).
 */
class WaveformViewTest {

    @Test
    fun computeNoteOverlays_acceptsHumanOneBasedStringIndex() {
        // human 1-based string index of 1 should map to internal index 5 (high E)
        // Use fret 0 so the stored pitch matches the string/fret mapping
        val note = MusicalNote(
            pitches = listOf(64),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0))
        )

        val overlays = computeNoteOverlays(listOf(note), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap
        // internal index for string 1 (high E) == 5
        assertTrue("Expected high E (internal index 5) to be present; found keys=${labelMap.keys}", labelMap.containsKey(5))
    }

    @Test
    fun computeNoteOverlays_acceptsZeroBasedStringIndex() {
        // zero-based index of 5 should also map to internal index 5
        // Zero-based index 5, fret 0 matches pitch 64
        val note = MusicalNote(
            pitches = listOf(64),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0))
        )

        val overlays = computeNoteOverlays(listOf(note), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap
        assertTrue("Expected high E (internal index 5) to be present; found keys=${labelMap.keys}", labelMap.containsKey(5))
    }

    @Test
    fun computeNoteOverlays_doesNotFillMissingStrings_forChord() {
        // Two-string chord: A (human 5) and D (human 4) open strings
        val note = MusicalNote(
            pitches = listOf(45, 50), // A2 (45), D3 (50)
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(5, 0), Pair(4, 0)) // human 1-based strings
        )

        val overlays = computeNoteOverlays(listOf(note), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap
        // Expect only the two active strings to be present (internal indices 1 and 2)
        assertEquals("Expected exactly 2 labeled strings; got=${labelMap.keys}", 2, labelMap.size)
        assertTrue(labelMap.containsKey(1)) // internal index for human 5 (A)
        assertTrue(labelMap.containsKey(2)) // internal index for human 4 (D)
        // Ensure other strings are not auto-filled
        for (i in 0..5) if (i !in listOf(1, 2)) assertFalse("Unexpected filled string $i", labelMap.containsKey(i))
    }

    @Test
    fun computeNoteOverlays_singleNoteCountsAsOneLabel() {
        val note = MusicalNote(
            pitches = listOf(64),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0)) // high E
        )

        val overlays = computeNoteOverlays(listOf(note), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap
        assertEquals(1, labelMap.size)
    }
}
