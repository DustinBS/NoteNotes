package com.notenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.util.GuitarUtils
import com.notenotes.util.PitchUtils

/**
 * Represents a single note being built in the editor.
 */
data class EditorNote(
    val stringIndex: Int,  // 0-based index into GuitarUtils.STRINGS
    val fret: Int,
    val midiPitch: Int = GuitarUtils.toMidi(stringIndex, fret)
)

/**
 * Always-visible guitar note editor panel.
 * Duration is auto-calculated (fill until next note).
 *
 * @param editCursorActive Whether an edit cursor is placed (enables Add)
 * @param timePointSeconds The time position where the note will be inserted
 * @param onAddNote Called with list of (midiPitch, (stringIndex, fret)) pairs
 * @param onDeleteSelected Called when user wants to delete selected note
 * @param onClearAll Called when user wants to clear all notes
 * @param hasSelectedNote True if there's a note selected on the waveform
 */
@Composable
fun NoteEditorPanel(
    editCursorActive: Boolean,
    timePointSeconds: Float,
    onAddNote: (notes: List<Pair<Int, Pair<Int, Int>>>) -> Unit,
    onDeleteSelected: (() -> Unit)?,
    onClearAll: () -> Unit,
    hasSelectedNote: Boolean,
    selectedNote: com.notenotes.model.MusicalNote? = null,
    selectedNoteIndex: Int? = null,
    canSplitAtCursor: Boolean = false,
    onSplitAtCursor: (() -> Unit)? = null,
    onUpdateNote: ((Int, Int, Int) -> Unit)? = null,           // (index, guitarString, guitarFret)
    onUpdateChordNote: ((Int, List<Int>, List<Pair<Int, Int>>) -> Unit)? = null,  // (index, pitches, stringFrets)
    modifier: Modifier = Modifier
) {
    var selectedStringIndex by remember { mutableIntStateOf(selectedNote?.guitarString ?: 0) }
    var selectedFret by remember { mutableIntStateOf(selectedNote?.guitarFret ?: 0) }
    val chordNotes = remember { mutableStateListOf<EditorNote>() }

    // Which chord note is being edited: -1 = primary, 0..n = chord pitch index, null = not editing chord
    var editingChordNoteIndex by remember { mutableIntStateOf(-1) }

    // Sync when a different note is selected
    LaunchedEffect(selectedNote) {
        if (selectedNote != null) {
            selectedStringIndex = selectedNote.guitarString ?: 0
            selectedFret = selectedNote.guitarFret ?: 0
            editingChordNoteIndex = -1  // Reset to primary
        }
    }

    // Real-time update when string/fret changes for an existing selected note
    LaunchedEffect(selectedStringIndex, selectedFret) {
        if (selectedNote == null || selectedNoteIndex == null) return@LaunchedEffect
        if (selectedNote.isChord) {
            // Build updated chord pitch lists
            // Primary note
            val primaryString = selectedNote.guitarString ?: 0
            val primaryFret = selectedNote.guitarFret ?: 0
            val safeChordSF = selectedNote.safeChordStringFrets
            // Build full list: primary + chord notes
            val fullPitches = mutableListOf(selectedNote.midiPitch)
            val fullStringFrets = mutableListOf(Pair(primaryString, primaryFret))
            for (i in selectedNote.chordPitches.indices) {
                fullPitches.add(selectedNote.chordPitches[i])
                fullStringFrets.add(if (i < safeChordSF.size) safeChordSF[i] else Pair(0, 0))
            }
            // Apply current edit
            val editIdx = editingChordNoteIndex
            if (editIdx in fullPitches.indices) {
                val newMidi = GuitarUtils.toMidi(selectedStringIndex, selectedFret)
                if (fullPitches[editIdx] == newMidi && fullStringFrets[editIdx] == Pair(selectedStringIndex, selectedFret)) return@LaunchedEffect
                fullPitches[editIdx] = newMidi
                fullStringFrets[editIdx] = Pair(selectedStringIndex, selectedFret)
            }
            // If editing primary (index 0): update primary + rebuild chord
            if (editIdx == 0) {
                onUpdateNote?.invoke(selectedNoteIndex, selectedStringIndex, selectedFret)
            }
            // Always update chord pitches (includes potential reorder)
            onUpdateChordNote?.invoke(selectedNoteIndex, fullPitches, fullStringFrets)
        } else {
            // Single note — just update directly
            val newMidi = GuitarUtils.toMidi(selectedStringIndex, selectedFret)
            if (newMidi != selectedNote.midiPitch || selectedStringIndex != (selectedNote.guitarString ?: 0) || selectedFret != (selectedNote.guitarFret ?: 0)) {
                onUpdateNote?.invoke(selectedNoteIndex, selectedStringIndex, selectedFret)
            }
        }
    }

    val currentMidi = GuitarUtils.toMidi(selectedStringIndex, selectedFret)
    val currentNoteName = PitchUtils.midiToNoteName(currentMidi)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // Selected note info display
            if (hasSelectedNote && selectedNote != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (selectedNote.isChord) {
                            // Chord header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (selectedNote.chordName != null) "Chord: ${selectedNote.chordName}" else "Chord",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (selectedNote.hasDuplicateStrings) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = "Duplicate strings",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Same string!",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Clickable chord note chips
                            val stringNames = arrayOf("E2", "A2", "D3", "G3", "B3", "E4")
                            val primaryString = selectedNote.guitarString ?: 0
                            val primaryFret = selectedNote.guitarFret ?: 0
                            val safeChordSF = selectedNote.safeChordStringFrets

                            // Build full note list: primary + chord pitches
                            data class ChordNoteInfo(val pitch: Int, val stringIdx: Int, val fret: Int, val index: Int)
                            val allChordNotes = mutableListOf(
                                ChordNoteInfo(selectedNote.midiPitch, primaryString, primaryFret, 0)
                            )
                            for (i in selectedNote.chordPitches.indices) {
                                val sf = if (i < safeChordSF.size) safeChordSF[i] else Pair(0, 0)
                                allChordNotes.add(ChordNoteInfo(selectedNote.chordPitches[i], sf.first, sf.second, i + 1))
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                allChordNotes.forEachIndexed { idx, cn ->
                                    val isEditing = editingChordNoteIndex == idx
                                    val noteName = PitchUtils.midiToNoteName(cn.pitch)
                                    val sName = if (cn.stringIdx in stringNames.indices) stringNames[cn.stringIdx] else "?"
                                    FilterChip(
                                        selected = isEditing,
                                        onClick = {
                                            editingChordNoteIndex = idx
                                            selectedStringIndex = cn.stringIdx
                                            selectedFret = cn.fret
                                        },
                                        label = {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(noteName, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("$sName F${cn.fret}", fontSize = 9.sp)
                                            }
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }

                            // Add note to chord button
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (selectedNoteIndex == null) return@OutlinedButton
                                        // Find unused string
                                        val usedStrings = allChordNotes.map { it.stringIdx }.toSet()
                                        val unusedString = (0..5).firstOrNull { it !in usedStrings } ?: 0
                                        val newMidi = GuitarUtils.toMidi(unusedString, 0)
                                        val newPitches = allChordNotes.map { it.pitch } + newMidi
                                        val newStringFrets = allChordNotes.map { Pair(it.stringIdx, it.fret) } + Pair(unusedString, 0)
                                        onUpdateChordNote?.invoke(selectedNoteIndex, newPitches, newStringFrets)
                                        // Switch to editing new note
                                        editingChordNoteIndex = allChordNotes.size
                                        selectedStringIndex = unusedString
                                        selectedFret = 0
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Add Note", fontSize = 10.sp)
                                }
                                if (allChordNotes.size > 1 && editingChordNoteIndex >= 0) {
                                    OutlinedButton(
                                        onClick = {
                                            if (selectedNoteIndex == null) return@OutlinedButton
                                            val removeIdx = editingChordNoteIndex
                                            val remaining = allChordNotes.filterIndexed { i, _ -> i != removeIdx }
                                            if (remaining.isEmpty()) return@OutlinedButton
                                            val newPitches = remaining.map { it.pitch }
                                            val newStringFrets = remaining.map { Pair(it.stringIdx, it.fret) }
                                            onUpdateChordNote?.invoke(selectedNoteIndex, newPitches, newStringFrets)
                                            editingChordNoteIndex = 0
                                            selectedStringIndex = remaining[0].stringIdx
                                            selectedFret = remaining[0].fret
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text("Remove", fontSize = 10.sp)
                                    }
                                }
                            }
                        } else {
                            // Single note display
                            Text(
                                text = "Selected Note",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val noteName = PitchUtils.midiToNoteName(selectedNote.midiPitch)
                                Text(
                                    text = noteName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "MIDI ${selectedNote.midiPitch}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (selectedNote.hasTab) {
                                    val stringNames = arrayOf("E2", "A2", "D3", "G3", "B3", "E4")
                                    val sName = if ((selectedNote.guitarString ?: 0) in stringNames.indices)
                                        stringNames[selectedNote.guitarString!!] else "?"
                                    Text(
                                        text = "String: $sName  Fret: ${selectedNote.guitarFret ?: 0}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                if (selectedNote.timePositionMs != null) {
                                    Text(
                                        text = "@ ${String.format("%.2f", selectedNote.timePositionMs / 1000f)}s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = selectedNote.type + if (selectedNote.dotted) "." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Header: note preview + time position
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Note preview
                Text(
                    text = "$currentNoteName · MIDI $currentMidi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (editCursorActive) {
                    Text(
                        text = "@ ${String.format("%.2f", timePointSeconds)}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Guitar string selector
            Text(
                "String",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GuitarUtils.STRINGS.forEachIndexed { index, gs ->
                    val isSelected = index == selectedStringIndex
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedStringIndex = index },
                        label = { Text(gs.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Fret selector
            Text(
                "Fret: $selectedFret",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            val fretScrollState = rememberScrollState()
            val density = LocalDensity.current
            // Auto-scroll to selected fret
            LaunchedEffect(selectedFret) {
                with(density) {
                    val targetPx = (selectedFret * 35.dp.toPx()).toInt() // 32dp + 3dp spacing
                    val viewportCenter = (fretScrollState.viewportSize / 2)
                    fretScrollState.animateScrollTo(maxOf(0, targetPx - viewportCenter))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(fretScrollState),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (fret in 0..GuitarUtils.MAX_FRET) {
                    val isSelected = fret == selectedFret
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { selectedFret = fret },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = fret.toString(),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Chord notes area
            if (chordNotes.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Chord:", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    chordNotes.forEach { note ->
                        val noteName = PitchUtils.midiToNoteName(note.midiPitch)
                        InputChip(
                            selected = false,
                            onClick = { chordNotes.remove(note) },
                            label = { Text("$noteName - MIDI ${note.midiPitch}", fontSize = 10.sp) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp))
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Same-string conflict warning
            if (chordNotes.isNotEmpty()) {
                val allStringsUsed = chordNotes.map { it.stringIndex }
                val hasDuplicateStrings = allStringsUsed.size != allStringsUsed.toSet().size
                val currentStringConflict = chordNotes.any { it.stringIndex == selectedStringIndex }

                if (hasDuplicateStrings) {
                    Text(
                        text = "\u26A0 Warning: Multiple notes on the same string!",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                } else if (currentStringConflict) {
                    Text(
                        text = "\u26A0 String ${GuitarUtils.STRINGS[selectedStringIndex].label} already used in chord",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Add to chord
                OutlinedButton(
                    onClick = {
                        val note = EditorNote(selectedStringIndex, selectedFret)
                        if (chordNotes.none { it.midiPitch == note.midiPitch }) {
                            chordNotes.add(note)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Chord", fontSize = 12.sp)
                }

                // Add note / chord
                Button(
                    onClick = {
                        val allNotes = if (chordNotes.isEmpty()) {
                            listOf(EditorNote(selectedStringIndex, selectedFret))
                        } else {
                            chordNotes.toList()
                        }
                        val pairs = allNotes.map { Pair(it.midiPitch, Pair(it.stringIndex, it.fret)) }
                        onAddNote(pairs)
                        chordNotes.clear()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = editCursorActive,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        if (chordNotes.size > 1) "Add Chord" else "Add Note",
                        fontSize = 12.sp
                    )
                }

                // Delete selected
                if (hasSelectedNote && onDeleteSelected != null) {
                    FilledTonalButton(
                        onClick = onDeleteSelected,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Delete", fontSize = 12.sp)
                    }
                }

                // Split at cursor
                if (canSplitAtCursor && onSplitAtCursor != null) {
                    OutlinedButton(
                        onClick = onSplitAtCursor,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Split", fontSize = 12.sp)
                    }
                }

                // Clear all
                FilledTonalButton(
                    onClick = onClearAll,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Clear", fontSize = 12.sp)
                }
            }
        }
    }
}
