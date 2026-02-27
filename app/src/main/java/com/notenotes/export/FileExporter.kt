package com.notenotes.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.notenotes.model.TranscriptionResult
import java.io.File

/**
 * Handles exporting and sharing of MIDI and MusicXML files.
 * Uses Android FileProvider for secure file sharing.
 */
class FileExporter(private val context: Context) {

    private val midiWriter = MidiWriter()
    private val musicXmlGenerator = MusicXmlGenerator()

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
        musicXmlGenerator.writeToFile(result, file, partName)
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
     * Share both MIDI and MusicXML files.
     */
    fun shareAll(result: TranscriptionResult, filename: String): Intent {
        val midiFile = exportMidi(result, filename)
        val xmlFile = exportMusicXml(result, filename)

        val midiUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", midiFile)
        val xmlUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", xmlFile)

        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(midiUri, xmlUri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
