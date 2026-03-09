package com.notenotes.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notenotes.audio.AudioRecorder
import com.notenotes.audio.WavWriter
import com.notenotes.data.AppDatabase
import com.notenotes.export.MidiWriter
import com.notenotes.export.MusicXmlGenerator
import com.notenotes.model.InstrumentProfile
import com.notenotes.model.MelodyIdea
import com.notenotes.model.TranscriptionResult
import com.notenotes.processing.TranscriptionPipeline
import com.notenotes.util.TranspositionUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.os.Environment

private const val TAG = "NNRecord"
private const val PREFS_NAME = "notenotes_settings"
private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"

class RecordViewModel(application: Application) : AndroidViewModel(application) {

    enum class UiState {
        IDLE, RECORDING, PROCESSING, DONE, ERROR
    }

    private val context = application.applicationContext
    private val audioRecorder = AudioRecorder(context)
    private val pipeline = TranscriptionPipeline()
    private val midiWriter = MidiWriter()
    private val musicXmlGenerator = MusicXmlGenerator()
    private val dao = AppDatabase.getDatabase(context).melodyDao()
    private val gson = Gson()

    private val _uiState = MutableStateFlow(UiState.IDLE)
    val uiState: StateFlow<UiState> = _uiState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _transcriptionResult = MutableStateFlow<TranscriptionResult?>(null)
    val transcriptionResult: StateFlow<TranscriptionResult?> = _transcriptionResult

    private val _savedIdeaId = MutableStateFlow<Long?>(null)
    val savedIdeaId: StateFlow<Long?> = _savedIdeaId

    private val _selectedInstrument = MutableStateFlow(InstrumentProfile.GUITAR)
    val selectedInstrument: StateFlow<InstrumentProfile> = _selectedInstrument

