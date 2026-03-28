package com.notenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.util.GuitarUtils
import com.notenotes.util.PitchUtils
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.window.Dialog
import com.notenotes.utils.NoteTextUtils

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
    tempoBpm: Int = 120,
    onAddNote: (notes: List<Pair<Int, Pair<Int, Int>>>) -> Unit,
    onDeleteSelected: (() -> Unit)?,
    hasSelectedNote: Boolean,
    selectedNote: com.notenotes.model.MusicalNote? = null,
    selectedNoteIndex: Int? = null,
    canSplitAtCursor: Boolean = false,
    onSplitAtCursor: (() -> Unit)? = null,
    onUpdateNote: ((Int, Int, Int) -> Unit)? = null,           // (index, guitarString, guitarFret)
    onUpdateChordNote: ((Int, List<Int>, List<Pair<Int, Int>>) -> Unit)? = null,  // (index, pitches, stringFrets)
    allNotes: List<com.notenotes.model.MusicalNote> = emptyList(),
    isMoveMode: Boolean = false,
    onToggleMoveMode: (() -> Unit)? = null,
    onCopyFromNote: ((Int) -> Unit)? = null,
    onPendingChangesChanged: ((Boolean, androidx.compose.ui.text.AnnotatedString?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state = rememberGuitarChordEditState(selectedNote)
    var showCopyFromMenu by remember(selectedNoteIndex) { mutableStateOf(false) }
    var showDeleteWholeChordConfirm by remember { mutableStateOf(false) }      

    LaunchedEffect(hasSelectedNote, selectedNoteIndex, state) {
        androidx.compose.runtime.snapshotFlow {
            if (hasSelectedNote && selectedNoteIndex != null && state.hasPendingChanges && selectedNote != null) {
                val originalText = NoteTextUtils.buildPitchFretAnnotated(selectedNote, isStrikethrough = true)
                val newPitches = listOf(state.editedPrimaryMidi) + state.editableChordPitches
                val newPositions = listOf(Pair(state.editedPrimaryString, state.editedPrimaryFret)) + state.editableChordPositions
                val dummyNote = selectedNote.copy(pitches = newPitches, tabPositions = newPositions)
                val newText = NoteTextUtils.buildPitchFretAnnotated(dummyNote)
                
                val combined = androidx.compose.ui.text.buildAnnotatedString {
                    append(originalText)
                    append(" -> ")
                    append(newText)
                }
                true to combined
            } else {
                false to null
            }
        }.collect { (hasPending, pendingText) ->
            onPendingChangesChanged?.invoke(hasPending, pendingText)
        }
    }

    if (showDeleteWholeChordConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteWholeChordConfirm = false },
            title = { Text("Delete Chord?") },
            text = { Text("This is the last note in the chord. Deleting it will delete the whole chord.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteWholeChordConfirm = false
                        onDeleteSelected?.invoke()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete Chord") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWholeChordConfirm = false }) { Text("Cancel") }
            }
        )
    }

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
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            // Header: note preview + time position
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Note preview
                Text(
                    text = if (hasSelectedNote) "Edit Selected Note" else "Place New Note",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(horizontalAlignment = Alignment.End) {
                    if (selectedNote != null) {
                        val seconds = selectedNote.durationTicks * (60f / tempoBpm.coerceAtLeast(1) / 4f)
                        Text(
                            text = String.format("Dur: %.2fs", seconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (editCursorActive) {
                        Text(
                            text = "@ ${String.format("%.2f", timePointSeconds)}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            GuitarNoteEditor(
                state = state,
                hasSelectedNote = hasSelectedNote,
                onDeleteWholeNoteWarning = { showDeleteWholeChordConfirm = true }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onToggleMoveMode != null) {
                    OutlinedButton(
                        onClick = onToggleMoveMode,
                        modifier = Modifier.weight(1f),
                        enabled = hasSelectedNote,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isMoveMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (isMoveMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text(if (isMoveMode) "Move ON" else "Move Chord", fontSize = 11.sp, maxLines = 1)
                    }
                }

                if (onCopyFromNote != null && allNotes.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showCopyFromMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = true,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Text("Copy From", fontSize = 11.sp, maxLines = 1)
                        }

                        if (showCopyFromMenu) {
                            CopyFromDialog(
                                allNotes = allNotes,
                                selectedNoteIndex = selectedNoteIndex,
                                timePointSeconds = timePointSeconds,
                                onDismissRequest = { showCopyFromMenu = false },
                                onCopyFromNote = onCopyFromNote
                            )
                        }
                    }
                }

                if (hasSelectedNote && selectedNoteIndex != null) {
                    Button(
                        onClick = {
                            val idx = selectedNoteIndex
                            onUpdateNote?.invoke(idx, state.editedPrimaryString, state.editedPrimaryFret)
                            onUpdateChordNote?.invoke(idx, state.getFullPitches(), state.getFullPositions())
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text("Confirm", fontSize = 11.sp, maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = {
                            val targetMidi = GuitarUtils.toMidi(state.selectedStringIndex, state.selectedFret)
                            val pairs = listOf(Pair(targetMidi, Pair(state.selectedStringIndex, state.selectedFret)))
                            onAddNote(pairs)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = editCursorActive,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text("Place Note", fontSize = 11.sp, maxLines = 1)
                    }
                }
            }

            if (!hasSelectedNote && canSplitAtCursor && onSplitAtCursor != null) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onSplitAtCursor,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Split at Cursor", fontSize = 12.sp)
                }
            }

            if (hasSelectedNote && onDeleteSelected != null) {
                Spacer(modifier = Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = onDeleteSelected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete Chord", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun CopyFromDialog(
    allNotes: List<com.notenotes.model.MusicalNote>,
    selectedNoteIndex: Int?,
    timePointSeconds: Float,
    onDismissRequest: () -> Unit,
    onCopyFromNote: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val distinctNotes = remember(allNotes, selectedNoteIndex) {
        val groups = linkedMapOf<String, Int>()
        allNotes.forEachIndexed { idx, note ->
            if (selectedNoteIndex == null || idx != selectedNoteIndex) {
                // Ignore empty rest notes
                if (!note.isRest && note.pitches.isNotEmpty()) {
                    val key = note.pitches.joinToString(",") + "|" + note.tabPositions.joinToString(",") { "${it.first}-${it.second}" }
                    if (!groups.containsKey(key)) {
                        groups[key] = idx
                    }
                }
            }
        }
        groups.map { it.value } // Return the original indices
    }
    
    val filteredIndices = remember(distinctNotes, searchQuery) {
        if (searchQuery.isBlank()) distinctNotes
        else distinctNotes.filter { idx -> 
            val note = allNotes[idx]
            val noteLabel = NoteTextUtils.buildPitchFretAnnotated(note).text
            noteLabel.contains(searchQuery, ignoreCase = true)
        }
    }
    
    val listState = rememberLazyListState()
    
    LaunchedEffect(Unit) {
        // Find previous note index
        val prevIdx = if (selectedNoteIndex != null && selectedNoteIndex > 0) {
            selectedNoteIndex - 1
        } else if (selectedNoteIndex == null) {
            // Find note right before cursor
            val timeMs = timePointSeconds * 1000f
            // We assume notes are chronological. But timePositionMs can be null.
            var lastIdx = -1
            var accumulated = 0f
            // Let us just find the last note whose start time is before timeMs
            for (i in allNotes.indices) {
                val note = allNotes[i]
                val t = note.timePositionMs?.toFloat() ?: accumulated
                if (t <= timeMs) {
                    lastIdx = i
                } else {
                    break
                }
                accumulated = t + (note.durationTicks * (60f / 120f) * 1000f / 4f) // Rough estimate
            }
            if (lastIdx >= 0) lastIdx else null
        } else {
            null
        }
        
        if (prevIdx != null && prevIdx >= 0 && prevIdx < allNotes.size) {
            val note = allNotes[prevIdx]
            val key = note.pitches.joinToString(",") + "|" + note.tabPositions.joinToString(",") { "${it.first}-${it.second}" }
            
            // Find in filtered list
            val targetIdxInFiltered = filteredIndices.indexOfFirst {
                val n = allNotes[it]
                val k = n.pitches.joinToString(",") + "|" + n.tabPositions.joinToString(",") { p -> "${p.first}-${p.second}" }
                k == key
            }
            if (targetIdxInFiltered >= 0) {
                listState.scrollToItem(targetIdxInFiltered)
            }
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Copy Chord", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search chords...") },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                ) {
                    itemsIndexed(filteredIndices) { _, originalIdx ->
                        val note = allNotes[originalIdx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCopyFromNote(originalIdx)
                                    onDismissRequest()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        ) {
                            Text(NoteTextUtils.buildPitchFretAnnotated(note))
                        }
                    }
                }
            }
        }
    }
}


