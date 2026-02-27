package com.notenotes.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notenotes.audio.AudioPlayer
import com.notenotes.audio.WavWriter
import com.notenotes.data.AppDatabase
import com.notenotes.export.FileExporter
import com.notenotes.export.MidiWriter
import com.notenotes.export.MusicXmlGenerator
import com.notenotes.model.InstrumentProfile
import com.notenotes.model.MelodyIdea
import com.notenotes.model.MusicalNote
import com.notenotes.model.TranscriptionResult
import com.notenotes.model.KeySignature
import com.notenotes.model.TimeSignature
import com.notenotes.processing.PitchAlgorithm
import com.notenotes.processing.TranscriptionPipeline
import com.notenotes.ui.components.WaveformData
import com.notenotes.util.TranspositionUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "NNPreview"

class PreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val dao = AppDatabase.getDatabase(context).melodyDao()
    private val audioPlayer = AudioPlayer()
    private val fileExporter = FileExporter(context)
    private val midiWriter = MidiWriter()
    private val musicXmlGenerator = MusicXmlGenerator()
    private val gson = Gson()

    private val _idea = MutableStateFlow<MelodyIdea?>(null)
    val idea: StateFlow<MelodyIdea?> = _idea

    private val _musicXml = MutableStateFlow<String?>(null)
    val musicXml: StateFlow<String?> = _musicXml

    private val _notesList = MutableStateFlow<List<MusicalNote>>(emptyList())
    val notesList: StateFlow<List<MusicalNote>> = _notesList

    val playbackState: StateFlow<AudioPlayer.PlaybackState> = audioPlayer.state
    val playbackProgress: StateFlow<Float> = audioPlayer.progress

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _waveformData = MutableStateFlow<WaveformData?>(null)
    val waveformData: StateFlow<WaveformData?> = _waveformData

    private val _audioDurationMs = MutableStateFlow(0)
    val audioDurationMs: StateFlow<Int> = _audioDurationMs

    private val _isRetranscribing = MutableStateFlow(false)
    val isRetranscribing: StateFlow<Boolean> = _isRetranscribing

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
                    // Also parse notes for note name view
                    if (melodyIdea.notes != null) {
                        try {
                            val notesType = object : TypeToken<List<MusicalNote>>() {}.type
                            val notes: List<MusicalNote> = gson.fromJson(melodyIdea.notes, notesType)
                            _notesList.value = notes
                        } catch (e: Exception) {
                            Log.w(TAG, "loadIdea: Could not parse notes JSON for note name view", e)
                        }
                    }
                } else if (melodyIdea.notes != null) {
                    Log.d(TAG, "loadIdea: Regenerating MusicXML from stored notes...")
                    // Rebuild from stored notes
                    val notesType = object : TypeToken<List<MusicalNote>>() {}.type
                    val notes: List<MusicalNote> = gson.fromJson(melodyIdea.notes, notesType)
                    Log.d(TAG, "loadIdea: Parsed ${notes.size} notes from JSON")
                    
                    val keySig = parseKeySignature(melodyIdea.keySignature)
                    val timeSig = parseTimeSignature(melodyIdea.timeSignature)
                    val instrument = InstrumentProfile.ALL.find { it.name == melodyIdea.instrument }

                    val result = TranscriptionResult(
                        notes = notes,
                        keySignature = keySig,
                        timeSignature = timeSig,
                        tempoBpm = melodyIdea.tempoBpm,
                        instrument = instrument
                    )
                    currentResult = result
                    _musicXml.value = musicXmlGenerator.generateMusicXml(result)
                    _notesList.value = notes
                    Log.d(TAG, "loadIdea: MusicXML regenerated successfully")
                } else {
                    Log.w(TAG, "loadIdea: No XML file and no stored notes!")
                }

                // Load waveform data from audio file
                melodyIdea.audioFilePath?.let { audioPath ->
                    loadWaveformData(File(audioPath))
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

    fun shareAudio(context: Context) {
        val audioPath = _idea.value?.audioFilePath ?: return
        val file = File(audioPath)
        if (!file.exists()) {
            _errorMessage.value = "Audio file not found"
            return
        }
        val intent = fileExporter.shareAudioFile(file)
        context.startActivity(Intent.createChooser(intent, "Share Audio"))
    }

    fun seekTo(fraction: Float) {
        audioPlayer.seekTo(fraction)
    }

    /**
     * Re-transcribe audio from the stored WAV file with optional settings overrides.
     * Updates the DB, MIDI, MusicXML, and in-memory state.
     */
    fun retranscribe(
        sensitivity: Double? = null,
        tempoBpm: Int? = null,
        keySignature: KeySignature? = null,
        timeSignature: TimeSignature? = null,
        pitchAlgorithm: PitchAlgorithm? = null
    ) {
        val melodyIdea = _idea.value ?: return
        val audioPath = melodyIdea.audioFilePath ?: run {
            _errorMessage.value = "No audio file to retranscribe"
            return
        }

        _isRetranscribing.value = true
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val audioFile = File(audioPath)
                if (!audioFile.exists()) {
                    _errorMessage.value = "Audio file not found"
                    _isRetranscribing.value = false
                    return@launch
                }

                // Read WAV samples
                val samples = readWavSamples(audioFile)
                if (samples == null || samples.isEmpty()) {
                    _errorMessage.value = "Could not read audio file"
                    _isRetranscribing.value = false
                    return@launch
                }

                Log.i(TAG, "retranscribe: Running pipeline on ${samples.size} samples" +
                    ", sensitivity=${sensitivity ?: "default"}" +
                    ", tempo=${tempoBpm ?: "auto"}" +
                    ", key=${keySignature ?: "auto"}" +
                    ", time=${timeSignature ?: "auto"}")

                // Create pipeline with custom sensitivity and algorithm if provided
                val minConfidence = sensitivity ?: 0.3
                val algorithm = pitchAlgorithm ?: PitchAlgorithm.DEFAULT
                val pipeline = TranscriptionPipeline(
                    minNoteConfidence = minConfidence,
                    pitchAlgorithm = algorithm
                )
                var result = pipeline.process(
                    samples = samples,
                    userTempoBpm = tempoBpm,
                    userKeySignature = keySignature,
                    userTimeSignature = timeSignature
                )

                if (result.notes.isEmpty()) {
                    _errorMessage.value = "No notes detected. Try lowering sensitivity."
                    _isRetranscribing.value = false
                    return@launch
                }

                // Apply instrument transposition
                val instrument = InstrumentProfile.ALL.find { it.name == melodyIdea.instrument }
                    ?: InstrumentProfile.GUITAR
                if (instrument.transposeSemitones != 0) {
                    val transposedNotes = TranspositionUtils.transposeNotes(result.notes, instrument)
                    result = result.copy(notes = transposedNotes, instrument = instrument)
                } else {
                    result = result.copy(instrument = instrument)
                }

                // Regenerate export files
                val title = melodyIdea.title
                val exportsDir = File(context.filesDir, "exports")
                exportsDir.mkdirs()

                val midiFile = File(exportsDir, "$title.mid")
                midiWriter.writeToFile(result, midiFile)

                val xmlFile = File(exportsDir, "$title.musicxml")
                musicXmlGenerator.writeToFile(result, xmlFile)

                val xmlContent = musicXmlGenerator.generateMusicXml(result)

                // Update database
                val updatedIdea = melodyIdea.copy(
                    midiFilePath = midiFile.absolutePath,
                    musicXmlFilePath = xmlFile.absolutePath,
                    tempoBpm = result.tempoBpm,
                    keySignature = result.keySignature.toString(),
                    timeSignature = result.timeSignature.toString(),
                    notes = gson.toJson(result.notes)
                )
                dao.update(updatedIdea)

                // Update in-memory state
                currentResult = result
                _idea.value = updatedIdea
                _musicXml.value = xmlContent
                _notesList.value = result.notes

                Log.i(TAG, "retranscribe: Done — ${result.notes.size} notes, " +
                    "key=${result.keySignature}, time=${result.timeSignature}, " +
                    "tempo=${result.tempoBpm}")

                _isRetranscribing.value = false
            } catch (e: Exception) {
                Log.e(TAG, "retranscribe: Error", e)
                _errorMessage.value = "Retranscription error: ${e.message}"
                _isRetranscribing.value = false
            }
        }
    }

    /**
     * Read PCM samples from a WAV file.
     */
    private fun readWavSamples(file: File): ShortArray? {
        try {
            val raf = RandomAccessFile(file, "r")
            raf.seek(12) // skip RIFF header
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val chunkSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt()

                if (String(chunkId) == "data") {
                    val dataBytes = ByteArray(chunkSize)
                    raf.readFully(dataBytes)
                    raf.close()
                    val bb = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                    return ShortArray(chunkSize / 2) { bb.getShort() }
                } else {
                    raf.seek(raf.filePointer + chunkSize.toLong())
                }
            }
            raf.close()
        } catch (e: Exception) {
            Log.e(TAG, "readWavSamples: Error", e)
        }
        return null
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

    /**
     * Load waveform data from a WAV file for visualization.
     */
    private fun loadWaveformData(file: File) {
        if (!file.exists()) {
            Log.w(TAG, "loadWaveformData: File not found: ${file.absolutePath}")
            return
        }
        try {
            val raf = RandomAccessFile(file, "r")
            // Read WAV header
            val header = ByteArray(44)
            raf.readFully(header)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            bb.position(0)
            val riff = ByteArray(4); bb.get(riff)
            if (String(riff) != "RIFF") {
                Log.w(TAG, "loadWaveformData: Not a RIFF file")
                raf.close()
                return
            }
            bb.getInt() // fileSize
            val wave = ByteArray(4); bb.get(wave)

            // Skip to data chunk
            bb.position(16)
            val fmtSize = bb.getInt()
            bb.position(20 + fmtSize) // Skip fmt chunk
            
            // Find actual data chunk position
            raf.seek(20 + fmtSize.toLong())
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val chunkSizeBytes = ByteArray(4)
                raf.readFully(chunkSizeBytes)
                val chunkSize = ByteBuffer.wrap(chunkSizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt()
                
                if (String(chunkId) == "data") {
                    val numSamples = chunkSize / 2
                    val dataBytes = ByteArray(chunkSize)
                    raf.readFully(dataBytes)
                    val dataBB = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val samples = ShortArray(numSamples) { dataBB.getShort() }
                    
                    _waveformData.value = WaveformData.fromSamples(samples, 44100, 2000)
                    _audioDurationMs.value = (numSamples * 1000 / 44100)
                    Log.d(TAG, "loadWaveformData: Loaded $numSamples samples, ${_audioDurationMs.value}ms")
                    break
                } else {
                    raf.seek(raf.filePointer + chunkSize.toLong())
                }
            }
            raf.close()
        } catch (e: Exception) {
            Log.e(TAG, "loadWaveformData: Error reading WAV", e)
        }
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
