package com.notenotes.ui.components

import com.google.gson.Gson
import com.notenotes.model.MusicalNote
import com.notenotes.util.GuitarUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Additional E2E-like unit tests that exercise JSON -> model -> overlay round-trips.
 */
class EditorRoundTripE2ETest {

    @Test
    fun json_zeroBased_string_is_normalized_to_human_and_renders() {
        // Legacy JSON might contain 0-based string numbers; adapter now clamps to 1..6.
        val json = """{"pitches":[64],"durationTicks":4,"type":"quarter","tabPositions":[{"first":0,"second":2}]}"""
        val gson = Gson()
        val parsed = gson.fromJson(json, MusicalNote::class.java)

        

        // Expect the adapter to coerce 0 -> 1 (human numbering)
        val expectedHuman = listOf(Pair(1, 2))
        assertEquals(expectedHuman, parsed.safeTabPositionsAsHuman)

        // Overlays should be keyed by human string number
        val overlays = computeNoteOverlays(listOf(parsed), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap
        assertTrue(labelMap.containsKey(1))
        val annotated = labelMap[1]!!
        assertTrue(annotated.text.contains("2"))
    }

    @Test
    fun editor_note_save_reload_roundtrip_keeps_human_positions_and_overlays() {
        val note = MusicalNote(
            pitches = listOf(64),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(6, 3)), // human 6 = low E
            isManual = true
        )
        val gson = Gson()
        val json = gson.toJson(note)

        val re = gson.fromJson(json, MusicalNote::class.java)
        
        // Model should preserve human tabPositions
        assertEquals(listOf(Pair(6, 3)), re.safeTabPositionsAsHuman)

        val overlays = computeNoteOverlays(listOf(re), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap
        
        // Expect the human string 6 to be present (low E)
        assertTrue(labelMap.containsKey(6))
        val annotated = labelMap[6]!!
        assertTrue(annotated.text.contains("3"))
    }
}
