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
    // Store UI-facing values as internal 0-based indices (0 = low E)
    var editedPrimaryString by mutableIntStateOf(originalNote?.safeTabPositionsAsIndex?.firstOrNull()?.first ?: 0)
    var editedPrimaryFret by mutableIntStateOf(originalNote?.safeTabPositionsAsIndex?.firstOrNull()?.second ?: 0)
    
    val editableChordPitches: SnapshotStateList<Int> = mutableStateListOf()
    val editableChordPositions: SnapshotStateList<Pair<Int, Int>> = mutableStateListOf()
    
    // --- UI interaction state ---
    var selectedStringIndex by mutableIntStateOf(originalNote?.safeTabPositionsAsIndex?.firstOrNull()?.first ?: 0)
    var selectedFret by mutableIntStateOf(originalNote?.safeTabPositionsAsIndex?.firstOrNull()?.second ?: 0)
    var editingChordPitchIndex by mutableStateOf<Int?>(null)
    
    // --- Original state for change detection ---
    val originalPrimaryString: Int = originalNote?.safeTabPositionsAsIndex?.firstOrNull()?.first ?: 0
    val originalPrimaryFret: Int = originalNote?.safeTabPositionsAsIndex?.firstOrNull()?.second ?: 0
    val originalPrimaryMidi: Int = originalNote?.pitches?.firstOrNull() ?: 0
    val originalChordPitches: List<Int> = originalNote?.pitches?.drop(1)?.toList() ?: emptyList()
    val originalChordPositions: List<Pair<Int, Int>> = run {
        val note = originalNote ?: return@run emptyList()
        val pitches = note.pitches.drop(1)
        // Use the index-aligned view (0-based indices) for original positions
        val safeTabPos = note.safeTabPositionsAsIndex.drop(1)
        pitches.mapIndexed { i, pitch ->
            val stored = safeTabPos.getOrNull(i)
            if (stored != null) stored
            else {
                val fm = GuitarUtils.fromMidi(pitch)
                if (fm != null) {
                    val idx = GuitarUtils.humanToIndex(fm.first) ?: 0
                    Pair(idx, fm.second)
                } else Pair(0, 0)
            }
        }
    }
    
    // --- Derived state ---
    val editedPrimaryMidi: Int
        get() = GuitarUtils.toMidi(GuitarUtils.indexToHuman(editedPrimaryString), editedPrimaryFret)
    
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
            val pitches = note.pitches.drop(1)
            val tabPos = note.safeTabPositionsAsIndex.drop(1)
            pitches.forEachIndexed { i, pitch ->
                val stored = tabPos.getOrNull(i)
                if (stored != null) {
                    editableChordPositions.add(stored)
                } else {
                    val fm = GuitarUtils.fromMidi(pitch)
                    if (fm != null) {
                        val idx = GuitarUtils.humanToIndex(fm.first) ?: 0
                        editableChordPositions.add(Pair(idx, fm.second))
                    } else {
                        editableChordPositions.add(Pair(0, 0))
                    }
                }
            }
        }
    }
    
    /**
     * Add a new chord note at the given string and fret.
     */
    fun addChordNote(stringIndex: Int, fret: Int) {
        val human = when {
            stringIndex in GuitarUtils.STRINGS.indices -> GuitarUtils.indexToHuman(stringIndex)
            stringIndex in 1..GuitarUtils.STRINGS.size -> stringIndex
            else -> stringIndex.coerceIn(1, GuitarUtils.STRINGS.size)
        }
        val midiPitch = GuitarUtils.toMidi(human, fret)
        editableChordPitches.add(midiPitch)
        editableChordPositions.add(Pair(stringIndex, fret))
        editingChordPitchIndex = editableChordPitches.size - 1
        selectedStringIndex = stringIndex
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
        selectedStringIndex = stringIndex
        selectedFret = fret
        
        val idx = editingChordPitchIndex
        if (idx == null) {
            editedPrimaryString = stringIndex
            editedPrimaryFret = fret
        } else if (idx in editableChordPitches.indices) {
            val newMidi = GuitarUtils.toMidi(GuitarUtils.indexToHuman(stringIndex), fret)
            editableChordPitches[idx] = newMidi
            editableChordPositions[idx] = Pair(stringIndex, fret)
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
    return remember(initialNote?.pitches, initialNote?.tabPositions) {
        GuitarChordEditState(initialNote)
    }
}
