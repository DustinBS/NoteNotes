package com.notenotes.ui.components

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.notenotes.model.MusicalNote
import com.notenotes.util.GuitarUtils

/**
 * Manages the editing state for a guitar chord/note, including:
 * - Primary note (string, fret, MIDI pitch)
 * - Chord pitches and their string/fret positions
 * - Unsaved changes tracking
 * - Duplicate string validation
 * - Complex pitch removal (promoting chord notes to primary when needed)
 */
class GuitarChordEditState(
    originalNote: MusicalNote?
) {
    // --- Editable state ---
    // Store UI-facing values as human 1-based string numbers (1 = High E, 6 = Low E)
    // Normalize incoming note tab positions (which may be stored as human or index) into human numbers.
    private val _initialHumanPositions: List<Pair<Int, Int>> = run {
        val note = originalNote ?: return@run emptyList()
        // Use the canonical human view from the model which normalizes legacy data.
        note.safeTabPositionsAsHuman
    }

    var editedPrimaryString by mutableIntStateOf(_initialHumanPositions.firstOrNull()?.first ?: GuitarUtils.STRINGS.size)
    var editedPrimaryFret by mutableIntStateOf(originalNote?.safeTabPositionsAsHuman?.firstOrNull()?.second ?: 0)
    
    val editableChordPitches: SnapshotStateList<Int> = mutableStateListOf()
    val editableChordPositions: SnapshotStateList<Pair<Int, Int>> = mutableStateListOf()
    
    // --- UI interaction state ---
    var selectedStringIndex by mutableIntStateOf(_initialHumanPositions.firstOrNull()?.first ?: GuitarUtils.STRINGS.size)
    var selectedFret by mutableIntStateOf(_initialHumanPositions.firstOrNull()?.second ?: 0)
    var editingChordPitchIndex by mutableStateOf<Int?>(null)
    
    // --- Original state for change detection ---
    // Original primary stored as human 1-based number
    val originalPrimaryString: Int = _initialHumanPositions.firstOrNull()?.first ?: GuitarUtils.STRINGS.size
    val originalPrimaryFret: Int = _initialHumanPositions.firstOrNull()?.second ?: 0
    val originalPrimaryMidi: Int = originalNote?.pitches?.firstOrNull() ?: 0
    val originalChordPitches: List<Int> = originalNote?.pitches?.drop(1)?.toList() ?: emptyList()
        val originalChordPositions: List<Pair<Int, Int>> = run {
        val note = originalNote ?: return@run emptyList()
        val pitches = note.pitches.drop(1)
            val safeHuman = _initialHumanPositions.drop(1)
        pitches.mapIndexed { i, pitch ->
            val stored = safeHuman.getOrNull(i)
            if (stored != null) stored
            else {
                val fm = GuitarUtils.fromMidi(pitch)
                if (fm != null) {
                    val human = fm.first
                    Pair(human, fm.second)
                } else Pair(GuitarUtils.STRINGS.size, 0)
            }
        }
    }
    
    // --- Derived state ---
    val editedPrimaryMidi: Int
        get() = GuitarUtils.toMidiHuman(editedPrimaryString, editedPrimaryFret)
    
    val hasPendingChanges: Boolean
        get() {
            return editedPrimaryString != originalPrimaryString ||
                editedPrimaryFret != originalPrimaryFret ||
                editableChordPitches.toList() != originalChordPitches ||
                editableChordPositions.toList() != originalChordPositions
        }
    
    val hasDuplicateStrings: Boolean
        get() {
            val allStrings = mutableListOf(editedPrimaryString)
            editableChordPositions.forEach { pos ->
                allStrings.add(pos.first)
            }
            return allStrings.size != allStrings.toSet().size
        }
    
    init {
        // Initialize from original note
        originalNote?.let { note ->
            editableChordPitches.addAll(note.pitches.drop(1))
            val tabPos = _initialHumanPositions.drop(1)
            val pitches = note.pitches.drop(1)
            pitches.forEachIndexed { i, pitch ->
                val stored = tabPos.getOrNull(i)
                if (stored != null) {
                    editableChordPositions.add(stored)
                } else {
                    val fm = GuitarUtils.fromMidi(pitch)
                    if (fm != null) {
                        editableChordPositions.add(Pair(fm.first, fm.second))
                    } else {
                        editableChordPositions.add(Pair(GuitarUtils.STRINGS.size, 0))
                    }
                }
            }
        }
    }
    
    /**
     * Add a new chord note at the given string and fret.
     */
    fun addChordNote(stringIndex: Int, fret: Int) {
        // Caller provides a human 1-based string number. Validate and coerce.
        val human = stringIndex.coerceIn(1, GuitarUtils.STRINGS.size)
        val midiPitch = GuitarUtils.toMidiHuman(human, fret)
        editableChordPitches.add(midiPitch)
        editableChordPositions.add(Pair(human, fret))
        editingChordPitchIndex = editableChordPitches.size - 1
        selectedStringIndex = human
        selectedFret = fret
    }
    
    /**
     * Remove a chord note at the given index.
     */
    fun removeChordNote(chordIndex: Int) {
        if (chordIndex in editableChordPitches.indices) {
            editableChordPitches.removeAt(chordIndex)
            editableChordPositions.removeAt(chordIndex)
            editingChordPitchIndex = null
            selectedStringIndex = editedPrimaryString
            selectedFret = editedPrimaryFret
        }
    }
    
    /**
     * Remove the primary note. If there are chord notes, promote the first one to primary.
     * Otherwise, triggers a "delete whole chord" scenario that the UI caller must handle.
     */
    fun removePrimaryNote(): Boolean {
        return if (editableChordPitches.isNotEmpty()) {
            val promotedPitch = editableChordPitches.removeAt(0)
            val promotedSf = editableChordPositions.removeAt(0)
            editedPrimaryString = promotedSf.first
            editedPrimaryFret = promotedSf.second
            editingChordPitchIndex = null
            selectedStringIndex = promotedSf.first
            selectedFret = promotedSf.second
            true  // Promoted successfully
        } else {
            false  // No chord notes to promote — signal to delete whole chord
        }
    }

    /**
     * Updates the currently active note (either primary or chord) to the new string and fret,
     * and updates the selector state to match.
     */
    fun updateSelectedStringFret(stringIndex: Int, fret: Int) {
        // Caller provides human 1-based string number
        val human = stringIndex.coerceIn(1, GuitarUtils.STRINGS.size)
        selectedStringIndex = human
        selectedFret = fret
        
        val idx = editingChordPitchIndex
        if (idx == null) {
            editedPrimaryString = human
            editedPrimaryFret = fret
        } else if (idx in editableChordPitches.indices) {
            val newMidi = GuitarUtils.toMidiHuman(human, fret)
            editableChordPitches[idx] = newMidi
            editableChordPositions[idx] = Pair(human, fret)
        }
    }

    /**
     * Build the raw pitch list for saving.
     */
    fun getFullPitches(): List<Int> {
        val full = mutableListOf(editedPrimaryMidi)
        full.addAll(editableChordPitches)
        return full
    }
    
    /**
     * Build the raw tab position pairs list for saving.
     */
    fun getFullPositions(): List<Pair<Int, Int>> {
        // Return human 1-based string numbers for persistence
        val full = mutableListOf(Pair(editedPrimaryString, editedPrimaryFret))
        full.addAll(editableChordPositions)
        return full
    }
    
    /**
     * Switch editing focus to a specific chord note by index.
     * Updates selectedStringIndex and selectedFret accordingly.
     */
    fun selectChordNote(chordIndex: Int) {
        if (chordIndex in editableChordPositions.indices) {
            editingChordPitchIndex = chordIndex
            val pos = editableChordPositions[chordIndex]
            selectedStringIndex = pos.first
            selectedFret = pos.second
        }
    }
    
    /**
     * Switch editing focus to the primary note.
     * Updates selectedStringIndex and selectedFret accordingly.
     */
    fun selectPrimaryNote() {
        editingChordPitchIndex = null
        selectedStringIndex = editedPrimaryString
        selectedFret = editedPrimaryFret
    }
}

/**
 * Compose helper to create and remember a GuitarChordEditState.
 */
@Composable
fun rememberGuitarChordEditState(initialNote: MusicalNote?): GuitarChordEditState {
    return remember(initialNote?.pitches, initialNote?.safeTabPositionsAsHuman) {
        GuitarChordEditState(initialNote)
    }
}
