package com.notenotes.export

import com.notenotes.model.MusicalNote
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Tests for BPM re-quantization formula.
 *
 * The core invariant: changing BPM should preserve absolute duration (ms)
 * of each note. The formula is:
 *   newTicks = round(oldTicks * newBpm / oldBpm)
 *   such that: newTicks * (60000 / newBpm / 4) ≈ oldTicks * (60000 / oldBpm / 4)
 *
 * Uses the "starship og" dataset as a real-world test case.
 */
class BpmReQuantizationTest {

    // The starship og notes at 120 BPM (original)
    private val starshipNotes120 = listOf(
        note(45, 14, 2214.3555f),
        note(45, 47, 4053.955f),
        note(52, 28, 10052.61f),
        note(64, 32, 13616.391f),
        note(45, 74, 17719.193f),
        note(52, 25, 27001.225f),
        note(76, 19, 30183.422f),
        note(74, 12, 32652.836f),
        note(71, 21, 34233.645f),
        note(45, 12, 36936.562f),
        note(64, 27, 38436.805f)
    )

    private fun note(midi: Int, ticks: Int, timeMs: Float) = MusicalNote(
        midiPitch = midi,
        durationTicks = ticks,
        type = "quarter",
        isManual = true,
        timePositionMs = timeMs
    )

    private fun msPerTick(bpm: Int) = 60000.0 / bpm / 4.0

    /**
     * Re-quantize notes from oldBpm to newBpm using the correct formula:
     * newTicks = round(oldTicks * newBpm / oldBpm)
     */
    private fun reQuantize(notes: List<MusicalNote>, oldBpm: Int, newBpm: Int): List<MusicalNote> {
        return notes.map { note ->
            val newTicks = (note.durationTicks.toDouble() * newBpm / oldBpm).roundToInt()
                .coerceAtLeast(1)
            note.copy(durationTicks = newTicks)
        }
    }

    // ── Core formula tests ──────────────────────────────────────────────

    @Test
    fun reQuantize_sameTemp_noChange() {
        val result = reQuantize(starshipNotes120, 120, 120)
        assertEquals(starshipNotes120.map { it.durationTicks }, result.map { it.durationTicks })
    }

    @Test
    fun reQuantize_120to60_preservesDuration() {
        val result = reQuantize(starshipNotes120, 120, 60)
        val oldMsPerTick = msPerTick(120)
        val newMsPerTick = msPerTick(60)

        for (i in starshipNotes120.indices) {
            val oldMs = starshipNotes120[i].durationTicks * oldMsPerTick
            val newMs = result[i].durationTicks * newMsPerTick
            // Allow up to 1 tick of rounding error → max newMsPerTick ms difference
            assertTrue(
                "Note $i: oldMs=$oldMs, newMs=$newMs, diff=${kotlin.math.abs(oldMs - newMs)}",
                kotlin.math.abs(oldMs - newMs) <= newMsPerTick
            )
        }
    }

    @Test
    fun reQuantize_120to60_ticksHalved() {
        // When going from 120→60, each tick at 60 BPM is twice as long,
        // so newTicks should be approximately half of oldTicks
        val result = reQuantize(starshipNotes120, 120, 60)
        for (i in starshipNotes120.indices) {
            val expected = (starshipNotes120[i].durationTicks.toDouble() * 60 / 120).roundToInt()
            assertEquals("Note $i ticks", expected, result[i].durationTicks)
        }
    }

    @Test
    fun reQuantize_120to240_ticksDoubled() {
        // When going from 120→240, each tick at 240 BPM is half as long,
        // so newTicks should be approximately double
        val result = reQuantize(starshipNotes120, 120, 240)
        for (i in starshipNotes120.indices) {
            val expected = (starshipNotes120[i].durationTicks.toDouble() * 240 / 120).roundToInt()
            assertEquals("Note $i ticks", expected, result[i].durationTicks)
        }
    }

    @Test
    fun reQuantize_120to80_preservesDuration() {
        val result = reQuantize(starshipNotes120, 120, 80)
        val oldMsPerTick = msPerTick(120)
        val newMsPerTick = msPerTick(80)

        for (i in starshipNotes120.indices) {
            val oldMs = starshipNotes120[i].durationTicks * oldMsPerTick
            val newMs = result[i].durationTicks * newMsPerTick
            assertTrue(
                "Note $i: oldMs=$oldMs, newMs=$newMs, diff=${kotlin.math.abs(oldMs - newMs)}",
                kotlin.math.abs(oldMs - newMs) <= newMsPerTick
            )
        }
    }

    @Test
    fun reQuantize_120to100_preservesDuration() {
        val result = reQuantize(starshipNotes120, 120, 100)
        val oldMsPerTick = msPerTick(120)
        val newMsPerTick = msPerTick(100)

        for (i in starshipNotes120.indices) {
            val oldMs = starshipNotes120[i].durationTicks * oldMsPerTick
            val newMs = result[i].durationTicks * newMsPerTick
            assertTrue(
                "Note $i: oldMs=$oldMs, newMs=$newMs, diff=${kotlin.math.abs(oldMs - newMs)}",
                kotlin.math.abs(oldMs - newMs) <= newMsPerTick
            )
        }
    }

