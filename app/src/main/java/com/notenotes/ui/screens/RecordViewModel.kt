package com.notenotes.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notenotes.audio.AudioPlayer
import com.notenotes.audio.AudioRecorder
import com.notenotes.audio.WavReader
import com.notenotes.audio.WavWriter
import com.notenotes.data.AppDatabase
import com.notenotes.export.MidiWriter
import com.notenotes.export.MusicXmlGenerator
import com.notenotes.model.InstrumentProfile
import com.notenotes.model.MelodyIdea
import com.notenotes.model.TranscriptionResult
import com.notenotes.processing.TranscriptionPipeline
import com.notenotes.ui.components.WaveformData
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
        IDLE, RECORDING, PAUSED, RECORDING_FINISHED, PROCESSING, DONE, ERROR
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
    
    private val _waveformWindowSize = MutableStateFlow(prefs.getFloat("waveform_window_size", 5f))
    val waveformWindowSize: StateFlow<Float> = _waveformWindowSize

    // Punch-in recording / playback state
    private var baseAudioBuffer: ShortArray? = null
    private var loadedIdeaId: Long? = null
    private val _punchInPositionMs = MutableStateFlow<Long>(0L)
    val punchInPositionMs: StateFlow<Long> = _punchInPositionMs
    val isSynced = MutableStateFlow(true)
    fun toggleSync() { isSynced.value = !isSynced.value }
    private var activePunchInMs = 0L

    private val audioPlayer = AudioPlayer()
    val playbackState = audioPlayer.state
    val playbackProgress = audioPlayer.progress

    private val _waveformData = MutableStateFlow<WaveformData?>(null)
    val waveformData: StateFlow<WaveformData?> = _waveformData
    
    private val _loadedIdea = MutableStateFlow<MelodyIdea?>(null)
    val loadedIdea: StateFlow<MelodyIdea?> = _loadedIdea

    private val _isChanged = MutableStateFlow(false)
    val isChanged: StateFlow<Boolean> = _isChanged

    // Listen for SharedPreferences changes (e.g. from SettingsScreen)
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_AUTO_TRANSCRIBE) {
            _autoTranscribe.value = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true)
        } else if (key == "waveform_window_size") {
            _waveformWindowSize.value = prefs.getFloat("waveform_window_size", 5f)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        viewModelScope.launch {
            audioPlayer.progress.collect { prog ->
                if (isSynced.value && audioPlayer.state.value != com.notenotes.audio.AudioPlayer.PlaybackState.IDLE || prog == 1.0f) {
                    if (isSynced.value) {
                        val ms = getCurrentTrackDurationMs()
                        _punchInPositionMs.value = (prog * ms.toFloat()).toLong()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        audioPlayer.release()
    }

    private val _recordingDuration = MutableStateFlow(0.0)
    val recordingDuration: StateFlow<Double> = _recordingDuration

    private val _recordHeadFraction = MutableStateFlow(0f)
    val recordHeadFraction: StateFlow<Float> = _recordHeadFraction

    val amplitudeLevel = audioRecorder.amplitudeLevel
    val recordingState = audioRecorder.state
    val livePitch = audioRecorder.livePitch


    private fun getBufferDurationMs(size: Int): Long {
        return (size.toDouble() / com.notenotes.audio.AudioRecorder.SAMPLE_RATE * 1000).toLong()
    }

    private fun getCurrentTrackDurationMs(): Long {
        return baseAudioBuffer?.let { getBufferDurationMs(it.size) } ?: audioPlayer.getDurationMs().toLong()
    }
    private var rawSamples: ShortArray? = null

    // --- Playback and Punch-In Logic ---
    
    fun play() {
        val samples = baseAudioBuffer
        if (samples != null) {
            if (audioPlayer.state.value == com.notenotes.audio.AudioPlayer.PlaybackState.PAUSED) {
                audioPlayer.resume()
            } else {
                val tempFile = com.notenotes.audio.AudioUtils.saveToTemporaryWaveFile(context, samples, "temp_record_${System.currentTimeMillis()}.wav")
                audioPlayer.play(tempFile)
                val ms = getBufferDurationMs(samples.size)
                audioPlayer.seekTo(if (ms > 0) _punchInPositionMs.value.toFloat() / ms.toFloat() else 0f)
            }
        } else {
            _loadedIdea.value?.audioFilePath?.let { file ->
                // Resume if paused, otherwise start from current progress
                if (audioPlayer.state.value == com.notenotes.audio.AudioPlayer.PlaybackState.PAUSED) {
                    audioPlayer.resume()
                } else {
                    audioPlayer.play(File(file))
                    audioPlayer.seekTo(_punchInPositionMs.value.toFloat() / audioPlayer.getDurationMs().toLong().coerceAtLeast(1L))
                }
            }
        }
    }

    fun pause() {
        audioPlayer.pause()
    }

    fun stopPlayback() {
        audioPlayer.stop()
        _punchInPositionMs.value = 0L
    }

    fun seekPlayhead(fraction: Float) {
        audioPlayer.seekTo(fraction)
        if (isSynced.value) {
            val ms = getCurrentTrackDurationMs()
            _punchInPositionMs.value = (fraction * ms.toFloat()).toLong()
        }
    }

    fun seekEditHead(fraction: Float) {
        val ms = getCurrentTrackDurationMs()
        _punchInPositionMs.value = (fraction * ms.toFloat()).toLong()
        if (isSynced.value) {
            audioPlayer.seekTo(fraction)
        }
    }

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        audioPlayer.setPlaybackSpeed(speed)
    }

    fun jumpPlayheadToEdit() {
        val ms = getCurrentTrackDurationMs()
        val frac = if (ms > 0L) _punchInPositionMs.value.toFloat() / ms.toFloat() else 0f
        audioPlayer.seekTo(frac)
    }

    fun jumpEditToPlayhead() {
        val ms = getCurrentTrackDurationMs()
        val frac = audioPlayer.getCurrentProgress()
        _punchInPositionMs.value = (frac * ms.toFloat()).toLong()
    }

    fun loadIdeaForRerecord(ideaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val idea = dao.getIdeaById(ideaId)
                if (idea != null) {
                    val file = File(idea.audioFilePath)
                    if (file.exists()) {
                        val shorts = WavReader.readSamples(file)
                        if (shorts != null) {
                            baseAudioBuffer = shorts
                            loadedIdeaId = ideaId
                            _loadedIdea.value = idea
                            _waveformData.value = WaveformData.fromSamples(shorts, AudioRecorder.SAMPLE_RATE)
                            _punchInPositionMs.value = 0L
                            _isChanged.value = false
                            Log.i(TAG, "Loaded idea $ideaId for rerecord, ${shorts.size} samples")
                        } else {
                            Log.e(TAG, "Failed to read WAV samples for idea $ideaId")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading idea for rerecord", e)
            }
        }
    }

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

        // Pause playback if it's currently playing and lock in punch-in time
        if (audioPlayer.state.value == com.notenotes.audio.AudioPlayer.PlaybackState.PLAYING) {
            audioPlayer.pause()
            val ms = getCurrentTrackDurationMs()
            _punchInPositionMs.value = (audioPlayer.getCurrentProgress() * ms.toFloat()).toLong()
        }

        activePunchInMs = _punchInPositionMs.value

        val started = audioRecorder.startRecording()
        if (started) {
            Log.i(TAG, "startRecording: Recording started successfully")
            _uiState.value = UiState.RECORDING
            
            viewModelScope.launch(Dispatchers.Default) {
                while (_uiState.value == UiState.RECORDING) {
                    kotlinx.coroutines.delay(200)
                    val live = audioRecorder.getLiveSamples()
                    val merged = mergeBuffers(baseAudioBuffer, live, activePunchInMs)
                    _waveformData.value = WaveformData.fromSamples(merged, AudioRecorder.SAMPLE_RATE)
                    val liveDurationMs = getBufferDurationMs(live.size)
                    val headMs = activePunchInMs + liveDurationMs
                    if (isSynced.value) { _punchInPositionMs.value = activePunchInMs + liveDurationMs }
                    val totalMs = getBufferDurationMs(merged.size)
                    _recordHeadFraction.value = if (totalMs > 0) headMs.toFloat() / totalMs.toFloat() else 0f
                }
            }
        } else {
            Log.e(TAG, "startRecording: Failed to start recording")
            _errorMessage.value = "Failed to start recording"
            _uiState.value = UiState.ERROR
        }
    }

    fun pauseRecording() {
        Log.i(TAG, "pauseRecording: Pausing recording...")
        val rec = audioRecorder.stopRecording()
        
        baseAudioBuffer = mergeBuffers(baseAudioBuffer, rec, activePunchInMs)
        _waveformData.value = WaveformData.fromSamples(baseAudioBuffer!!, AudioRecorder.SAMPLE_RATE)
        _uiState.value = UiState.PAUSED
        _isChanged.value = true
        val recordedLengthMs = getBufferDurationMs(rec.size)
        if (isSynced.value) {
            _punchInPositionMs.value = activePunchInMs + recordedLengthMs
        } else {
            _punchInPositionMs.value = activePunchInMs
        }
        // Force audioPlayer to reload the new buffer on next play
        audioPlayer.stop()
    }

    fun saveRecording() {
        val samples = baseAudioBuffer
        if (samples == null || samples.isEmpty()) {
            _errorMessage.value = "No audio recorded"
            _uiState.value = UiState.ERROR
            return
        }

        rawSamples = samples
        val tempFile = com.notenotes.audio.AudioUtils.saveToTemporaryWaveFile(context, samples, "temp_record_${System.currentTimeMillis()}.wav")
        Log.i(TAG, "saveRecording: Saved to temp wav file: ${tempFile.absolutePath}")

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
                    keySignature = null,
                    timeSignature = null,
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
        val currentLoadedIdeaId = loadedIdeaId
        
        _uiState.value = UiState.IDLE
        _errorMessage.value = null
        _transcriptionResult.value = null
        _savedIdeaId.value = null
        rawSamples = null
        baseAudioBuffer = null
        loadedIdeaId = null
        _punchInPositionMs.value = 0L
        _loadedIdea.value = null
        _waveformData.value = null
        _isChanged.value = false
        audioPlayer.stop()

        if (currentLoadedIdeaId != null) {
            loadIdeaForRerecord(currentLoadedIdeaId)
        }
    }

    private fun mergeBuffers(base: ShortArray?, newRec: ShortArray?, punchInMs: Long): ShortArray {
        if (newRec == null || newRec.isEmpty()) return base ?: ShortArray(0)
        if (base == null || base.isEmpty()) return newRec

        val punchInIndex = (punchInMs / 1000.0 * AudioRecorder.SAMPLE_RATE).toInt().coerceIn(0, base.size)
        val newSize = kotlin.math.max(base.size, punchInIndex + newRec.size)
        val mergedAudio = ShortArray(newSize)

        System.arraycopy(base, 0, mergedAudio, 0, punchInIndex)
        System.arraycopy(newRec, 0, mergedAudio, punchInIndex, newRec.size)

        val baseTailStart = punchInIndex + newRec.size
        if (baseTailStart < base.size) {
            System.arraycopy(base, baseTailStart, mergedAudio, baseTailStart, base.size - baseTailStart)
        }

        return mergedAudio
    }
}





