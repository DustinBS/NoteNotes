package com.notenotes.util

import com.notenotes.model.MusicalNote
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for NoteTimingHelper — the extracted time-mapping logic that was
 * previously duplicated 6 times in PreviewViewModel.
 *
 * These tests lock down the exact current behavior so the refactoring is safe.
 *
 * Run with: gradlew testDebugUnitTest --tests "*NoteTimingHelperTest*"
 */
class NoteTimingHelperTest {

    // ══════════════════════════════════════════════════════════════════════
    // computeNoteStartMs — the core time-position calculation
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun computeNoteStartMs_autoNote_usesCumulativeTime() {
        val note = quarterNote(60)
        val startMs = NoteTimingHelper.computeNoteStartMs(note, cumulativeMs = 500f)
        assertEquals(500f, startMs, 0.01f)
    }

    @Test
    fun computeNoteStartMs_manualNoteWithPosition_usesTimePositionMs() {
        val note = manualNote(60, timeMs = 1234f)
        val startMs = NoteTimingHelper.computeNoteStartMs(note, cumulativeMs = 500f)
        assertEquals(1234f, startMs, 0.01f)
    }

    @Test
    fun computeNoteStartMs_manualNoteWithoutPosition_fallsToCumulative() {
        // isManual=true but timePositionMs=null → falls back to cumulative
        val note = MusicalNote(pitches = listOf(60), durationTicks = 4, type = "quarter", isManual = true)
        val startMs = NoteTimingHelper.computeNoteStartMs(note, cumulativeMs = 750f)
        assertEquals(750f, startMs, 0.01f)
    }

