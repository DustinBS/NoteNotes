package com.notenotes.model

/**
 * Result from pitch detection for a single audio frame.
 */
data class PitchDetectionResult(
    val frequencyHz: Double,  // detected frequency in Hz, -1.0 if no pitch
    val confidence: Double,   // 0.0 to 1.0
    val isPitched: Boolean,   // true if a reliable pitch was detected
    val timeSeconds: Double   // time position of this frame in the audio
)

/**
 * A detected note with onset time and duration.
 * Supports both monophonic and polyphonic (chord) events.
 */
data class DetectedNote(
    val midiNote: Int,          // MIDI note number 0-127 (primary/bass note)
    val frequencyHz: Double,    // average frequency in Hz (of primary note)
    val onsetSeconds: Double,   // when the note starts
    val durationSeconds: Double, // how long it lasts
    val confidence: Double,     // average confidence
    val chordMidiNotes: List<Int> = emptyList(), // additional chord pitches (excludes midiNote)
    val chordName: String? = null // identified chord name, e.g., "Am", "G7"
) {
    val isChord: Boolean get() = chordMidiNotes.isNotEmpty()
    val allMidiNotes: List<Int> get() = if (chordMidiNotes.isEmpty()) listOf(midiNote)
        else (listOf(midiNote) + chordMidiNotes).sorted()
}
