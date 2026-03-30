package com.notenotes.ui.components

import com.google.gson.Gson
import com.notenotes.util.GuitarUtils
import com.notenotes.model.MusicalNote
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E style test: ensure persisted JSON with 0-based tabPositions is normalized
 * to canonical human 1-based storage and renders correctly in overlays.
 */
class TabPositionRoundTripE2ETest {

    @Test
    fun json_zeroBased_tabPosition_normalizes_and_renders() {
        // Use canonical human string numbers in persisted JSON (1..6). Previously tests
        // relied on adapter heuristics to convert 0-based indices; final cleanup removes
        // that heuristic so tests must provide human values.
        val json = """{"pitches":[64],"durationTicks":4,"type":"quarter","tabPositions":[{"first":6,"second":3}]}"""

        val gson = Gson()
        val parsed = gson.fromJson(json, MusicalNote::class.java)
        

        val expectedHuman = listOf(Pair(6, 3))
        assertEquals(expectedHuman, parsed.safeTabPositionsAsHuman)

        // Now compute overlays and ensure rendering uses the 0-based index key
        val overlays = computeNoteOverlays(listOf(parsed), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap
        

        // Overlays now key maps by human 1-based string numbers (1..6).
        val expectedHumanKey = expectedHuman[0].first
        assertTrue("Overlay should contain expected human string key", labelMap.containsKey(expectedHumanKey))

        val annotated = labelMap[expectedHumanKey]!!
        assertTrue(annotated.text.contains("3"))
    }
}
