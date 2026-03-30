package com.notenotes.util

import com.google.gson.Gson
import com.notenotes.model.MusicalNote
import org.junit.Assert.*
import org.junit.Test

/**
 * Diagnostic tests to trace string-index conversions and adapter normalization.
 */
class GuitarIndexTraceTest {

    @Test
    fun index_and_human_mappings() {
        // Explicit one-based <-> 0-based mappings
        assertEquals(6, GuitarUtils.indexToHuman(0))
        assertEquals(1, GuitarUtils.indexToHuman(5))

        assertEquals(5, GuitarUtils.humanToIndex(1))
        assertEquals(0, GuitarUtils.humanToIndex(6))
    }

    @Test
    fun toMidi_accepts_both_index_and_human() {
        // Prefer explicit one-based API in tests
        assertEquals(GuitarUtils.STRINGS[5].openMidi + 0, GuitarUtils.toMidiHuman(1, 0))
        assertEquals(GuitarUtils.STRINGS[0].openMidi + 0, GuitarUtils.toMidiHuman(6, 0))

    }

    @Test
    fun musicalNote_safeTabPositionsAsIndex_and_adapter_normalization() {
        val n = MusicalNote(
            pitches = listOf(64),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0), Pair(0, 0), Pair(10, 0))
        )

        // Map canonical human view to internal indices for assertions
        val list = n.safeTabPositionsAsHuman.map { Pair(GuitarUtils.humanToIndex(it.first) ?: 0, it.second) }
        // (1,0) -> human 1 -> internal index 5
        assertEquals(5, list[0].first)
        // (0,0) is coerced to human 1 -> internal index 5 under final cleanup
        assertEquals(5, list[1].first)
        // out-of-range fallback -> index 0
        assertEquals(0, list[2].first)
    }

    @Test
    fun adapter_normalizes_json_tabPositions() {
        val gson = Gson()
        // Adapter now expects canonical human numbers; update JSON accordingly.
        val json1 = """{"pitches":[64],"durationTicks":4,"type":"quarter","tabPositions":[{"first":1,"second":0}]}"""
        val parsed1 = gson.fromJson(json1, MusicalNote::class.java)
        assertEquals(1, parsed1.tabPositions.size)
        assertEquals(1, parsed1.tabPositions[0].first)
        assertEquals(0, parsed1.tabPositions[0].second)
        // safeTabPositionsAsHuman should reflect the canonical human value
        assertEquals(1, parsed1.safeTabPositionsAsHuman.size)
        assertEquals(1, parsed1.safeTabPositionsAsHuman[0].first)

        val json2 = """{"pitches":[64],"durationTicks":4,"type":"quarter","tabPositions":[{"first":1,"second":0}]}"""
        val parsed2 = gson.fromJson(json2, MusicalNote::class.java)
        assertEquals(1, parsed2.tabPositions.size)
        assertEquals(1, parsed2.tabPositions[0].first)
        assertEquals(0, parsed2.tabPositions[0].second)
        // human 1 -> internal index 5 (via explicit mapping)
        val idx2 = GuitarUtils.humanToIndex(parsed2.tabPositions[0].first)
        assertEquals(5, idx2)
    }
}