    @Test
    fun computeNoteStartMs_nonManualWithPositionSet_usesPosition() {
        // isManual=false but timePositionMs set → uses timePositionMs (not cumulative).
        // computeNoteStartMs uses timePositionMs whenever it is set, regardless
        // of isManual, so the sheet-music cursor stays in sync with the Notes tab.
        val note = MusicalNote(pitches = listOf(60), durationTicks = 4, type = "quarter",
            isManual = false, timePositionMs = 999f)
        val startMs = NoteTimingHelper.computeNoteStartMs(note, cumulativeMs = 200f)
        assertEquals(999f, startMs, 0.01f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // tickDurationMs — converts BPM to tick duration
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun tickDurationMs_120bpm_is125ms() {
        // At 120 BPM: beat = 500ms, tick = 500/4 = 125ms
        assertEquals(125f, NoteTimingHelper.tickDurationMs(120), 0.01f)
    }

    @Test
    fun tickDurationMs_60bpm_is250ms() {
        // At 60 BPM: beat = 1000ms, tick = 1000/4 = 250ms
        assertEquals(250f, NoteTimingHelper.tickDurationMs(60), 0.01f)
    }

    @Test
    fun tickDurationMs_240bpm_is62_5ms() {
        assertEquals(62.5f, NoteTimingHelper.tickDurationMs(240), 0.01f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // getCurrentNoteIndex — which note is playing at a given time
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun getCurrentNoteIndex_emptyNotes_returnsZero() {
        assertEquals(0, NoteTimingHelper.getCurrentNoteIndex(emptyList(), 500f, 120))
    }

    @Test
    fun getCurrentNoteIndex_beforeFirstNote_returnsZero() {
        val notes = listOf(quarterNote(60), quarterNote(62))
        assertEquals(0, NoteTimingHelper.getCurrentNoteIndex(notes, 0f, 120))
    }

    @Test
    fun getCurrentNoteIndex_inFirstNote_returnsZero() {
        val notes = listOf(quarterNote(60), quarterNote(62))
        // tick = 125ms, first note duration = 4 ticks = 500ms
        assertEquals(0, NoteTimingHelper.getCurrentNoteIndex(notes, 100f, 120))
    }

    @Test
    fun getCurrentNoteIndex_inSecondNote_returnsOne() {
        val notes = listOf(quarterNote(60), quarterNote(62))
        // first note: 0-500ms, second note: 500-1000ms
        assertEquals(1, NoteTimingHelper.getCurrentNoteIndex(notes, 600f, 120))
    }

    @Test
    fun getCurrentNoteIndex_pastAllNotes_returnsLastIndex() {
        val notes = listOf(quarterNote(60), quarterNote(62))
        // Total duration = 1000ms, past that → last index
        assertEquals(1, NoteTimingHelper.getCurrentNoteIndex(notes, 2000f, 120))
    }

    @Test
    fun getCurrentNoteIndex_mixedAutoAndManual_respectsPositions() {
        val notes = listOf(
            quarterNote(60), // auto: starts at 0ms
            manualNote(64, timeMs = 1000f) // manual: starts at 1000ms
        )
        // At 600ms: auto note ends at 500ms, gap until manual at 1000ms
        // Behavior: still inside first note's end, or past it?
        // Current behavior: first note ends at 500ms (4 ticks * 125ms)
        // 600ms > 500ms → move to next note
        assertEquals(1, NoteTimingHelper.getCurrentNoteIndex(notes, 600f, 120))
    }

    @Test
    fun getCurrentNoteIndex_atExactBoundary_returnsNextNote() {
        val notes = listOf(quarterNote(60), quarterNote(62))
        // Exactly at 500ms (end of first note)
        // currentTimeMs < noteEndMs → 500 < 500 is false → moves to next
        assertEquals(1, NoteTimingHelper.getCurrentNoteIndex(notes, 500f, 120))
    }

    // ══════════════════════════════════════════════════════════════════════
    // getPlaybackFractionInNote — how far through the current note
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun getPlaybackFractionInNote_emptyNotes_returnsZero() {
        assertEquals(0f, NoteTimingHelper.getPlaybackFractionInNote(emptyList(), 500f, 120), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_startOfFirstNote_returnsZero() {
        val notes = listOf(quarterNote(60))
        assertEquals(0f, NoteTimingHelper.getPlaybackFractionInNote(notes, 0f, 120), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_halfwayThroughNote_returnsHalf() {
        val notes = listOf(quarterNote(60)) // 500ms at 120bpm
        assertEquals(0.5f, NoteTimingHelper.getPlaybackFractionInNote(notes, 250f, 120), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_pastAllNotes_returnsOne() {
        val notes = listOf(quarterNote(60))
        assertEquals(1f, NoteTimingHelper.getPlaybackFractionInNote(notes, 2000f, 120), 0.01f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // isCursorInsideNote — used for split button visibility
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun isCursorInsideNote_cursorInNote_returnsTrue() {
        val notes = listOf(quarterNote(60)) // 0-500ms at 120bpm
        assertTrue(NoteTimingHelper.isCursorInsideNote(notes, 250f, 120))
    }

    @Test
    fun isCursorInsideNote_cursorAtStart_returnsTrue() {
        val notes = listOf(quarterNote(60))
        assertTrue(NoteTimingHelper.isCursorInsideNote(notes, 0f, 120))
    }

    @Test
    fun isCursorInsideNote_cursorPastAll_returnsFalse() {
        val notes = listOf(quarterNote(60))
        assertFalse(NoteTimingHelper.isCursorInsideNote(notes, 2000f, 120))
    }

    @Test
    fun isCursorInsideNote_emptyNotes_returnsFalse() {
        assertFalse(NoteTimingHelper.isCursorInsideNote(emptyList(), 250f, 120))
    }

    // ══════════════════════════════════════════════════════════════════════
    // computeNoteTimings — bulk computation for a list of notes
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun computeNoteTimings_threeAutoNotes_sequentialTiming() {
        val notes = listOf(quarterNote(60), quarterNote(62), quarterNote(64))
        val timings = NoteTimingHelper.computeNoteTimings(notes, 120)

        assertEquals(3, timings.size)
        // At 120 BPM: tick = 125ms, quarter = 500ms
        assertEquals(0f, timings[0].startMs, 0.01f)
        assertEquals(500f, timings[0].endMs, 0.01f)
        assertEquals(500f, timings[1].startMs, 0.01f)
        assertEquals(1000f, timings[1].endMs, 0.01f)
        assertEquals(1000f, timings[2].startMs, 0.01f)
        assertEquals(1500f, timings[2].endMs, 0.01f)
    }

    @Test
    fun computeNoteTimings_mixedDurations() {
        val notes = listOf(
            MusicalNote(pitches = listOf(60), durationTicks = 8, type = "half"),    // 1000ms
            MusicalNote(pitches = listOf(62), durationTicks = 2, type = "eighth")   // 250ms
        )
        val timings = NoteTimingHelper.computeNoteTimings(notes, 120)

        assertEquals(0f, timings[0].startMs, 0.01f)
        assertEquals(1000f, timings[0].endMs, 0.01f)
        assertEquals(1000f, timings[1].startMs, 0.01f)
        assertEquals(1250f, timings[1].endMs, 0.01f)
    }

    @Test
    fun computeNoteTimings_manualNoteOverridesPosition() {
        val notes = listOf(
            quarterNote(60),  // auto: 0-500ms
            manualNote(64, timeMs = 2000f) // manual: 2000-2500ms
        )
        val timings = NoteTimingHelper.computeNoteTimings(notes, 120)

        assertEquals(0f, timings[0].startMs, 0.01f)
        assertEquals(500f, timings[0].endMs, 0.01f)
        assertEquals(2000f, timings[1].startMs, 0.01f)
        assertEquals(2500f, timings[1].endMs, 0.01f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ticksToType — maps tick count to note type name
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun ticksToType_standard() {
        assertEquals("whole", NoteTimingHelper.ticksToType(16))
        assertEquals("half", NoteTimingHelper.ticksToType(8))
        assertEquals("quarter", NoteTimingHelper.ticksToType(4))
        assertEquals("eighth", NoteTimingHelper.ticksToType(2))
        assertEquals("16th", NoteTimingHelper.ticksToType(1))
    }

    @Test
    fun ticksToType_nonStandard_roundsDown() {
        assertEquals("half", NoteTimingHelper.ticksToType(12))    // >= 8
        assertEquals("quarter", NoteTimingHelper.ticksToType(6))  // >= 4
        assertEquals("eighth", NoteTimingHelper.ticksToType(3))   // >= 2
        assertEquals("whole", NoteTimingHelper.ticksToType(20))   // >= 16
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun quarterNote(midi: Int) = MusicalNote(
        pitches = listOf(midi),
        durationTicks = 4,
        type = "quarter"
    )

    private fun manualNote(midi: Int, timeMs: Float) = MusicalNote(
        pitches = listOf(midi),
        durationTicks = 4,
        type = "quarter",
        isManual = true,
        timePositionMs = timeMs
    )
}
