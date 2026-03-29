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
    fun rawToIndex_and_human_mappings() {
        // Diagnostic prints
        println("STRINGS (number,openMidi) = ${GuitarUtils.STRINGS.map { Pair(it.number, it.openMidi) }}")
        println("rawToIndex(0)=${GuitarUtils.rawToIndex(0)} rawToIndex(1)=${GuitarUtils.rawToIndex(1)} rawToIndex(5)=${GuitarUtils.rawToIndex(5)} rawToIndex(6)=${GuitarUtils.rawToIndex(6)}")

        assertEquals(0, GuitarUtils.rawToIndex(0))
        // With human-first normalization, raw value 5 (ambiguous) maps to human index -> internal 1
        assertEquals(1, GuitarUtils.rawToIndex(5))
        assertEquals(5, GuitarUtils.rawToIndex(1))
        assertEquals(0, GuitarUtils.rawToIndex(6))

        assertEquals(5, GuitarUtils.humanToIndex(1))
        assertEquals(0, GuitarUtils.humanToIndex(6))

        assertEquals(6, GuitarUtils.indexToHuman(0))
        assertEquals(1, GuitarUtils.indexToHuman(5))
    }

    @Test
    fun toMidi_accepts_both_index_and_human() {
        println("STRINGS openMidi by index = ${GuitarUtils.STRINGS.map { it.openMidi }}")
        println("toMidi(0,0)=${GuitarUtils.toMidi(0, 0)} toMidi(1,0)=${GuitarUtils.toMidi(1,0)} toMidi(5,0)=${GuitarUtils.toMidi(5,0)}")

        assertEquals(GuitarUtils.STRINGS[0].openMidi + 0, GuitarUtils.toMidi(0, 0))
        // human 1 -> index 5 (high E)
        assertEquals(GuitarUtils.STRINGS[5].openMidi + 0, GuitarUtils.toMidi(1, 0))
        // ambiguous 5 -> human-first normalization maps to internal index 1 (openMidi=45)
        assertEquals(GuitarUtils.STRINGS[1].openMidi + 0, GuitarUtils.toMidi(5, 0))
    }

    @Test
    fun musicalNote_safeTabPositionsAsIndex_and_adapter_normalization() {
        val n = MusicalNote(
            pitches = listOf(64),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0), Pair(0, 0), Pair(10, 0))
        )

        val list = n.safeTabPositionsAsIndex
        // (1,0) -> human 1 -> internal index 5
        assertEquals(5, list[0].first)
        // (0,0) interpreted as zero-based index -> 0
        assertEquals(0, list[1].first)
        // out-of-range fallback -> 0
        assertEquals(0, list[2].first)
    }

    @Test
    fun adapter_normalizes_json_tabPositions() {
        val gson = Gson()
        val json1 = """{"pitches":[64],"durationTicks":4,"type":"quarter","tabPositions":[{"first":0,"second":0}]}"""
        val parsed1 = gson.fromJson(json1, MusicalNote::class.java)
        // adapter should convert raw 0 (zero-based) to human 6
        assertEquals(1, parsed1.tabPositions.size)
        assertEquals(6, parsed1.tabPositions[0].first)
        assertEquals(0, parsed1.tabPositions[0].second)
        // safeTabPositionsAsIndex should map human 6 -> index 0
        assertEquals(0, parsed1.safeTabPositionsAsIndex[0].first)

        val json2 = """{"pitches":[64],"durationTicks":4,"type":"quarter","tabPositions":[{"first":1,"second":0}]}"""
        val parsed2 = gson.fromJson(json2, MusicalNote::class.java)
        assertEquals(1, parsed2.tabPositions.size)
        assertEquals(1, parsed2.tabPositions[0].first)
        assertEquals(0, parsed2.tabPositions[0].second)
        // human 1 -> internal index 5
        assertEquals(5, parsed2.safeTabPositionsAsIndex[0].first)
    }
}