    private val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val _autoTranscribe = MutableStateFlow(prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true))
    val autoTranscribe: StateFlow<Boolean> = _autoTranscribe

    // Listen for SharedPreferences changes (e.g. from SettingsScreen)
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_AUTO_TRANSCRIBE) {
            _autoTranscribe.value = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private val _recordingDuration = MutableStateFlow(0.0)
    val recordingDuration: StateFlow<Double> = _recordingDuration

    val amplitudeLevel = audioRecorder.amplitudeLevel
    val recordingState = audioRecorder.state
    val livePitch = audioRecorder.livePitch

    private var rawSamples: ShortArray? = null

    fun setInstrument(instrument: InstrumentProfile) {
        _selectedInstrument.value = instrument
    }

    fun setAutoTranscribe(enabled: Boolean) {
        _autoTranscribe.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_TRANSCRIBE, enabled).apply()
    }

    fun startRecording() {
        Log.i(TAG, "startRecording: Initiating recording...")
        _uiState.value = UiState.IDLE
        _errorMessage.value = null
        _transcriptionResult.value = null
        _savedIdeaId.value = null

        if (!audioRecorder.hasPermission()) {
            Log.e(TAG, "startRecording: No RECORD_AUDIO permission")
            _errorMessage.value = "Microphone permission required"
            _uiState.value = UiState.ERROR
            return
        }

        val started = audioRecorder.startRecording()
        if (started) {
            Log.i(TAG, "startRecording: Recording started successfully")
            _uiState.value = UiState.RECORDING
            // Update duration periodically
            viewModelScope.launch {
                while (_uiState.value == UiState.RECORDING) {
                    _recordingDuration.value = audioRecorder.getRecordingDurationSeconds()
                    kotlinx.coroutines.delay(100)
                }
            }
        } else {
            Log.e(TAG, "startRecording: Failed to start recording")
            _errorMessage.value = "Failed to start recording"
            _uiState.value = UiState.ERROR
        }
    }

    fun stopRecording() {
        Log.i(TAG, "stopRecording: Stopping recording...")
        rawSamples = audioRecorder.stopRecording()
        _recordingDuration.value = (rawSamples?.size ?: 0).toDouble() / AudioRecorder.SAMPLE_RATE
        Log.i(TAG, "stopRecording: Got ${rawSamples?.size ?: 0} samples, duration=${_recordingDuration.value}s")

        if (rawSamples == null || rawSamples!!.isEmpty()) {
            Log.e(TAG, "stopRecording: No audio recorded!")
            _errorMessage.value = "No audio recorded"
            _uiState.value = UiState.ERROR
            return
        }

        if (_autoTranscribe.value) {
            processRecording()
        } else {
            saveWithoutTranscription()
        }
    }

    /** Save WAV only (no transcription) and navigate to preview with empty notes. */
    private fun saveWithoutTranscription() {
        Log.i(TAG, "saveWithoutTranscription: Saving WAV without running transcription...")
        _uiState.value = UiState.PROCESSING
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val samples = rawSamples ?: return@launch
                val instrument = _selectedInstrument.value
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val title = "Idea_$timestamp"

                val externalMusicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "NoteNotes"
                )
                externalMusicDir.mkdirs()
                val audioDir = File(externalMusicDir, "audio")
                audioDir.mkdirs()
                val wavFile = File(audioDir, "$title.wav")
                WavWriter.writeWav(samples, wavFile)
                Log.d(TAG, "saveWithoutTranscription: WAV saved: ${wavFile.absolutePath}")

                val idea = MelodyIdea(
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    audioFilePath = wavFile.absolutePath,
                    midiFilePath = null,
                    musicXmlFilePath = null,
                    instrument = instrument.name,
                    tempoBpm = 120,
                    keySignature = "C",
                    timeSignature = "4/4",
                    notes = "[]"
                )
                val id = dao.insert(idea)
                Log.i(TAG, "saveWithoutTranscription: Saved to DB with id=$id (no notes)")
                _savedIdeaId.value = id
                _uiState.value = UiState.DONE
            } catch (e: Exception) {
                Log.e(TAG, "saveWithoutTranscription: Error", e)
                _errorMessage.value = "Save error: ${e.message}"
                _uiState.value = UiState.ERROR
            }
        }
    }

    private fun processRecording() {
        Log.i(TAG, "processRecording: Starting transcription pipeline...")
        _uiState.value = UiState.PROCESSING

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val samples = rawSamples ?: return@launch

                // Run transcription pipeline
                Log.d(TAG, "processRecording: Running pipeline on ${samples.size} samples...")
                var result = pipeline.process(samples)
                Log.i(TAG, "processRecording: Pipeline returned ${result.notes.size} notes, key=${result.keySignature}, time=${result.timeSignature}, tempo=${result.tempoBpm}")

                if (result.notes.isEmpty()) {
                    Log.w(TAG, "processRecording: No notes detected")
                    _errorMessage.value = "No notes detected. Try recording in a quieter environment."
                    _uiState.value = UiState.ERROR
                    return@launch
                }

                // Apply instrument transposition and set instrument info
                val instrument = _selectedInstrument.value
                if (instrument.transposeSemitones != 0) {
                    Log.d(TAG, "processRecording: Transposing ${instrument.transposeSemitones} semitones for ${instrument.name}")
                    val transposedNotes = TranspositionUtils.transposeNotes(result.notes, instrument)
                    result = result.copy(notes = transposedNotes, instrument = instrument)
                } else {
                    result = result.copy(instrument = instrument)
                }

                _transcriptionResult.value = result

                // Save files — use external Music directory for persistence across uninstalls
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val title = "Idea_$timestamp"
                Log.d(TAG, "processRecording: Saving as '$title'...")

                // External base directory: Music/NoteNotes/
                val externalMusicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "NoteNotes"
                )
                externalMusicDir.mkdirs()

                // Save WAV to external storage
                val audioDir = File(externalMusicDir, "audio")
                audioDir.mkdirs()
                val wavFile = File(audioDir, "$title.wav")
                WavWriter.writeWav(samples, wavFile)
                Log.d(TAG, "processRecording: WAV saved: ${wavFile.absolutePath} (${wavFile.length()} bytes)")

                // Save MIDI & MusicXML to external storage
                val exportsDir = File(externalMusicDir, "exports")
                exportsDir.mkdirs()
                val midiFile = File(exportsDir, "$title.mid")
                midiWriter.writeToFile(result, midiFile)
                Log.d(TAG, "processRecording: MIDI saved: ${midiFile.absolutePath} (${midiFile.length()} bytes)")

                // Save MusicXML
                val xmlFile = File(exportsDir, "$title.musicxml")
                musicXmlGenerator.writeToFile(result, xmlFile)
                Log.d(TAG, "processRecording: MusicXML saved: ${xmlFile.absolutePath} (${xmlFile.length()} bytes)")

                // Save to database
                val idea = MelodyIdea(
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    audioFilePath = wavFile.absolutePath,
                    midiFilePath = midiFile.absolutePath,
                    musicXmlFilePath = xmlFile.absolutePath,
                    instrument = instrument.name,
                    tempoBpm = result.tempoBpm,
                    keySignature = result.keySignature.toString(),
                    timeSignature = result.timeSignature.toString(),
                    notes = gson.toJson(result.notes)
                )

                val id = dao.insert(idea)
                Log.i(TAG, "processRecording: Saved to DB with id=$id")
                _savedIdeaId.value = id
                _uiState.value = UiState.DONE

            } catch (e: Exception) {
                Log.e(TAG, "processRecording: Error during processing", e)
                _errorMessage.value = "Processing error: ${e.message}"
                _uiState.value = UiState.ERROR
            }
        }
    }

    fun reset() {
        _uiState.value = UiState.IDLE
        _errorMessage.value = null
        _transcriptionResult.value = null
        _savedIdeaId.value = null
        rawSamples = null
    }
}