    // ── Total duration preservation ─────────────────────────────────────

    @Test
    fun reQuantize_totalDuration_preserved_60() {
        val oldTotal = starshipNotes120.sumOf { it.durationTicks } * msPerTick(120)
        val newNotes = reQuantize(starshipNotes120, 120, 60)
        val newTotal = newNotes.sumOf { it.durationTicks } * msPerTick(60)
        // Allow up to 11 * msPerTickNew ms rounding (one tick per note)
        val tolerance = 11 * msPerTick(60)
        assertTrue(
            "Total: old=$oldTotal, new=$newTotal, diff=${kotlin.math.abs(oldTotal - newTotal)}, tol=$tolerance",
            kotlin.math.abs(oldTotal - newTotal) <= tolerance
        )
    }

    @Test
    fun reQuantize_totalDuration_preserved_240() {
        val oldTotal = starshipNotes120.sumOf { it.durationTicks } * msPerTick(120)
        val newNotes = reQuantize(starshipNotes120, 120, 240)
        val newTotal = newNotes.sumOf { it.durationTicks } * msPerTick(240)
        val tolerance = 11 * msPerTick(240)
        assertTrue(
            "Total: old=$oldTotal, new=$newTotal, diff=${kotlin.math.abs(oldTotal - newTotal)}, tol=$tolerance",
            kotlin.math.abs(oldTotal - newTotal) <= tolerance
        )
    }

    // ── Round-trip stability ────────────────────────────────────────────

    @Test
    fun reQuantize_roundTrip_120_60_120_stableWithinOneTick() {
        val to60 = reQuantize(starshipNotes120, 120, 60)
        val backTo120 = reQuantize(to60, 60, 120)
        for (i in starshipNotes120.indices) {
            val diff = kotlin.math.abs(starshipNotes120[i].durationTicks - backTo120[i].durationTicks)
            assertTrue(
                "Note $i: original=${starshipNotes120[i].durationTicks}, roundTrip=${backTo120[i].durationTicks}, diff=$diff",
                diff <= 1
            )
        }
    }

    @Test
    fun reQuantize_roundTrip_120_80_120_stableWithinOneTick() {
        val to80 = reQuantize(starshipNotes120, 120, 80)
        val backTo120 = reQuantize(to80, 80, 120)
        for (i in starshipNotes120.indices) {
            val diff = kotlin.math.abs(starshipNotes120[i].durationTicks - backTo120[i].durationTicks)
            assertTrue(
                "Note $i: original=${starshipNotes120[i].durationTicks}, roundTrip=${backTo120[i].durationTicks}, diff=$diff",
                diff <= 1
            )
        }
    }

    // ── Verify the OLD (wrong) formula produces different results ───────

    @Test
    fun oldFormula_120to60_producesDoubledTicks_WRONG() {
        // The OLD formula was: newTicks = oldTicks * oldBpm / newBpm
        // This DOUBLES ticks when halving BPM, which is WRONG
        val wrongResult = starshipNotes120.map { note ->
            val wrongTicks = (note.durationTicks.toDouble() * 120 / 60).roundToInt()
            note.copy(durationTicks = wrongTicks)
        }
        // At 60 BPM with doubled ticks, durations would be 4x the original
        val oldMs = starshipNotes120[0].durationTicks * msPerTick(120) // 14 * 125 = 1750ms
        val wrongMs = wrongResult[0].durationTicks * msPerTick(60)     // 28 * 250 = 7000ms
        assertEquals("Wrong formula produces 4x duration", oldMs * 4, wrongMs, 1.0)
    }

    @Test
    fun correctFormula_120to60_preservesAbsoluteDuration() {
        val correctResult = reQuantize(starshipNotes120, 120, 60)
        val oldMs = starshipNotes120[0].durationTicks * msPerTick(120) // 14 * 125 = 1750ms
        val newMs = correctResult[0].durationTicks * msPerTick(60)     // 7 * 250 = 1750ms
        assertEquals("Correct formula preserves duration", oldMs, newMs, 1.0)
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun reQuantize_singleTick_coercedToOne() {
        val oneTickNote = listOf(note(60, 1, 0f))
        val result = reQuantize(oneTickNote, 120, 240)
        // 1 * 240/120 = 2 → not clamped
        assertEquals(2, result[0].durationTicks)

        // Going the other way: 1 * 60/120 = 0.5 → rounds to 1 (coerced)
        val result2 = reQuantize(oneTickNote, 120, 60)
        assertEquals(1, result2[0].durationTicks)
    }

    @Test
    fun reQuantize_pitchesPreserved() {
        val result = reQuantize(starshipNotes120, 120, 60)
        for (i in starshipNotes120.indices) {
            assertEquals(
                "Pitch should be preserved for note $i",
                starshipNotes120[i].midiPitch, result[i].midiPitch
            )
        }
    }

    @Test
    fun reQuantize_timePositionMsPreserved() {
        val result = reQuantize(starshipNotes120, 120, 60)
        for (i in starshipNotes120.indices) {
            assertEquals(
                "TimePositionMs should be preserved for note $i",
                starshipNotes120[i].timePositionMs, result[i].timePositionMs
            )
        }
    }
}
