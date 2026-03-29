package com.notenotes.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notenotes.audio.AudioPlayer
import com.notenotes.audio.AudioDecoder
import com.notenotes.audio.WavReader
import com.notenotes.audio.WavWriter
import com.notenotes.data.AppDatabase
import com.notenotes.export.FileExporter
import com.notenotes.export.MidiWriter
import com.notenotes.export.MusicXmlGenerator
import com.notenotes.export.MusicXmlSanitizer
import com.notenotes.model.InstrumentProfile
import com.notenotes.model.MelodyIdea
import com.notenotes.model.MusicalNote
import com.notenotes.model.TranscriptionResult
import com.notenotes.model.KeySignature
import com.notenotes.model.TimeSignature
import com.notenotes.processing.PitchAlgorithm
import com.notenotes.processing.TranscriptionPipeline
import com.notenotes.ui.components.WaveformData
import com.notenotes.util.NoteTimingHelper
import com.notenotes.util.TranspositionUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.os.Environment
import kotlin.math.abs

private const val TAG = "NNPreview"

class PreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val dao = AppDatabase.getDatabase(context).melodyDao()
    private val audioPlayer = AudioPlayer()
    private val midiWriter = MidiWriter()
    private val musicXmlGenerator = MusicXmlGenerator()
    private val fileExporter = FileExporter(context, midiWriter, musicXmlGenerator)
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

    private val _leadingRestBeatCount = MutableStateFlow(0)
    val leadingRestBeatCount: StateFlow<Int> = _leadingRestBeatCount

    private val _isRetranscribing = MutableStateFlow(false)
    val isRetranscribing: StateFlow<Boolean> = _isRetranscribing

    // Editing state
    private val _selectedNoteIndex = MutableStateFlow<Int?>(null)
    val selectedNoteIndex: StateFlow<Int?> = _selectedNoteIndex

    private val _editCursorFraction = MutableStateFlow<Float?>(null)
    val editCursorFraction: StateFlow<Float?> = _editCursorFraction

    private val _isEditorOpen = MutableStateFlow(false)
    val isEditorOpen: StateFlow<Boolean> = _isEditorOpen

    // Derived: the actual selected note object (for display)
    val selectedNote: MusicalNote?
        get() {
            val idx = _selectedNoteIndex.value ?: return null
            val notes = _notesList.value
            return if (idx in notes.indices) notes[idx] else null
        }

    // Waveform window state
    private val _windowStartFraction = MutableStateFlow(0f)
    val windowStartFraction: StateFlow<Float> = _windowStartFraction

    private val _windowSizeSec = MutableStateFlow(5f)
    val windowSizeSec: StateFlow<Float> = _windowSizeSec

    private val _isWindowLocked = MutableStateFlow(false)
    val isWindowLocked: StateFlow<Boolean> = _isWindowLocked

    // Rename dialog state
    private val _isRenameDialogOpen = MutableStateFlow(false)
    val isRenameDialogOpen: StateFlow<Boolean> = _isRenameDialogOpen

    // Tab state (persists across navigation)
    private val _selectedTab = MutableStateFlow(2)  // 0=Sheet, 1=Notes, 2=Waveform
    val selectedTab: StateFlow<Int> = _selectedTab

    fun setSelectedTab(tab: Int) { _selectedTab.value = tab }

    private var currentResult: TranscriptionResult? = null

    fun loadIdea(ideaId: Long) {
        Log.i(TAG, "loadIdea: Loading idea id=$ideaId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _leadingRestBeatCount.value = 0
                val melodyIdea = dao.getIdeaById(ideaId) ?: run {
                    Log.e(TAG, "loadIdea: Idea $ideaId not found in database!")
                    _errorMessage.value = "Idea not found"
                    return@launch
                }
                Log.d(TAG, "loadIdea: Found '${melodyIdea.title}', audio=${melodyIdea.audioFilePath}, xml=${melodyIdea.musicXmlFilePath}")
                _idea.value = melodyIdea

                // Always regenerate MusicXML from stored notes (avoids stale/corrupt files on disk)
                if (melodyIdea.notes != null) {
                    Log.d(TAG, "loadIdea: Regenerating MusicXML from stored notes...")
                    val notesType = object : TypeToken<List<MusicalNote>>() {}.type
                    val notes: List<MusicalNote> = MusicalNote.sanitizeList(
                        gson.fromJson(melodyIdea.notes, notesType)
                    )
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
                    _musicXml.value = MusicXmlSanitizer.sanitize(
                        musicXmlGenerator.generateMusicXml(result)
                    )
                    _notesList.value = notes
                    refreshLeadingRestBeatCount()
                    Log.d(TAG, "loadIdea: MusicXML regenerated successfully")
                } else {
                    // Fallback: try loading from saved file (no notes in DB)
                    val xmlFile = melodyIdea.musicXmlFilePath?.let { File(it) }
                    if (xmlFile != null && xmlFile.exists() && xmlFile.length() > 0) {
                        var content = xmlFile.readText()
                        if (content.trimStart().startsWith("<?xml") || content.trimStart().startsWith("<score-partwise")) {
                            Log.d(TAG, "loadIdea: Loading MusicXML from file (${xmlFile.length()} bytes)")
                            // Patch legacy files: inject <voice>1</voice> if missing
                            // (required by alphaTab to initialise voice arrays)
                            if (!content.contains("<voice>")) {
                                content = content.replace(
                                    Regex("(<duration>[^<]*</duration>)"),
                                    "$1\n        <voice>1</voice>"
                                )
                                Log.d(TAG, "loadIdea: Patched legacy MusicXML with <voice> elements")
                            }
                            // Sanitize non-standard durations (fixes alphaTab 'push' crash)
                            content = MusicXmlSanitizer.sanitize(content)
                            // Persist all fixes so they don't need patching on every load
                            try { xmlFile.writeText(content) } catch (_: Exception) {}
                            _musicXml.value = content

                            // Parse notes from MusicXML so Notes tab and Waveform annotations populate
                            val parsedNotes = com.notenotes.export.MusicXmlParser().parse(content, melodyIdea.tempoBpm).notes
                            if (parsedNotes.isNotEmpty()) {
                                _notesList.value = parsedNotes
                                refreshLeadingRestBeatCount()
                                Log.d(TAG, "loadIdea: Parsed ${parsedNotes.size} notes from MusicXML file")

                                val keySig = parseKeySignature(melodyIdea.keySignature)
                                val timeSig = parseTimeSignature(melodyIdea.timeSignature)
                                val instrument = InstrumentProfile.ALL.find { it.name == melodyIdea.instrument }
                                currentResult = TranscriptionResult(
                                    notes = parsedNotes,
                                    keySignature = keySig,
                                    timeSignature = timeSig,
                                    tempoBpm = melodyIdea.tempoBpm,
                                    instrument = instrument
                                )

                                // Persist parsed notes so future loads use the primary path
                                val updatedIdea = melodyIdea.copy(notes = gson.toJson(parsedNotes))
                                dao.update(updatedIdea)
                                _idea.value = updatedIdea
                            }
                        } else {
                            Log.w(TAG, "loadIdea: File exists but doesn't look like valid MusicXML")
                        }
                    } else {
                        Log.w(TAG, "loadIdea: No stored notes and no valid XML file!")
                    }
                }

                // Load waveform data from audio file
                loadWaveformData(File(melodyIdea.audioFilePath))
            } catch (e: Exception) {
                Log.e(TAG, "loadIdea: Error loading idea", e)
                _errorMessage.value = "Error loading: ${e.message}"
            }
        }
    }

    private fun parseKeySignature(s: String?): com.notenotes.model.KeySignature {
        if (s.isNullOrBlank()) return com.notenotes.model.KeySignature.C_MAJOR
        val trimmed = s.trim()
        val byExact = com.notenotes.model.KeySignature.ALL_KEYS.firstOrNull { it.toString().equals(trimmed, ignoreCase = true) }
        if (byExact != null) return byExact
        val byRoot = com.notenotes.model.KeySignature.ALL_KEYS.firstOrNull { it.root.equals(trimmed, ignoreCase = true) }
        return byRoot ?: com.notenotes.model.KeySignature.C_MAJOR
    }

    private fun parseTimeSignature(s: String?): com.notenotes.model.TimeSignature {
        if (s.isNullOrBlank()) return com.notenotes.model.TimeSignature.FOUR_FOUR
        val trimmed = s.trim()
        val byExact = com.notenotes.model.TimeSignature.SUPPORTED.firstOrNull { it.toString() == trimmed }
        if (byExact != null) return byExact
        // Try parsing numeric form like "3/4"
        val parts = trimmed.split('/')
        if (parts.size == 2) {
            val num = parts[0].toIntOrNull()
            val den = parts[1].toIntOrNull()
            if (num != null && den != null) {
                return com.notenotes.model.TimeSignature(num, den)
            }
        }
        return com.notenotes.model.TimeSignature.FOUR_FOUR
    }

    /** Load waveform samples and prepare AudioPlayer for playback. */
    fun loadWaveformData(file: java.io.File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wav = WavReader.readFull(file)
                if (wav != null) {
                    _audioDurationMs.value = wav.durationMs
                    _waveformData.value = WaveformData.fromSamples(wav.samples, wav.sampleRate)
                } else {
                    // Try a more permissive reader
                    val raw = WavReader.readSamples(file)
                    if (raw != null) {
                        _audioDurationMs.value = Math.round(raw.size.toDouble() / 44100.0 * 1000.0).toInt()
                        _waveformData.value = WaveformData.fromSamples(raw, 44100)
                    } else {
                        _audioDurationMs.value = 0
                        _waveformData.value = null
                    }
                }
                // Prepare the audio player so UI can play immediately
                try { audioPlayer.load(file) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "loadWaveformData: Error", e)
            }
        }
    }

    /** Retranscribe audio using the specified pitch algorithm and update state. */
    fun retranscribe(pitchAlgorithm: PitchAlgorithm = PitchAlgorithm.DEFAULT) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRetranscribing.value = true
            try {
                val melodyIdea = _idea.value ?: return@launch
                val audioFile = java.io.File(melodyIdea.audioFilePath)
                val wav = WavReader.readFull(audioFile) ?: run {
                    val samples = WavReader.readSamples(audioFile) ?: ShortArray(0)
                    WaveformData.fromSamples(samples, 44100)
                    null
                }

                val samples = wav?.samples ?: WavReader.readSamples(audioFile) ?: ShortArray(0)
                val sampleRate = wav?.sampleRate ?: 44100

                if (samples.isEmpty()) {
                    _errorMessage.value = "Audio not readable for transcription"
                    _isRetranscribing.value = false
                    return@launch
                }

                val pipeline = TranscriptionPipeline(sampleRate = sampleRate, pitchAlgorithm = pitchAlgorithm)
                val result = pipeline.process(samples, userTempoBpm = null, userKeySignature = null, userTimeSignature = null)

                // Persist result and update UI
                currentResult = result
                _notesList.value = result.notes
                _musicXml.value = MusicXmlSanitizer.sanitize(musicXmlGenerator.generateMusicXml(result))
                refreshLeadingRestBeatCount()

                // Update DB row
                val updated = melodyIdea.copy(
                    notes = gson.toJson(result.notes),
                    tempoBpm = result.tempoBpm,
                    keySignature = result.keySignature.toString(),
                    timeSignature = result.timeSignature.toString()
                )
                dao.update(updated)
                _idea.value = updated

                // Also reload waveform (duration/sampleRate may have changed)
                loadWaveformData(audioFile)
            } catch (e: Exception) {
                Log.e(TAG, "retranscribe: Error", e)
                _errorMessage.value = "Retranscribe failed: ${e.message}"
            } finally {
                _isRetranscribing.value = false
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
            // Remember pre-scrubbed position so we can seek after play
            val preScrubPosition = audioPlayer.progress.value
            audioPlayer.play(file)
            // If user scrubbed before first play, seek to that position
            if (preScrubPosition > 0.01f) {
                audioPlayer.seekTo(preScrubPosition)
            }
            // Apply current speed setting (new MediaPlayer starts at 1x)
            if (_playbackSpeed.value != 1f) {
                audioPlayer.setPlaybackSpeed(_playbackSpeed.value)
            }
        } else {
            Log.e(TAG, "playVoiceMemo: Audio file not found: $audioPath")
            _errorMessage.value = "Audio file not found"
        }
    }

    fun pausePlayback() = audioPlayer.pause()
    fun resumePlayback() = audioPlayer.resume()
    fun stopPlayback() = audioPlayer.stop()

    // Playback speed
    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        audioPlayer.setPlaybackSpeed(speed)
    }

    fun setError(message: String) {
        _errorMessage.value = message
    }

    fun shareMidi(context: Context) {
        val result = buildResult() ?: return
        val title = _idea.value?.title?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "melody"
        val intent = fileExporter.shareMidi(result, title)
        context.startActivity(Intent.createChooser(intent, "Share MIDI"))
    }

    fun shareMusicXml(context: Context) {
        val result = buildResult() ?: return
        val title = _idea.value?.title?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "melody"
        val intent = fileExporter.shareMusicXml(result, title)
        context.startActivity(Intent.createChooser(intent, "Share MusicXML"))
    }

    fun shareAll(context: Context) {
        val result = buildResult() ?: return
        val title = _idea.value?.title?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "melody"
        val audioFile = _idea.value?.audioFilePath?.let { File(it) }
        val intent = fileExporter.shareAll(result, title, audioFile)
        context.startActivity(Intent.createChooser(intent, "Share Files"))
    }

    /** Construct a TranscriptionResult from current idea/notes, or null if impossible. */
    fun buildResult(): TranscriptionResult? {
        val notes = _notesList.value
        if (notes.isEmpty()) return null
        val melodyIdea = _idea.value
        val keySig = parseKeySignature(melodyIdea?.keySignature)
        val timeSig = parseTimeSignature(melodyIdea?.timeSignature)
        val bpm = melodyIdea?.tempoBpm ?: 120
        val inst = melodyIdea?.instrument?.let { name -> InstrumentProfile.ALL.find { it.name == name } }
        return TranscriptionResult(
            notes = notes,
            keySignature = keySig,
            timeSignature = timeSig,
            tempoBpm = bpm,
            divisions = 4,
            instrument = inst
        )
    }

    /**
     * Export a complete snapshot ZIP containing audio, MIDI, MusicXML,
     * and a metadata JSON file that preserves all idea properties.
     */
    fun shareSnapshot(context: Context) {
        val melodyIdea = _idea.value ?: return
        val title = melodyIdea.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = File(context.filesDir, "exports")
        dir.mkdirs()
        val zipFile = File(dir, "$title.notenotes.zip")

        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                // metadata.json
                val meta = mutableMapOf<String, Any?>()
                meta["title"] = melodyIdea.title
                meta["instrument"] = melodyIdea.instrument
                meta["tempoBpm"] = melodyIdea.tempoBpm
                meta["keySignature"] = melodyIdea.keySignature
                meta["timeSignature"] = melodyIdea.timeSignature
                meta["notes"] = melodyIdea.notes
                meta["groupId"] = melodyIdea.groupId
                meta["groupName"] = melodyIdea.groupName
                meta["createdAt"] = melodyIdea.createdAt

                zos.putNextEntry(ZipEntry("metadata.json"))
                zos.write(gson.toJson(meta).toByteArray())
                zos.closeEntry()

                // Audio file
                melodyIdea.audioFilePath.let { path ->
                    val audio = File(path)
                    if (audio.exists()) {
                        val ext = audio.extension.ifEmpty { "wav" }
                        zos.putNextEntry(ZipEntry("audio.$ext"))
                        audio.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                // MIDI (generate fresh)
                val result = buildResult()
                if (result != null) {
                    val midFile = File(dir, "__temp.mid")
                    midiWriter.writeToFile(result, midFile)
                    zos.putNextEntry(ZipEntry("melody.mid"))
                    midFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    midFile.delete()

                    // MusicXML (generate fresh + sanitize)
                    val xml = MusicXmlSanitizer.sanitize(
                        musicXmlGenerator.generateMusicXml(result)
                    )
                    zos.putNextEntry(ZipEntry("melody.musicxml"))
                    zos.write(xml.toByteArray())
                    zos.closeEntry()
                }
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zipFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Snapshot"))
        } catch (e: Exception) {
            Log.e(TAG, "shareSnapshot: Error", e)
            _errorMessage.value = "Snapshot export error: ${e.message}"
        }
    }

    fun shareAudio(context: Context) {
        val audioPath = _idea.value?.audioFilePath ?: return
        val title = _idea.value?.title
        val file = File(audioPath)
        if (!file.exists()) {
            _errorMessage.value = "Audio file not found"
            return
        }
        val intent = fileExporter.shareAudioFile(file, title)
        context.startActivity(Intent.createChooser(intent, "Share Audio"))
    }

    fun seekTo(fraction: Float) {
        // Pause playback while scrubbing to avoid stuttering
        if (audioPlayer.state.value == AudioPlayer.PlaybackState.PLAYING) {
            audioPlayer.pause()
        }
        audioPlayer.seekTo(fraction)
        // Make the seeked position the START of the window
        moveWindowToFraction(fraction)
    }

    /**
     * Seek audio only without moving the waveform window.
     * Used when scrubbing directly on the waveform (window is already showing the right area).
     */
    fun seekAudioOnly(fraction: Float) {
        if (audioPlayer.state.value == AudioPlayer.PlaybackState.PLAYING) {
            audioPlayer.pause()
        }
        audioPlayer.seekTo(fraction)
    }

    /**
     * Move the window so that the given fraction becomes the window start.
     */
    fun moveWindowToFraction(fraction: Float) {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return
        val windowFractionSize = (_windowSizeSec.value * 1000f) / durationMs
        val newStart = fraction.coerceIn(0f, (1f - windowFractionSize).coerceAtLeast(0f))
        _windowStartFraction.value = newStart
    }

    // ── Note Editing ──────────────────────────────────────────

    fun selectNote(index: Int?) {
        _selectedNoteIndex.value = index
        // If selecting a note, open editor and clear edit cursor
        if (index != null) {
            _isEditorOpen.value = true
            _editCursorFraction.value = null
        }
    }

    fun setEditCursor(fraction: Float?) {
        _editCursorFraction.value = fraction
        // If setting cursor, open editor and clear note selection
        if (fraction != null) {
            _isEditorOpen.value = true
            _selectedNoteIndex.value = null
        }
    }

    fun closeEditor() {
        _isEditorOpen.value = false
        _selectedNoteIndex.value = null
        _editCursorFraction.value = null
    }

    /**
     * Add a note (or chord) at the edit cursor position.
     * Duration is auto-computed: fills remaining time until the next note.
     * Stores guitar tablature (string/fret) as ground truth.
     */
    fun addNote(editorNotes: List<Pair<Int, Pair<Int, Int>>>) { // List of (midiPitch, (stringIndex, fret))
        val cursorFrac = _editCursorFraction.value ?: return
        val currentNotes = _notesList.value.toMutableList()
        val melodyIdea = _idea.value ?: return
        val tempoBpm = melodyIdea.tempoBpm
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return

        val cursorTimeMs = cursorFrac * durationMs

        // Create the new note with auto-duration placeholder (will be recalculated)
        val primaryEntry = editorNotes.minByOrNull { it.first }!!
        val primaryMidi = primaryEntry.first
        val primaryStringRaw = primaryEntry.second.first
        val primaryFret = primaryEntry.second.second
        // Normalize provided string identifier to canonical human 1-based numbering.
        // Prefer treating values as 0-based indices when they fall in that range (UI/editor callers pass indices).
        val primaryString = when {
            primaryStringRaw in com.notenotes.util.GuitarUtils.STRINGS.indices -> com.notenotes.util.GuitarUtils.indexToHuman(primaryStringRaw)
            primaryStringRaw in 1..com.notenotes.util.GuitarUtils.STRINGS.size -> primaryStringRaw
            else -> primaryStringRaw.coerceIn(1, com.notenotes.util.GuitarUtils.STRINGS.size)
        }
        // Create chord entries sorted by MIDI pitch (so chordPitches and chordStringFrets stay in sync)
        val chordEntries = if (editorNotes.size > 1) {
            editorNotes.filter { it.second != primaryEntry.second }.sortedBy { it.first }
        } else emptyList()
        val chordPitches = chordEntries.map { it.first }
        // Normalize chord string identifiers to canonical human numbering
            val chordStringFrets = chordEntries.map { ce ->
            val raw = ce.second.first
            val fret = ce.second.second
            val human = when {
                raw in com.notenotes.util.GuitarUtils.STRINGS.indices -> com.notenotes.util.GuitarUtils.indexToHuman(raw)
                raw in 1..com.notenotes.util.GuitarUtils.STRINGS.size -> raw
                else -> raw.coerceIn(1, com.notenotes.util.GuitarUtils.STRINGS.size)
            }
            Pair(human, fret)
        }

        val newNote = MusicalNote(
            pitches = listOf(primaryMidi) + chordPitches,
            tabPositions = listOf(Pair(primaryString, primaryFret)) + chordStringFrets,
            durationTicks = 4, // placeholder quarter note, will be recalculated
            type = "quarter",
            chordName = if (chordPitches.isNotEmpty()) "Manual Chord" else null,
            velocity = 80,
            isManual = true,
            timePositionMs = cursorTimeMs
        )

        // Insert at position sorted by timePositionMs for manual, or cumulative for auto
        currentNotes.add(newNote)
        
        // Recalculate all durations based on time positions
        val sortedNotes = recalculateNoteDurations(currentNotes, tempoBpm, durationMs)
        updateNotesAndRefresh(sortedNotes)

        val newNoteIndex = sortedNotes.indexOfFirst {
            it.timePositionMs == newNote.timePositionMs && it.pitches == newNote.pitches
        }
        if (newNoteIndex != -1) {
            _selectedNoteIndex.value = newNoteIndex
        }

        Log.i(TAG, "addNote: Added ${com.notenotes.util.PitchUtils.midiToNoteName(primaryMidi)} " +
            "(MIDI $primaryMidi, string=$primaryString, fret=$primaryFret) at ${String.format("%.0f", cursorTimeMs)}ms")

        // Keep editor open but clear cursor for next add
        _editCursorFraction.value = null
    }

    /**
     * Recalculate note durations so each note fills to the next note's time position.
     * Manual notes use their timePositionMs; auto notes use cumulative ticks.
     */
    private fun recalculateNoteDurations(notes: List<MusicalNote>, tempoBpm: Int, durationMs: Int): List<MusicalNote> {
        if (notes.isEmpty()) return notes

        val tickDurationMs = NoteTimingHelper.tickDurationMs(tempoBpm)

        // Compute time positions for all notes
        data class TimedNote(val note: MusicalNote, val timeMs: Float)
        
        val timedNotes = mutableListOf<TimedNote>()
        var cumulativeMs = 0f
        
        for (note in notes) {
            val timeMs = NoteTimingHelper.computeNoteStartMs(note, cumulativeMs)
            timedNotes.add(TimedNote(note, timeMs))
            cumulativeMs = timeMs + note.durationTicks * tickDurationMs
        }

        // Sort by time position
        timedNotes.sortBy { it.timeMs }

        // Recalculate durations: each note fills to the next
        val result = mutableListOf<MusicalNote>()
        for (i in timedNotes.indices) {
            val current = timedNotes[i]
            val nextTimeMs = if (i + 1 < timedNotes.size) timedNotes[i + 1].timeMs else durationMs.toFloat()
            val gapMs = (nextTimeMs - current.timeMs).coerceAtLeast(tickDurationMs) // minimum 1 tick
            val newTicks = (gapMs / tickDurationMs).toInt().coerceAtLeast(1)
            val newType = ticksToType(newTicks)
            result.add(current.note.copy(
                durationTicks = newTicks,
                type = newType,
                timePositionMs = current.timeMs
            ))
        }

        return result
    }

    /**
     * Map tick count to closest note type name.
     */
    private fun ticksToType(ticks: Int): String = NoteTimingHelper.ticksToType(ticks)

    /**
     * Update a note at a specific index with new guitar tab data.
     */
    fun updateNoteAt(index: Int, guitarString: Int, guitarFret: Int) {
        val currentNotes = _notesList.value.toMutableList()
        if (index !in currentNotes.indices) return
        val oldNote = currentNotes[index]
        // Normalize supplied string identifier (may be 0-based index or 1-based human).
        // Prefer index-first normalization for values coming from UI/editor state.
        val human = when {
            guitarString in com.notenotes.util.GuitarUtils.STRINGS.indices -> com.notenotes.util.GuitarUtils.indexToHuman(guitarString)
            guitarString in 1..com.notenotes.util.GuitarUtils.STRINGS.size -> guitarString
            else -> guitarString.coerceIn(1, com.notenotes.util.GuitarUtils.STRINGS.size)
        }
        val newMidi = com.notenotes.util.GuitarUtils.toMidi(human, guitarFret)
        
        // Preserve chord pitches if they exist, but update the primary
        val newPitches = oldNote.pitches.toMutableList()
        val newTabs = oldNote.safeTabPositions.toMutableList()
        
        if (newPitches.isEmpty()) newPitches.add(newMidi)
        else newPitches[0] = newMidi
        
        if (newTabs.isEmpty()) newTabs.add(Pair(human, guitarFret))
        else newTabs[0] = Pair(human, guitarFret)

        currentNotes[index] = oldNote.copy(
            pitches = newPitches,
            tabPositions = newTabs
        )
        updateNotesAndRefresh(currentNotes)
        Log.i(TAG, "updateNoteAt: Updated note $index primary to MIDI $newMidi (string=$guitarString, fret=$guitarFret)")
    }

    /**
     * Update the chord pitches and their string/fret positions for a note at a specific index.
     * fullPitches and fullStringFrets here MUST contain the primary note at index 0.
     */
    fun updateChordPitches(index: Int, fullPitches: List<Int>, fullStringFrets: List<Pair<Int, Int>>) {
        val currentNotes = _notesList.value.toMutableList()
        if (index !in currentNotes.indices) return
        val oldNote = currentNotes[index]
        
        if (fullPitches.isEmpty() || fullStringFrets.isEmpty()) return

        val primaryPitch = fullPitches.first()
        val primaryTabRaw = fullStringFrets.first()
        val primaryTab = Pair(
            when {
                primaryTabRaw.first in com.notenotes.util.GuitarUtils.STRINGS.indices -> com.notenotes.util.GuitarUtils.indexToHuman(primaryTabRaw.first)
                primaryTabRaw.first in 1..com.notenotes.util.GuitarUtils.STRINGS.size -> primaryTabRaw.first
                else -> primaryTabRaw.first.coerceIn(1, com.notenotes.util.GuitarUtils.STRINGS.size)
            }, primaryTabRaw.second
        )

        // Keep the rest of the notes, filtering out accidental duplicate exact strings
        // Sort the rest by pitch for standardized chord rendering
        val restPaired = fullPitches.zip(fullStringFrets).drop(1)
            .map { pair ->
                val raw = pair.second
                val human = when {
                    raw.first in com.notenotes.util.GuitarUtils.STRINGS.indices -> com.notenotes.util.GuitarUtils.indexToHuman(raw.first)
                    raw.first in 1..com.notenotes.util.GuitarUtils.STRINGS.size -> raw.first
                    else -> raw.first.coerceIn(1, com.notenotes.util.GuitarUtils.STRINGS.size)
                }
                Pair(pair.first, Pair(human, raw.second))
            }
            .filter { it.second != primaryTab } // don't duplicate the primary note perfectly
            .distinctBy { it.second.first } // ensure one note per physical guitar string max
            .sortedBy { it.first } // sort by pitch 

        currentNotes[index] = oldNote.copy(
            pitches = listOf(primaryPitch) + restPaired.map { it.first },
            tabPositions = listOf(primaryTab) + restPaired.map { it.second },
            chordName = if (restPaired.isEmpty()) null else (oldNote.chordName ?: "Chord")
        )
        updateNotesAndRefresh(currentNotes)
        Log.i(TAG, "updateChordPitches: Updated chord at $index, pitches=${currentNotes[index].pitches}")
    }

    /**
     * Copy pitch/tab chord content from one note to another, preserving target timing.
     */
    fun copyChordFrom(sourceIndex: Int, targetIndex: Int) {
        val currentNotes = _notesList.value.toMutableList()
        if (sourceIndex !in currentNotes.indices || targetIndex !in currentNotes.indices) return
        if (sourceIndex == targetIndex) return

        val source = currentNotes[sourceIndex]
        val target = currentNotes[targetIndex]

        currentNotes[targetIndex] = target.copy(
            pitches = source.pitches,
            tabPositions = source.safeTabPositions,
            chordName = source.chordName,
            isManual = true
        )

        updateNotesAndRefresh(currentNotes)
        _selectedNoteIndex.value = targetIndex
        Log.i(TAG, "copyChordFrom: Copied note/chord from index=$sourceIndex to index=$targetIndex")
    }

    /**
     * Move a note's start time based on a 0..1 waveform fraction and recalculate durations.
     */
    fun moveNoteToFraction(noteIndex: Int, startFraction: Float) {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return

        val currentNotes = _notesList.value.toMutableList()
        if (noteIndex !in currentNotes.indices) return

        val ideaTempo = _idea.value?.tempoBpm ?: 120
        val oldNote = currentNotes[noteIndex]
        val clampedStart = startFraction.coerceIn(0f, 1f) * durationMs

        currentNotes[noteIndex] = oldNote.copy(
            timePositionMs = clampedStart,
            isManual = true
        )

        val sorted = recalculateNoteDurations(currentNotes, ideaTempo, durationMs)
        updateNotesAndRefresh(sorted)

        val bestMatchIndex = sorted.indices.minByOrNull { idx ->
            val note = sorted[idx]
            val dt = abs((note.timePositionMs ?: 0f) - clampedStart)
            if (note.pitches == oldNote.pitches) dt else dt + 10_000f
        } ?: noteIndex
        _selectedNoteIndex.value = bestMatchIndex
        _editCursorFraction.value = null

        Log.i(TAG, "moveNoteToFraction: Moved note index=$noteIndex to ${String.format("%.0f", clampedStart)}ms")
    }

    /**
     * Split the note under the edit cursor into two notes at the cursor position.
     */
    fun splitNoteAtCursor() {
        val cursorFrac = _editCursorFraction.value ?: return
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return
        val cursorTimeMs = cursorFrac * durationMs
        val currentNotes = _notesList.value.toMutableList()
        val tempoBpm = _idea.value?.tempoBpm ?: 120

        // Find the note that contains the cursor
        val tickDurationMs = NoteTimingHelper.tickDurationMs(tempoBpm)
        var cumulativeMs = 0f

        for (i in currentNotes.indices) {
            val note = currentNotes[i]
            val noteStartMs = NoteTimingHelper.computeNoteStartMs(note, cumulativeMs)
            val noteDurationMs = note.durationTicks * tickDurationMs
            val noteEndMs = noteStartMs + noteDurationMs

            if (cursorTimeMs > noteStartMs + tickDurationMs && cursorTimeMs < noteEndMs - tickDurationMs) {
                // Split this note into two
                val firstDurationMs = cursorTimeMs - noteStartMs
                val secondDurationMs = noteEndMs - cursorTimeMs
                val firstTicks = (firstDurationMs / tickDurationMs).toInt().coerceAtLeast(1)
                val secondTicks = (secondDurationMs / tickDurationMs).toInt().coerceAtLeast(1)

                val firstNote = note.copy(
                    durationTicks = firstTicks,
                    type = ticksToType(firstTicks),
                    timePositionMs = noteStartMs
                )
                val secondNote = note.copy(
                    durationTicks = secondTicks,
                    type = ticksToType(secondTicks),
                    timePositionMs = cursorTimeMs,
                    isManual = true
                )

                currentNotes.removeAt(i)
                currentNotes.add(i, secondNote)
                currentNotes.add(i, firstNote)

                val sorted = recalculateNoteDurations(currentNotes, tempoBpm, durationMs)
                updateNotesAndRefresh(sorted)
                Log.i(TAG, "splitNoteAtCursor: Split note at index $i at ${cursorTimeMs}ms")
                return
            }

            cumulativeMs = noteStartMs + noteDurationMs
        }
        Log.w(TAG, "splitNoteAtCursor: No splittable note found at cursor position")
    }

    /**
     * Get 0..1 fraction indicating how far through the current note playback is.
     */
    fun getPlaybackFractionInNote(progress: Float): Float {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return 0f
        val currentTimeMs = progress * durationMs
        val tempoBpm = _idea.value?.tempoBpm ?: 120
        return NoteTimingHelper.getPlaybackFractionInNote(_notesList.value, currentTimeMs, tempoBpm)
    }

    /**
     * Compute which note index is currently playing at the given playback progress.
     * Uses the same time-mapping logic as the notes tab so cursor walks perfectly in sync.
     */
    fun getCurrentNoteIndex(progress: Float): Int {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return 0
        val currentTimeMs = progress * durationMs
        val tempoBpm = _idea.value?.tempoBpm ?: 120
        return NoteTimingHelper.getCurrentNoteIndex(_notesList.value, currentTimeMs, tempoBpm)
    }

    /**
     * Count synthetic leading-rest beats inserted by MusicXmlGenerator before the first note.
     * This offset keeps alphaTab cursor indexing aligned with rendered score beats.
     */
    private fun refreshLeadingRestBeatCount() {
        val notes = _notesList.value
        val tempoBpm = _idea.value?.tempoBpm ?: 120
        if (notes.isEmpty()) {
            _leadingRestBeatCount.value = 0
            return
        }

        val firstTimeMs = notes.first().timePositionMs
        if (firstTimeMs == null || firstTimeMs <= 0f) {
            _leadingRestBeatCount.value = 0
            return
        }

        val divisions = 4
        val tickMs = 60000.0 / tempoBpm / divisions
        val gapTicks = Math.round(firstTimeMs / tickMs).toInt()
        if (gapTicks <= 0) {
            _leadingRestBeatCount.value = 0
            return
        }

        // Mirror MusicXmlGenerator's greedy decomposition into standard durations.
        val stdTicks = listOf(16, 12, 8, 6, 4, 3, 2, 1)
        var remaining = gapTicks
        var count = 0
        while (remaining > 0) {
            val segment = stdTicks.firstOrNull { it <= remaining }
            if (segment != null) {
                remaining -= segment
                count++
            } else {
                count++
                break
            }
        }
        _leadingRestBeatCount.value = count
    }

    /**
     * Check if the edit cursor is currently inside a note (for showing split button).
     */
    fun isCursorInsideNote(): Boolean {
        val cursorFrac = _editCursorFraction.value ?: return false
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return false
        val cursorTimeMs = cursorFrac * durationMs
        val tempoBpm = _idea.value?.tempoBpm ?: 120
        return NoteTimingHelper.isCursorInsideNote(_notesList.value, cursorTimeMs, tempoBpm)
    }

    /**
     * Delete the currently selected note.
     */
    fun deleteSelectedNote(selectAdjacent: Boolean = true) {
        val idx = _selectedNoteIndex.value ?: return
        val currentNotes = _notesList.value.toMutableList()
        if (idx !in currentNotes.indices) return

        val removed = currentNotes.removeAt(idx)
        val removedPitch = removed.pitches.firstOrNull() ?: 0
        Log.i(TAG, "deleteNote: Removed ${com.notenotes.util.PitchUtils.midiToNoteName(removedPitch)} " +
            "(MIDI $removedPitch) at index $idx")

        updateNotesAndRefresh(currentNotes)
        if (!selectAdjacent) {
            _selectedNoteIndex.value = null
            return
        }

        // LIFO: select previous note, or next if first was deleted, or null if empty
        _selectedNoteIndex.value = when {
            currentNotes.isEmpty() -> null
            idx > 0 -> idx - 1
            else -> 0
        }
    }

    /**
     * Clear all notes with confirmation (called after user confirms dialog).
     */
    fun clearAllNotes() {
        Log.i(TAG, "clearAllNotes: Clearing ${_notesList.value.size} notes")
        _selectedNoteIndex.value = null
        _editCursorFraction.value = null
        updateNotesAndRefresh(emptyList())
    }

    /**
     * Rename the idea title.
     */
    fun renameIdea(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val melodyIdea = _idea.value ?: return@launch
            val updated = melodyIdea.copy(title = trimmed)
            dao.update(updated)
            _idea.value = updated
            Log.i(TAG, "renameIdea: Renamed to '$trimmed'")
        }
    }

    fun openRenameDialog() { _isRenameDialogOpen.value = true }
    fun closeRenameDialog() { _isRenameDialogOpen.value = false }

    /**
     * Update the instrument and regenerate MusicXML.
     */
    fun updateInstrument(instrument: InstrumentProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            val melodyIdea = _idea.value ?: return@launch
            val updated = melodyIdea.copy(instrument = instrument.name)
            dao.update(updated)
            _idea.value = updated
            // Regenerate MusicXML with new instrument (clef, transposition)
            updateNotesAndRefresh(_notesList.value)
            Log.i(TAG, "updateInstrument: Changed to ${instrument.displayName}")
        }
    }

    /** Update the notes list, regenerate MusicXML, and persist notes to DB. */
    fun updateNotesAndRefresh(notes: List<MusicalNote>, skipFileRegen: Boolean = false) {
        // Update UI state synchronously so callers (and unit tests) observe changes immediately.
        val sanitized = MusicalNote.sanitizeList(notes)
        // Keep notes sorted by timePosition when available
        val sorted = sanitized.sortedWith(compareBy({ it.timePositionMs ?: Float.MAX_VALUE }, { it.durationTicks }))

        _notesList.value = sorted
        refreshLeadingRestBeatCount()

        // Persist notes and regenerate MusicXML asynchronously to avoid blocking UI.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Persist notes JSON into DB so future loads prefer notes over raw XML
                val melodyIdea = _idea.value
                if (melodyIdea != null) {
                    val updatedIdea = melodyIdea.copy(notes = gson.toJson(sorted))
                    dao.update(updatedIdea)
                    _idea.value = updatedIdea
                }

                // Regenerate MusicXML for the UI (always), and write to file only when not skipped.
                val result = buildResult() ?: return@launch
                currentResult = result
                try {
                    val xml = MusicXmlSanitizer.sanitize(musicXmlGenerator.generateMusicXml(result))
                    _musicXml.value = xml
                    if (!skipFileRegen) {
                        // If idea already points to a file, attempt to overwrite it
                        melodyIdea?.musicXmlFilePath?.let { path ->
                            try { java.io.File(path).writeText(xml) } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "updateNotesAndRefresh: MusicXML generation failed", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "updateNotesAndRefresh: Error", e)
                _errorMessage.value = "Error updating notes: ${e.message}"
            }
        }
    }

    /**
     * Save all files (MIDI, MusicXML, Audio) to the user's configured directory,
     * falling back to Downloads/NoteNotes. Files are saved into an idea-named subfolder.
     */
    fun saveToDevice(context: Context, customName: String? = null) {
        val melodyIdea = _idea.value ?: return
        val rawName = customName?.takeIf { it.isNotBlank() } ?: melodyIdea.title
        val folderName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        performSave(context, folderName, overwrite = true) // The UI layer checks for overwrite warning
    }

    /**
     * Checks if a folder already exists under the proposed custom name. 
     */
    fun doesSaveDirectoryExist(context: Context, customName: String): Boolean {
        if (customName.isBlank()) return false
        val folderName = customName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val saveDir = resolveSaveDir(context)
        val ideaDir = File(saveDir, folderName)
        return ideaDir.exists() && ideaDir.list()?.isNotEmpty() == true
    }

    /** Resolve the base save directory from SharedPreferences or default Downloads/NoteNotes. */
    private fun resolveSaveDir(context: Context): File {
        val prefs = context.getSharedPreferences("notenotes_settings", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("save_path_uri", null)
        if (uriStr != null) {
            try {
                val treeUri = android.net.Uri.parse(uriStr)
                val path = getPathFromTreeUri(treeUri)
                if (path != null) {
                    val dir = File(path)
                    if (dir.exists() || dir.mkdirs()) return dir
                }
            } catch (e: Exception) {
                Log.w(TAG, "Custom save path unavailable, using default", e)
            }
        }
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NoteNotes"
        )
    }

    /** Extract a real filesystem path from a tree URI (works for primary storage). */
    private fun getPathFromTreeUri(uri: android.net.Uri): String? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":")
        return when {
            parts.size == 2 && parts[0] == "primary" -> {
                "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
            }
            parts.size == 1 && parts[0] == "primary" -> {
                Environment.getExternalStorageDirectory().absolutePath
            }
            else -> null
        }
    }

    /** Actually write all files into [folderName] under the resolved save dir. */
    private fun performSave(context: Context, folderName: String, overwrite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val melodyIdea = _idea.value ?: return@launch
                val saveDir = resolveSaveDir(context)
                val ideaDir = File(saveDir, folderName)

                if (overwrite && ideaDir.exists()) {
                    ideaDir.listFiles()?.forEach { it.delete() }
                }
                ideaDir.mkdirs()

                val saveName = folderName

                var savedCount = 0
                val savedPaths = mutableListOf<String>()
                val savedMimes = mutableListOf<String>()

                // Save audio
                melodyIdea.audioFilePath.let { path ->
                    val src = File(path)
                    if (src.exists()) {
                        val ext = src.extension.ifEmpty { "wav" }
                        val dest = File(ideaDir, "$saveName.$ext")
                        src.copyTo(dest, overwrite = true)
                        savedPaths.add(dest.absolutePath)
                        savedMimes.add(when (ext.lowercase()) {
                            "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"
                            "ogg" -> "audio/ogg"; "flac" -> "audio/flac"
                            else -> "audio/wav"
                        })
                        savedCount++
                    }
                }

                // Save MIDI
                melodyIdea.midiFilePath?.let { path ->
                    val src = File(path)
                    if (src.exists()) {
                        val dest = File(ideaDir, "$saveName.mid")
                        src.copyTo(dest, overwrite = true)
                        savedPaths.add(dest.absolutePath)
                        savedMimes.add("audio/midi")
                        savedCount++
                    }
                }

                // Save MusicXML
                melodyIdea.musicXmlFilePath?.let { path ->
                    val src = File(path)
                    if (src.exists()) {
                        val dest = File(ideaDir, "$saveName.musicxml")
                        src.copyTo(dest, overwrite = true)
                        savedPaths.add(dest.absolutePath)
                        savedMimes.add("application/xml")
                        savedCount++
                    }
                }

                // Scan saved files so they appear in the SAF file picker immediately
                if (savedPaths.isNotEmpty()) {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        savedPaths.toTypedArray(),
                        savedMimes.toTypedArray(),
                        null
                    )
                }

                // Save last export path to DB
                val updatedIdea = melodyIdea.copy(lastExportPath = ideaDir.absolutePath)
                dao.update(updatedIdea)
                _idea.value = updatedIdea

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Saved $savedCount files to ${ideaDir.absolutePath}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                Log.i(TAG, "saveToDevice: Saved $savedCount files to ${ideaDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "saveToDevice: Error", e)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Save failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Open the Downloads/NoteNotes folder in the system file manager.
     */
    fun openInFileManager(context: Context, targetPath: String? = null) {
        try {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NoteNotes"
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            var baseUriString = "content://com.android.externalstorage.documents/document/primary:Download%2FNoteNotes"
            if (targetPath != null && targetPath.contains("Download/NoteNotes/")) {
                val subPath = targetPath.substringAfter("Download/NoteNotes/")
                if (subPath.isNotBlank()) {
                    val encodedSubPath = android.net.Uri.encode(subPath)
                    baseUriString += "%2F$encodedSubPath"
                }
            }

            // Try the standard Documents UI
            val uri = android.net.Uri.parse(baseUriString)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open Downloads root
            try {
                val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "openInFileManager: Could not open file manager", e2)
                android.widget.Toast.makeText(
                    context,
                    "Files saved to Downloads/NoteNotes",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Update the key signature and regenerate MusicXML.
     */
    fun updateKeySignature(key: KeySignature) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val melodyIdea = _idea.value ?: return@launch
                val updated = melodyIdea.copy(keySignature = key.toString())
                dao.update(updated)
                _idea.value = updated
                // Refresh sheet music without touching export files
                updateNotesAndRefresh(_notesList.value, skipFileRegen = true)
                Log.i(TAG, "updateKeySignature: Changed to $key")
            } catch (e: Exception) {
                Log.e(TAG, "updateKeySignature: Error", e)
                _errorMessage.value = "Error saving key signature: ${e.message}"
            }
        }
    }

    /**
     * Update the time signature and regenerate MusicXML.
     */
    fun updateTimeSignature(ts: TimeSignature) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val melodyIdea = _idea.value ?: return@launch
                val updated = melodyIdea.copy(timeSignature = ts.toString())
                dao.update(updated)
                _idea.value = updated
                // Refresh sheet music without touching export files
                updateNotesAndRefresh(_notesList.value, skipFileRegen = true)
                Log.i(TAG, "updateTimeSignature: Changed to $ts")
            } catch (e: Exception) {
                Log.e(TAG, "updateTimeSignature: Error", e)
                _errorMessage.value = "Error saving time signature: ${e.message}"
            }
        }
    }

    /**
     * Update the tempo BPM and regenerate MusicXML.
     */
    fun updateTempoBpm(bpm: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val melodyIdea = _idea.value ?: return@launch
                val oldBpm = melodyIdea.tempoBpm
                if (bpm == oldBpm) return@launch

                val scaledNotes = _notesList.value.map { note ->
                    val newTicks = Math.round(note.durationTicks.toDouble() * bpm / oldBpm).toInt().coerceAtLeast(1)
                    note.copy(durationTicks = newTicks)
                }

                val updated = melodyIdea.copy(tempoBpm = bpm)
                dao.update(updated)
                _idea.value = updated
                // Refresh sheet music without touching export files
                updateNotesAndRefresh(scaledNotes, skipFileRegen = true)
                Log.i(TAG, "updateTempoBpm: Changed $oldBpm -> $bpm")
            } catch (e: Exception) {
                Log.e(TAG, "updateTempoBpm: Error", e)
                _errorMessage.value = "Error saving tempo: ${e.message}"
            }
        }
    }

    // Window navigation
    fun setWindowStartFraction(fraction: Float) {
        _windowStartFraction.value = fraction.coerceIn(0f, 1f)
    }

    fun toggleWindowLock() {
        _isWindowLocked.value = !_isWindowLocked.value
    }

    fun setWindowSizeSec(sec: Float) {
        _windowSizeSec.value = sec.coerceIn(1f, 30f)
    }

    /** Move the window by a delta in units of window-width (delta=1 = one full window). */
    fun moveWindow(delta: Float) {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return
        val windowFractionSize = (_windowSizeSec.value * 1000f) / durationMs
        val newStart = (_windowStartFraction.value + delta * windowFractionSize)
            .coerceIn(0f, (1f - windowFractionSize).coerceAtLeast(0f))
        _windowStartFraction.value = newStart
    }

    /** Pan the window so that the given global fraction is centered (where possible). */
    fun panWindowTo(globalFraction: Float) {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return
        val windowFractionSize = (_windowSizeSec.value * 1000f) / durationMs
        val half = windowFractionSize / 2f
        val targetStart = (globalFraction - half).coerceIn(0f, (1f - windowFractionSize).coerceAtLeast(0f))
        _windowStartFraction.value = targetStart
    }

    /** Auto-scroll window to follow playback unless the window is locked. */
    fun updateWindowForPlayback(playbackFraction: Float) {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return
        if (_isWindowLocked.value) return

        val windowFractionSize = (_windowSizeSec.value * 1000f) / durationMs
        val start = _windowStartFraction.value
        val end = (start + windowFractionSize).coerceAtMost(1f)

        if (playbackFraction < start || playbackFraction > end) {
            // Center playback in the window when it's outside view
            val half = windowFractionSize / 2f
            val newStart = (playbackFraction - half).coerceIn(0f, (1f - windowFractionSize).coerceAtLeast(0f))
            _windowStartFraction.value = newStart
        }
    }

    /**
     * Auto-detect and apply the BPM and time signature for the first time
     * a user views the sheet music or notes tab.
     */
                fun autoDetectAndApplySongParameters() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val melodyIdea = _idea.value ?: return@launch
                val oldBpm = melodyIdea.tempoBpm

                val bpm = autoDetectBpm() ?: return@launch

                val scaledNotes = _notesList.value.map { note ->
                    val newTicks = Math.round(note.durationTicks.toDouble() * bpm / oldBpm).toInt().coerceAtLeast(1)
                    note.copy(durationTicks = newTicks)
                }

                val onsetsSec = mutableListOf<Double>()
                val allPitches = mutableListOf<Int>()

                var cumMs = 0.0
                val tickMs = 60000.0 / bpm / 4.0

                for (note in scaledNotes) {
                    if (note.isRest) {
                        cumMs += note.durationTicks * tickMs
                        continue
                    }
                    val onsetMs = if (note.isManual && note.timePositionMs != null) {
                        note.timePositionMs.toDouble()
                    } else {
                        cumMs
                    }

                    onsetsSec.add(onsetMs / 1000.0)
                    allPitches.addAll(note.pitches)

                    cumMs = onsetMs + note.durationTicks * tickMs
                }

                val tsDetector = com.notenotes.processing.TimeSignatureDetector()
                val ts = tsDetector.detectTimeSignature(onsetsSec, null, bpm.toDouble())
                
                val keyDetector = com.notenotes.processing.KeyDetector()
                val keySig = keyDetector.detectKey(allPitches)

                val updated = melodyIdea.copy(
                    tempoBpm = bpm,
                    timeSignature = ts.toString(),
                    keySignature = keySig.toString()
                )
                dao.update(updated)
                _idea.value = updated
                updateNotesAndRefresh(scaledNotes, skipFileRegen = true)
                android.util.Log.i(TAG, "autoDetectAndApplySongParameters: Applied ${bpm} BPM, ${ts}, ${keySig}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "autoDetectAndApplySongParameters: Error", e)
            }
        }
    }

    /**
     * Auto-detect BPM from the current note list using inter-onset interval (IOI) analysis.
     * Examines time gaps between consecutive note onsets and finds the beat period that
     * best explains the majority of intervals (within a tolerance window).
     * Returns a BPM in the 30–300 range, or null if detection fails.
     */
    fun autoDetectBpm(): Int? {
        val notes = _notesList.value
        if (notes.size < 3) return null

        val currentBpm = _idea.value?.tempoBpm ?: 120
        // ms per tick at current BPM is relative
        val tickMs = 60000.0 / currentBpm / 4.0

        // Compute note onset times in ms
        val onsets = mutableListOf<Double>()
        var cumMs = 0.0
        for (note in notes) {
            if (note.isRest) {
                cumMs += note.durationTicks * tickMs
                continue
            }
            // For auto-detect BPM we must rely solely on the original timePositionMs or fallback
            val onsetMs = note.timePositionMs?.toDouble() ?: cumMs
            onsets.add(onsetMs)
            cumMs = onsetMs + (note.durationTicks * tickMs)
        }

        if (onsets.size < 3) return null

        // Compute inter-onset intervals
        val iois = mutableListOf<Double>()
        for (i in 1 until onsets.size) {
            val ioi = onsets[i] - onsets[i - 1]
            if (ioi > 50) iois.add(ioi) // ignore intervals < 50ms (likely ornaments)
        }
        if (iois.isEmpty()) return null

        // Test candidate BPMs from 30..300 and score each
        var bestBpm = 120
        var bestScore = -1.0
        for (candidateBpm in 30..300) {
            val beatMs = 60000.0 / candidateBpm
            var score = 0.0
            for (ioi in iois) {
                // How close is this IOI to an integer multiple of the beat?
                val ratio = ioi / beatMs
                val nearestInt = Math.round(ratio).toDouble()
                if (nearestInt < 1) continue
                val deviation = Math.abs(ratio - nearestInt) / nearestInt
                if (deviation < 0.15) { // 15% tolerance
                    score += 1.0 / nearestInt // weight direct beats higher than multiples
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestBpm = candidateBpm
            }
        }

        return if (bestScore > 0) bestBpm else null
    }    fun autoDetectKey(): com.notenotes.model.KeySignature {
        val notes = _notesList.value
        val allPitches = mutableListOf<Int>()
        for (n in notes) {
            if (!n.isRest) {
                allPitches.addAll(n.pitches)
            }
        }
        val detector = com.notenotes.processing.KeyDetector()
        return detector.detectKey(allPitches)
    }

    fun autoDetectTimeSignature(bpm: Int? = null): com.notenotes.model.TimeSignature {
        val notes = _notesList.value
        val currentBpm = bpm ?: _idea.value?.tempoBpm ?: 120
        val onsetsSec = mutableListOf<Double>()
        var cumMs = 0.0
        val tickMs = 60000.0 / currentBpm / 4.0
        for (note in notes) {
            if (note.isRest) {
                cumMs += note.durationTicks * tickMs
                continue
            }
            val onsetMs = if (note.isManual && note.timePositionMs != null) {
                note.timePositionMs.toDouble()
            } else {
                cumMs
            }
            onsetsSec.add(onsetMs / 1000.0)
            cumMs = onsetMs + note.durationTicks * tickMs
        }
        val detector = com.notenotes.processing.TimeSignatureDetector()
        return detector.detectTimeSignature(onsetsSec, null, currentBpm.toDouble())
    }

    
}











