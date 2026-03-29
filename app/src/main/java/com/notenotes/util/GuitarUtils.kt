package com.notenotes.util

/**
 * Guitar-specific utilities for string/fret to MIDI note conversion.
 * Standard tuning: E2 A2 D3 G3 B3 E4 (strings 6→1, low→high)
 */
object GuitarUtils {

    /** Guitar string definitions, indexed 0–5 (string 6 low E to string 1 high E). */
    data class GuitarString(
        val number: Int,       // 1-6 (1 = thinnest/high E, 6 = thickest/low E)
        val name: String,      // "E2", "A2", etc.
        val openMidi: Int,     // MIDI note for open string (fret 0)
        val label: String,     // Short display label
        val colorArgb: Long    // Color for this string (ARGB hex)
    )

    val STRINGS = listOf(
        GuitarString(6, "Low E", 40, "6 E", 0xFF_E53935),  // Red
        GuitarString(5, "A",     45, "5 A", 0xFF_FB8C00),  // Orange
        GuitarString(4, "D",     50, "4 D", 0xFF_FFEA00),  // Neon/Highlighter Yellow
        GuitarString(3, "G",     55, "3 G", 0xFF_43A047),  // Green
        GuitarString(2, "B",     59, "2 B", 0xFF_1E88E5),  // Blue
        GuitarString(1, "High E", 64, "1 E", 0xFF_8E24AA)  // Purple
    )

    const val MAX_FRET = 24

    /**
     * Convert a guitar string + fret to a MIDI note number.
     * Accepts either a 0-based index into `STRINGS` (0 = Low E / string 6)
     * or a human 1-based string number (1 = High E). This keeps callers
     * flexible while the canonical external representation uses 1..N.
     * @param fret fret number 0–24
     * @return MIDI note number
     */
    fun toMidi(stringIndex: Int, fret: Int): Int {
        // Prefer interpreting 1..STRINGS.size as human 1-based numbers (1 = high E)
        // to avoid ambiguity with zero-based indices 0..(n-1).
        val idx = when {
            stringIndex in 1..STRINGS.size -> STRINGS.size - stringIndex
            stringIndex in STRINGS.indices -> stringIndex
            else -> throw IllegalArgumentException("stringIndex must be 0..${STRINGS.size - 1} or 1..${STRINGS.size}")
        }
        require(fret in 0..MAX_FRET) { "fret must be 0–$MAX_FRET" }
        return STRINGS[idx].openMidi + fret
    }

    /**
     * Try to find the best guitar position for a given MIDI note.
     * Returns a pair `(stringNumber, fret)` where `stringNumber` is human 1-based
     * (1 = High E, 6 = Low E). Returns null if not playable.
     */
    fun fromMidi(midiNote: Int): Pair<Int, Int>? {
        // Prefer lower frets on thicker strings
        for (i in STRINGS.indices) {
            val fret = midiNote - STRINGS[i].openMidi
            if (fret in 0..MAX_FRET) return Pair(STRINGS.size - i, fret)
        }
        return null
    }

    /**
     * Convert a raw value (either 0-based index or 1-based human string number)
     * to the 0-based index used to access `STRINGS`.
     * Returns null if the raw value is out of range.
     */
    fun rawToIndex(raw: Int): Int? {
        // Prefer human 1-based mapping when ambiguous (1..size overlaps with 0..size-1)
        return when {
            raw in 1..STRINGS.size -> STRINGS.size - raw
            raw in STRINGS.indices -> raw
            else -> null
        }
    }

    /**
     * Convert a 0-based index into a human 1-based string number.
     */
    fun indexToHuman(index: Int): Int {
        require(index in STRINGS.indices)
        return STRINGS.size - index
    }

    /**
     * Convert a human 1-based string number into a 0-based index.
     */
    fun humanToIndex(human: Int): Int? {
        return if (human in 1..STRINGS.size) STRINGS.size - human else null
    }

    /**
     * Get the note name for a string + fret combination.
     */
    fun noteName(stringIndex: Int, fret: Int): String {
        return PitchUtils.midiToNoteName(toMidi(stringIndex, fret))
    }

    /** Standard note durations for the editor. */
    data class NoteDuration(
        val label: String,
        val type: String,       // "whole", "half", "quarter", "eighth", "16th"
        val ticks: Int,         // duration in ticks (divisions=4 per quarter)
        val symbol: String      // Unicode music symbol
    )

    val DURATIONS = listOf(
        NoteDuration("Whole",    "whole",   16, "𝅝"),
        NoteDuration("Half",     "half",     8, "𝅗𝅥"),
        NoteDuration("Quarter",  "quarter",  4, "𝅘𝅥"),
        NoteDuration("Eighth",   "eighth",   2, "𝅘𝅥𝅮"),
        NoteDuration("16th",     "16th",     1, "𝅘𝅥𝅯")
    )
}
