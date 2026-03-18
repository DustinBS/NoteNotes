package com.notenotes.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.notenotes.export.MusicXmlSanitizer
import com.notenotes.model.TranscriptionResult
import java.io.File

/**
 * Handles exporting and sharing of MIDI and MusicXML files.
 * Uses Android FileProvider for secure file sharing.
 */
class FileExporter(
    private val context: Context,
    private val midiWriter: MidiWriter = MidiWriter(),
    private val musicXmlGenerator: MusicXmlGenerator = MusicXmlGenerator()
) {

    /**
     * Export transcription to MIDI file.
     * @return the saved file
     */
    fun exportMidi(result: TranscriptionResult, filename: String): File {
        val dir = File(context.filesDir, "exports")
        dir.mkdirs()
        val file = File(dir, "$filename.mid")
        midiWriter.writeToFile(result, file)
        return file
    }

    /**
     * Export transcription to MusicXML file.
     * @return the saved file
     */
    fun exportMusicXml(result: TranscriptionResult, filename: String, partName: String = "Melody"): File {
        val dir = File(context.filesDir, "exports")
        dir.mkdirs()
        val file = File(dir, "$filename.musicxml")
        // Generate + sanitize so exported XML is always valid for alphaTab/other readers
        val xml = MusicXmlSanitizer.sanitize(
            musicXmlGenerator.generateMusicXml(result, partName)
        )
        file.writeText(xml)
        return file
    }

    /**
     * Create a share intent for a file.
     */
    fun createShareIntent(file: File, mimeType: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Share a MIDI file.
     */
    fun shareMidi(result: TranscriptionResult, filename: String): Intent {
        val file = exportMidi(result, filename)
        return createShareIntent(file, "audio/midi")
    }

    /**
     * Share a MusicXML file.
     */
    fun shareMusicXml(result: TranscriptionResult, filename: String): Intent {
        val file = exportMusicXml(result, filename)
        return createShareIntent(file, "application/xml")
    }

    /**
     * Share an audio file, renamed to match the idea title.
     */
    fun shareAudioFile(audioFile: File, title: String? = null): Intent {
        // Copy to exports dir so FileProvider can access it
        val dir = File(context.filesDir, "exports")
        dir.mkdirs()
        val ext = audioFile.extension.ifEmpty { "wav" }
        val fileName = if (!title.isNullOrBlank()) "$title.$ext" else audioFile.name
        val dest = File(dir, fileName)
        audioFile.copyTo(dest, overwrite = true)
        val mime = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "audio/wav"
        }
        return createShareIntent(dest, mime)
    }

    /**
     * Share MIDI, MusicXML transcription files (plus optional audio).
     */
    fun shareAll(result: TranscriptionResult, filename: String, audioFile: File? = null): Intent {
        val midiFile = exportMidi(result, filename)
        val xmlFile = exportMusicXml(result, filename)

        val uris = arrayListOf(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", midiFile),
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", xmlFile)
        )

        // Include the audio file if available
        if (audioFile != null && audioFile.exists()) {
            val dir = File(context.filesDir, "exports")
            dir.mkdirs()
            val ext = audioFile.extension.ifEmpty { "wav" }
            val audioName = "$filename.$ext"
            val dest = File(dir, audioName)
            audioFile.copyTo(dest, overwrite = true)
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest))
        }

        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
