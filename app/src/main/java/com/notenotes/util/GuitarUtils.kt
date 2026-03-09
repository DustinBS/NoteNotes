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
        GuitarString(4, "D",     50, "4 D", 0xFF_E6A817),  // Amber/Gold
        GuitarString(3, "G",     55, "3 G", 0xFF_43A047),  // Green
        GuitarString(2, "B",     59, "2 B", 0xFF_1E88E5),  // Blue
        GuitarString(1, "High E", 64, "1 E", 0xFF_8E24AA)  // Purple
    )

    const val MAX_FRET = 24

    /**
     * Convert a guitar string + fret to a MIDI note number.
     * @param stringIndex 0-based index into STRINGS (0 = Low E / string 6)
     * @param fret fret number 0–24
     * @return MIDI note number
     */
    fun toMidi(stringIndex: Int, fret: Int): Int {
        require(stringIndex in 0..5) { "stringIndex must be 0–5" }
        require(fret in 0..MAX_FRET) { "fret must be 0–$MAX_FRET" }
        return STRINGS[stringIndex].openMidi + fret
    }

    /**
     * Try to find the best guitar position for a given MIDI note.
     * Returns (stringIndex, fret) or null if not playable.
     */
    fun fromMidi(midiNote: Int): Pair<Int, Int>? {
        // Prefer lower frets on thicker strings
        for (i in STRINGS.indices) {
            val fret = midiNote - STRINGS[i].openMidi
            if (fret in 0..MAX_FRET) return Pair(i, fret)
        }
        return null
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
