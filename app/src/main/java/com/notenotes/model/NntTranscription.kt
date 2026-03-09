package com.notenotes.model

/**
 * NoteNotes Transcription (.nnt) file format.
 *
 * A lightweight JSON format that preserves the full fidelity of a
 * MusicalNote list including timePositionMs, isManual, velocity,
 * chordName, guitar tab data, and all other fields that MusicXML
 * cannot round-trip.
 *
 * Designed for the 2-component workflow: export a transcription from
 * one idea and import it onto another idea with different audio.
 */
data class NntTranscription(
    val formatVersion: Int = 1,
    val instrument: String = "piano",
    val tempoBpm: Int = 120,
    val keySignature: String? = null,
    val timeSignature: String? = null,
    /** Audio duration in milliseconds — preserved across export/import to avoid
     *  duration-mismatch warnings caused by minor rounding differences. */
    val durationMs: Long? = null,
    val notes: List<MusicalNote> = emptyList()
) {
    companion object {
        const val FILE_EXTENSION = "nnt"
        const val MIME_TYPE = "application/json"
    }
}
