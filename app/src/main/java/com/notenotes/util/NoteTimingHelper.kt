package com.notenotes.util

import com.notenotes.model.MusicalNote

/**
 * Centralized note timing calculations.
 *
 * Extracts the time-mapping logic that was previously duplicated 6 times
 * in PreviewViewModel (getCurrentNoteIndex, getPlaybackFractionInNote,
 * isCursorInsideNote, splitNoteAtCursor, recalculateNoteDurations, addNote).
 *
 * Key concept: each note's start time is either:
 * - Its explicit `timePositionMs` (if `isManual == true` and `timePositionMs != null`)
 * - Or the cumulative end time of all preceding notes (auto-transcribed notes)
 */
object NoteTimingHelper {

    /** Duration of one tick in milliseconds at the given BPM (divisions=4). */
    fun tickDurationMs(tempoBpm: Int): Float {
        val beatDurationMs = 60000f / tempoBpm
        return beatDurationMs / 4f  // divisions = 4
    }

    /**
     * Compute the start time of a note given its cumulative position.
     * Manual notes with a timePositionMs use that; otherwise uses cumulative.
     */
    fun computeNoteStartMs(note: MusicalNote, cumulativeMs: Float): Float {
        return if (note.isManual && note.timePositionMs != null) {
            note.timePositionMs
        } else {
            cumulativeMs
        }
    }

    /**
     * Timing info for a single note.
     */
    data class NoteTiming(
        val index: Int,
        val startMs: Float,
        val endMs: Float,
        val durationMs: Float
    )

    /**
     * Compute start/end/duration for every note in the list.
     */
    fun computeNoteTimings(notes: List<MusicalNote>, tempoBpm: Int): List<NoteTiming> {
        val tickMs = tickDurationMs(tempoBpm)
        var cumulativeMs = 0f
        return notes.mapIndexed { index, note ->
            val startMs = computeNoteStartMs(note, cumulativeMs)
            val durationMs = note.durationTicks * tickMs
            val endMs = startMs + durationMs
            cumulativeMs = endMs
            NoteTiming(index, startMs, endMs, durationMs)
        }
    }

    /**
     * Find which note is playing at the given time (in ms).
     * Returns 0 for empty lists, last index if past all notes.
     */
    fun getCurrentNoteIndex(notes: List<MusicalNote>, currentTimeMs: Float, tempoBpm: Int): Int {
        if (notes.isEmpty()) return 0
        val tickMs = tickDurationMs(tempoBpm)
        var cumulativeMs = 0f

        for ((index, note) in notes.withIndex()) {
            val noteStartMs = computeNoteStartMs(note, cumulativeMs)
            val noteDurationMs = note.durationTicks * tickMs
            val noteEndMs = noteStartMs + noteDurationMs

            if (currentTimeMs < noteEndMs) {
                return index
            }
            cumulativeMs = noteEndMs
        }
        return notes.size - 1
    }

    /**
     * Get 0..1 fraction indicating how far through the current note playback is.
     */
    fun getPlaybackFractionInNote(notes: List<MusicalNote>, currentTimeMs: Float, tempoBpm: Int): Float {
        if (notes.isEmpty()) return 0f
        val tickMs = tickDurationMs(tempoBpm)
        var cumulativeMs = 0f

        for (note in notes) {
            val noteStartMs = computeNoteStartMs(note, cumulativeMs)
            val noteDurationMs = note.durationTicks * tickMs
            val noteEndMs = noteStartMs + noteDurationMs

            if (currentTimeMs < noteEndMs) {
                return if (noteDurationMs > 0) {
                    ((currentTimeMs - noteStartMs) / noteDurationMs).coerceIn(0f, 1f)
                } else 0f
            }
            cumulativeMs = noteEndMs
        }
        return 1f
    }

    /**
     * Check if a cursor time is inside any note (for showing split button).
     */
    fun isCursorInsideNote(notes: List<MusicalNote>, cursorTimeMs: Float, tempoBpm: Int): Boolean {
        if (notes.isEmpty()) return false
        val tickMs = tickDurationMs(tempoBpm)
        var cumulativeMs = 0f

        for (note in notes) {
            val noteStartMs = computeNoteStartMs(note, cumulativeMs)
            val noteDurationMs = note.durationTicks * tickMs
            val noteEndMs = noteStartMs + noteDurationMs

            if (cursorTimeMs >= noteStartMs && cursorTimeMs <= noteEndMs) {
                return true
            }
            cumulativeMs = noteEndMs
        }
        return false
    }

    /**
     * Map tick count to the closest note type name.
     */
    fun ticksToType(ticks: Int): String = when {
        ticks >= 16 -> "whole"
        ticks >= 8  -> "half"
        ticks >= 4  -> "quarter"
        ticks >= 2  -> "eighth"
        else        -> "16th"
    }
}
