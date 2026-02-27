package com.notenotes.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notenotes.audio.AudioPlayer
import com.notenotes.data.AppDatabase
import com.notenotes.export.FileExporter
import com.notenotes.export.MusicXmlGenerator
import com.notenotes.model.MelodyIdea
import com.notenotes.model.MusicalNote
import com.notenotes.model.TranscriptionResult
import com.notenotes.model.KeySignature
import com.notenotes.model.TimeSignature
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "NNPreview"

class PreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val dao = AppDatabase.getDatabase(context).melodyDao()
    private val audioPlayer = AudioPlayer()
    private val fileExporter = FileExporter(context)
    private val musicXmlGenerator = MusicXmlGenerator()
    private val gson = Gson()

    private val _idea = MutableStateFlow<MelodyIdea?>(null)
    val idea: StateFlow<MelodyIdea?> = _idea

    private val _musicXml = MutableStateFlow<String?>(null)
    val musicXml: StateFlow<String?> = _musicXml

    val playbackState: StateFlow<AudioPlayer.PlaybackState> = audioPlayer.state

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var currentResult: TranscriptionResult? = null

    fun loadIdea(ideaId: Long) {
        Log.i(TAG, "loadIdea: Loading idea id=$ideaId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val melodyIdea = dao.getIdeaById(ideaId) ?: run {
                    Log.e(TAG, "loadIdea: Idea $ideaId not found in database!")
                    _errorMessage.value = "Idea not found"
                    return@launch
                }
                Log.d(TAG, "loadIdea: Found '${melodyIdea.title}', audio=${melodyIdea.audioFilePath}, xml=${melodyIdea.musicXmlFilePath}")
                _idea.value = melodyIdea

                // Load or regenerate MusicXML
                val xmlFile = melodyIdea.musicXmlFilePath?.let { File(it) }
                if (xmlFile != null && xmlFile.exists()) {
                    Log.d(TAG, "loadIdea: Loading MusicXML from file (${xmlFile.length()} bytes)")
                    _musicXml.value = xmlFile.readText()
                } else if (melodyIdea.notes != null) {
                    Log.d(TAG, "loadIdea: Regenerating MusicXML from stored notes...")
                    // Rebuild from stored notes
                    val notesType = object : TypeToken<List<MusicalNote>>() {}.type
                    val notes: List<MusicalNote> = gson.fromJson(melodyIdea.notes, notesType)
                    Log.d(TAG, "loadIdea: Parsed ${notes.size} notes from JSON")
                    
                    val keySig = parseKeySignature(melodyIdea.keySignature)
                    val timeSig = parseTimeSignature(melodyIdea.timeSignature)

                    val result = TranscriptionResult(
                        notes = notes,
                        keySignature = keySig,
                        timeSignature = timeSig,
                        tempoBpm = melodyIdea.tempoBpm
                    )
                    currentResult = result
                    _musicXml.value = musicXmlGenerator.generateMusicXml(result)
                    Log.d(TAG, "loadIdea: MusicXML regenerated successfully")
                } else {
                    Log.w(TAG, "loadIdea: No XML file and no stored notes!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadIdea: Error loading idea", e)
                _errorMessage.value = "Error loading: ${e.message}"
            }
        }
    }

    fun playVoiceMemo() {
        val audioPath = _idea.value?.audioFilePath ?: run {
            Log.e(TAG, "playVoiceMemo: No audio path")
            return
        }
        val file = File(audioPath)
        if (file.exists()) {
            Log.i(TAG, "playVoiceMemo: Playing ${file.absolutePath} (${file.length()} bytes)")
            audioPlayer.play(file)
        } else {
            Log.e(TAG, "playVoiceMemo: Audio file not found: $audioPath")
            _errorMessage.value = "Audio file not found"
        }
    }

    fun pausePlayback() = audioPlayer.pause()
    fun resumePlayback() = audioPlayer.resume()
    fun stopPlayback() = audioPlayer.stop()

    fun setError(message: String) {
        _errorMessage.value = message
    }

    fun shareMidi(context: Context) {
        val result = buildResult() ?: return
        val title = _idea.value?.title ?: "melody"
        val intent = fileExporter.shareMidi(result, title)
        context.startActivity(Intent.createChooser(intent, "Share MIDI"))
    }

    fun shareMusicXml(context: Context) {
        val result = buildResult() ?: return
        val title = _idea.value?.title ?: "melody"
        val intent = fileExporter.shareMusicXml(result, title)
        context.startActivity(Intent.createChooser(intent, "Share MusicXML"))
    }

    fun shareAll(context: Context) {
        val result = buildResult() ?: return
        val title = _idea.value?.title ?: "melody"
        val intent = fileExporter.shareAll(result, title)
        context.startActivity(Intent.createChooser(intent, "Share Files"))
    }

    private fun buildResult(): TranscriptionResult? {
        currentResult?.let { return it }
        
        val melodyIdea = _idea.value ?: return null
        val notesJson = melodyIdea.notes ?: return null
        
        val notesType = object : TypeToken<List<MusicalNote>>() {}.type
        val notes: List<MusicalNote> = gson.fromJson(notesJson, notesType)
        
        val result = TranscriptionResult(
            notes = notes,
            keySignature = parseKeySignature(melodyIdea.keySignature),
            timeSignature = parseTimeSignature(melodyIdea.timeSignature),
            tempoBpm = melodyIdea.tempoBpm
        )
        currentResult = result
        return result
    }

    private fun parseKeySignature(str: String?): KeySignature {
        if (str == null) return KeySignature.C_MAJOR
        return KeySignature.ALL_KEYS.find { it.toString() == str } ?: KeySignature.C_MAJOR
    }

    private fun parseTimeSignature(str: String?): TimeSignature {
        if (str == null) return TimeSignature.FOUR_FOUR
        val parts = str.split("/")
        if (parts.size == 2) {
            val beats = parts[0].toIntOrNull() ?: return TimeSignature.FOUR_FOUR
            val beatType = parts[1].toIntOrNull() ?: return TimeSignature.FOUR_FOUR
            return TimeSignature(beats, beatType)
        }
        return TimeSignature.FOUR_FOUR
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
