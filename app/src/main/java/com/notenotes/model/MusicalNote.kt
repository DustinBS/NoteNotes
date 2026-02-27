package com.notenotes.model

data class MusicalNote(
    val midiPitch: Int,          // 0-127 (primary/lowest pitch)
    val durationTicks: Int,      // in divisions (e.g., quarter = 1 division)
    val type: String,            // "whole", "half", "quarter", "eighth", "16th"
    val dotted: Boolean = false,
    val isRest: Boolean = false,
    val tiedToNext: Boolean = false,
    val velocity: Int = 80,      // 0-127, default mezzo-forte
    val chordPitches: List<Int> = emptyList(), // additional MIDI pitches for chord notes (excludes midiPitch)
    val chordName: String? = null // e.g., "Am", "G7" — null for single notes
) {
    /** True if this note is part of a chord (multiple simultaneous pitches). */
    val isChord: Boolean get() = chordPitches.isNotEmpty()

    /** All MIDI pitches in this note/chord, sorted ascending. */
    val allPitches: List<Int> get() = if (chordPitches.isEmpty()) listOf(midiPitch)
        else (listOf(midiPitch) + chordPitches).sorted()
}
