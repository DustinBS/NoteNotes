package com.notenotes.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notenotes.audio.AudioPlayer
import com.notenotes.audio.WavReader
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
import com.notenotes.util.NoteTimingHelper
import com.notenotes.util.TranspositionUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import android.os.Environment

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
                    _musicXml.value = musicXmlGenerator.generateMusicXml(result)
                    _notesList.value = notes
                    Log.d(TAG, "loadIdea: MusicXML regenerated successfully")
                } else {
                    // Fallback: try loading from saved file (no notes in DB)
                    val xmlFile = melodyIdea.musicXmlFilePath?.let { File(it) }
                    if (xmlFile != null && xmlFile.exists() && xmlFile.length() > 0) {
                        val content = xmlFile.readText()
                        if (content.trimStart().startsWith("<?xml") || content.trimStart().startsWith("<score-partwise")) {
                            Log.d(TAG, "loadIdea: Loading MusicXML from file (${xmlFile.length()} bytes)")
                            _musicXml.value = content
                        } else {
                            Log.w(TAG, "loadIdea: File exists but doesn't look like valid MusicXML")
                        }
                    } else {
                        Log.w(TAG, "loadIdea: No stored notes and no valid XML file!")
                    }
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
        val primaryString = primaryEntry.second.first
        val primaryFret = primaryEntry.second.second
        // Create chord entries sorted by MIDI pitch (so chordPitches and chordStringFrets stay in sync)
        val chordEntries = if (editorNotes.size > 1) {
            editorNotes.filter { it.first != primaryMidi }.sortedBy { it.first }
        } else emptyList()
        val chordPitches = chordEntries.map { it.first }
        val chordStringFrets = chordEntries.map { Pair(it.second.first, it.second.second) }

        val newNote = MusicalNote(
            midiPitch = primaryMidi,
            durationTicks = 4, // placeholder quarter note, will be recalculated
            type = "quarter",
            chordPitches = chordPitches,
            chordStringFrets = chordStringFrets,
            chordName = if (chordPitches.isNotEmpty()) "Manual Chord" else null,
            velocity = 80,
            guitarString = primaryString,
            guitarFret = primaryFret,
            isManual = true,
            timePositionMs = cursorTimeMs
        )

        // Insert at position sorted by timePositionMs for manual, or cumulative for auto
        currentNotes.add(newNote)
        
        // Recalculate all durations based on time positions
        val sortedNotes = recalculateNoteDurations(currentNotes, tempoBpm, durationMs)
        updateNotesAndRefresh(sortedNotes)

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
        val newMidi = com.notenotes.util.GuitarUtils.toMidi(guitarString, guitarFret)
        currentNotes[index] = oldNote.copy(
            midiPitch = newMidi,
            guitarString = guitarString,
            guitarFret = guitarFret
        )
        updateNotesAndRefresh(currentNotes)
        Log.i(TAG, "updateNoteAt: Updated note $index to MIDI $newMidi (string=$guitarString, fret=$guitarFret)")
    }

    /**
     * Update the chord pitches and their string/fret positions for a note at a specific index.
     */
    fun updateChordPitches(index: Int, newChordPitches: List<Int>, newChordStringFrets: List<Pair<Int, Int>>) {
        val currentNotes = _notesList.value.toMutableList()
        if (index !in currentNotes.indices) return
        val oldNote = currentNotes[index]
        // Filter out the primary pitch and keep positions in sync
        val paired = newChordPitches.zip(newChordStringFrets)
        val filtered = paired.filter { it.first != oldNote.midiPitch }.sortedBy { it.first }
        currentNotes[index] = oldNote.copy(
            chordPitches = filtered.map { it.first },
            chordStringFrets = filtered.map { it.second },
            chordName = if (filtered.isEmpty()) null else (oldNote.chordName ?: "Chord")
        )
        updateNotesAndRefresh(currentNotes)
        Log.i(TAG, "updateChordPitches: Updated chord at $index, pitches=${filtered.map { it.first }}")
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
    fun deleteSelectedNote() {
        val idx = _selectedNoteIndex.value ?: return
        val currentNotes = _notesList.value.toMutableList()
        if (idx !in currentNotes.indices) return

        val removed = currentNotes.removeAt(idx)
        Log.i(TAG, "deleteNote: Removed ${com.notenotes.util.PitchUtils.midiToNoteName(removed.midiPitch)} " +
            "(MIDI ${removed.midiPitch}) at index $idx")

        updateNotesAndRefresh(currentNotes)
        _selectedNoteIndex.value = null
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
     * Update the key signature and regenerate MusicXML.
     */
    fun updateKeySignature(key: KeySignature) {
        viewModelScope.launch(Dispatchers.IO) {
            val melodyIdea = _idea.value ?: return@launch
            val updated = melodyIdea.copy(keySignature = key.toString())
            dao.update(updated)
            _idea.value = updated
            // Regenerate MusicXML with new key
            updateNotesAndRefresh(_notesList.value)
            Log.i(TAG, "updateKeySignature: Changed to $key")
        }
    }

    /**
     * Update the time signature and regenerate MusicXML.
     */
    fun updateTimeSignature(ts: TimeSignature) {
        viewModelScope.launch(Dispatchers.IO) {
            val melodyIdea = _idea.value ?: return@launch
            val updated = melodyIdea.copy(timeSignature = ts.toString())
            dao.update(updated)
            _idea.value = updated
            // Regenerate MusicXML with new time signature
            updateNotesAndRefresh(_notesList.value)
            Log.i(TAG, "updateTimeSignature: Changed to $ts")
        }
    }

    /**
     * Update the tempo BPM and regenerate MusicXML.
     */
    fun updateTempoBpm(bpm: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val melodyIdea = _idea.value ?: return@launch
            val updated = melodyIdea.copy(tempoBpm = bpm)
            dao.update(updated)
            _idea.value = updated
            // Regenerate MusicXML with new tempo
            updateNotesAndRefresh(_notesList.value)
            Log.i(TAG, "updateTempoBpm: Changed to $bpm")
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

    /**
     * Move window by delta windows (positive = forward, negative = backward).
     */
    fun moveWindow(deltaWindows: Float) {
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return
        val windowFractionSize = (_windowSizeSec.value * 1000f) / durationMs
        val newStart = (_windowStartFraction.value + deltaWindows * windowFractionSize).coerceIn(0f, (1f - windowFractionSize).coerceAtLeast(0f))
        _windowStartFraction.value = newStart
    }

    /**
     * Auto-scroll window to follow playback (unless locked).
     */
    fun updateWindowForPlayback(playbackFraction: Float) {
        if (_isWindowLocked.value) return
        val durationMs = _audioDurationMs.value
        if (durationMs <= 0) return
        val windowFractionSize = (_windowSizeSec.value * 1000f) / durationMs
        val windowEnd = _windowStartFraction.value + windowFractionSize
        if (playbackFraction < _windowStartFraction.value || playbackFraction > windowEnd) {
            _windowStartFraction.value = (playbackFraction - windowFractionSize * 0.1f).coerceIn(0f, (1f - windowFractionSize).coerceAtLeast(0f))
        }
    }

    /** Debounce job for coalescing rapid edits into a single disk write. */
    private var saveJob: kotlinx.coroutines.Job? = null

    /**
     * After adding or deleting notes, refresh all derived state (MusicXML, exports, DB).
     * Debounces disk writes — rapid edits within 300ms are coalesced.
     */
    private fun updateNotesAndRefresh(notes: List<MusicalNote>) {
        _notesList.value = notes

        // Cancel any pending save — the latest edit wins
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            // Brief debounce so rapid successive edits don't each trigger disk I/O
            kotlinx.coroutines.delay(300)
            try {
                val melodyIdea = _idea.value ?: return@launch
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

                // Regenerate MusicXML
                val xmlContent = musicXmlGenerator.generateMusicXml(result)
                _musicXml.value = xmlContent

                // Regenerate export files
                val title = melodyIdea.title
                val externalMusicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "NoteNotes"
                )
                val exportsDir = File(externalMusicDir, "exports")
                exportsDir.mkdirs()

                val midiFile = File(exportsDir, "$title.mid")
                midiWriter.writeToFile(result, midiFile)

                val xmlFile = File(exportsDir, "$title.musicxml")
                musicXmlGenerator.writeToFile(result, xmlFile)

                // Update database
                val updatedIdea = melodyIdea.copy(
                    notes = gson.toJson(notes),
                    midiFilePath = midiFile.absolutePath,
                    musicXmlFilePath = xmlFile.absolutePath
                )
                dao.update(updatedIdea)
                _idea.value = updatedIdea

                Log.i(TAG, "updateNotesAndRefresh: Saved ${notes.size} notes")
            } catch (e: Exception) {
                Log.e(TAG, "updateNotesAndRefresh: Error", e)
                _errorMessage.value = "Error saving: ${e.message}"
            }
        }
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
                val externalMusicDir2 = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "NoteNotes"
                )
                val exportsDir = File(externalMusicDir2, "exports")
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
        val result = WavReader.readSamples(file)
        if (result == null) Log.e(TAG, "readWavSamples: Failed to read ${file.name}")
        return result
    }

    private fun buildResult(): TranscriptionResult? {
        currentResult?.let { return it }
        
        val melodyIdea = _idea.value ?: return null
        val notesJson = melodyIdea.notes ?: return null
        
        val notesType = object : TypeToken<List<MusicalNote>>() {}.type
        val notes: List<MusicalNote> = MusicalNote.sanitizeList(
            gson.fromJson(notesJson, notesType)
        )
        
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
        val wavData = WavReader.readFull(file)
        if (wavData == null) {
            Log.w(TAG, "loadWaveformData: Could not read ${file.absolutePath}")
            return
        }
        _waveformData.value = WaveformData.fromSamples(wavData.samples, wavData.sampleRate, 2000)
        _audioDurationMs.value = wavData.durationMs
        Log.d(TAG, "loadWaveformData: Loaded ${wavData.samples.size} samples, ${wavData.durationMs}ms")
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
