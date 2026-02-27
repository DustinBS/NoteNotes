package com.notenotes.model

import com.notenotes.util.GuitarUtils

data class MusicalNote(
    val midiPitch: Int,          // 0-127 (primary/lowest pitch)
    val durationTicks: Int,      // in divisions (e.g., quarter = 1 division)
    val type: String,            // "whole", "half", "quarter", "eighth", "16th"
    val dotted: Boolean = false,
    val isRest: Boolean = false,
    val tiedToNext: Boolean = false,
    val velocity: Int = 80,      // 0-127, default mezzo-forte
    val chordPitches: List<Int> = emptyList(), // additional MIDI pitches for chord notes (excludes midiPitch)
    val chordStringFrets: List<Pair<Int, Int>> = emptyList(), // parallel to chordPitches: (guitarString, guitarFret) per chord note
    val chordName: String? = null, // e.g., "Am", "G7" — null for single notes
    // Guitar tablature — hand-crafted ground truth (only set for manually added notes)
    val guitarString: Int? = null,  // 0-based index (0=Low E/6th, 5=High E/1st), null for auto-transcribed
    val guitarFret: Int? = null,    // fret number 0–24, null for auto-transcribed
    val isManual: Boolean = false,  // true if manually annotated (guaranteed correct)
    val timePositionMs: Float? = null // precise time position in audio (ms), ground truth for manual notes
) {
    /** True if this note is part of a chord (multiple simultaneous pitches). */
    val isChord: Boolean get() = (chordPitches ?: emptyList()).isNotEmpty()

    /** All MIDI pitches in this note/chord, sorted ascending. */
    val allPitches: List<Int> get() {
        val cp = chordPitches ?: emptyList()
        return if (cp.isEmpty()) listOf(midiPitch)
        else (listOf(midiPitch) + cp).sorted()
    }

    /** True if this note has guitar tablature information. */
    val hasTab: Boolean get() = guitarString != null && guitarFret != null

    /** True if this chord has multiple notes assigned to the same guitar string. */
    val hasDuplicateStrings: Boolean get() {
        if (!isChord) return false
        val csf = chordStringFrets ?: emptyList()
        val allStrings = mutableListOf(guitarString ?: 0)
        csf.forEach { allStrings.add(it.first) }
        return allStrings.size != allStrings.toSet().size
    }

    /** Null-safe accessor for chordStringFrets (Gson can set it to null). */
    val safeChordStringFrets: List<Pair<Int, Int>> get() = chordStringFrets ?: emptyList()

    /**
     * Sanitize this note after Gson deserialization.
     * - Ensures chordStringFrets is non-null
     * - Derives guitarString/guitarFret from MIDI if missing
     * - Populates missing chordStringFrets from chord MIDI pitches
     */
    fun sanitized(): MusicalNote {
        val cp = chordPitches ?: emptyList()
        val csf = chordStringFrets ?: emptyList()

        // Derive primary note's string/fret if missing
        val derivedString: Int?
        val derivedFret: Int?
        if (guitarString == null || guitarFret == null) {
            val pos = if (!isRest) GuitarUtils.fromMidi(midiPitch) else null
            derivedString = pos?.first ?: guitarString
            derivedFret = pos?.second ?: guitarFret
        } else {
            derivedString = guitarString
            derivedFret = guitarFret
        }

        // Ensure chordStringFrets has entries for all chordPitches
        val fixedCsf = if (csf.size < cp.size) {
            cp.mapIndexed { i, pitch ->
                csf.getOrNull(i) ?: (GuitarUtils.fromMidi(pitch) ?: Pair(0, 0))
            }
        } else {
            csf
        }

        return copy(
            chordPitches = cp,
            chordStringFrets = fixedCsf,
            guitarString = derivedString,
            guitarFret = derivedFret
        )
    }

    companion object {
        /** Sanitize a list of notes after Gson deserialization. */
        fun sanitizeList(notes: List<MusicalNote>): List<MusicalNote> =
            notes.map { it.sanitized() }
    }
}
