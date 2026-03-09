package com.notenotes.export

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.notenotes.model.MusicalNote
import com.notenotes.model.NntTranscription
import java.io.File
import java.io.InputStream

/**
 * Read / write helpers for the NoteNotes Transcription (.nnt) format.
 *
 * The file is a pretty-printed JSON containing metadata (instrument,
 * tempo, key/time signature) plus the full [MusicalNote] list — every
 * field that MusicXML would lose on a round-trip is preserved.
 */
object NntFile {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Serialize an [NntTranscription] to a JSON string. */
    fun toJson(transcription: NntTranscription): String = gson.toJson(transcription)

    /** Write an [NntTranscription] to a file. */
    fun write(transcription: NntTranscription, file: File) {
        file.writeText(toJson(transcription))
    }

    /** Read an [NntTranscription] from a file. */
    fun read(file: File): NntTranscription =
        parse(file.readText())

    /** Read an [NntTranscription] from an [InputStream]. */
    fun read(inputStream: InputStream): NntTranscription =
        parse(inputStream.reader().use { it.readText() })

    /** Parse an [NntTranscription] from a JSON string. */
    fun parse(json: String): NntTranscription {
        val transcription = gson.fromJson(json, NntTranscription::class.java)
        // Ensure notes go through sanitization (Gson can null out default lists)
        return transcription.copy(
            notes = MusicalNote.sanitizeList(transcription.notes)
        )
    }

    /**
     * Build an [NntTranscription] from a raw notes-JSON string (the
     * format stored in MelodyIdea.notes) plus metadata fields.
     */
    fun fromNotesJson(
        notesJson: String,
        instrument: String = "piano",
        tempoBpm: Int = 120,
        keySignature: String? = null,
        timeSignature: String? = null
    ): NntTranscription {
        val notesType = object : TypeToken<List<MusicalNote>>() {}.type
        val notes: List<MusicalNote> = MusicalNote.sanitizeList(
            gson.fromJson(notesJson, notesType)
        )
        return NntTranscription(
            instrument = instrument,
            tempoBpm = tempoBpm,
            keySignature = keySignature,
            timeSignature = timeSignature,
            notes = notes
        )
    }
}
