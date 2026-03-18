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
    onPendingChangesChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state = rememberGuitarChordEditState(selectedNote)
    var showCopyFromMenu by remember(selectedNoteIndex) { mutableStateOf(false) }
    var showDeleteWholeChordConfirm by remember { mutableStateOf(false) }

    SideEffect {
        onPendingChangesChanged?.invoke(hasSelectedNote && selectedNoteIndex != null && state.hasPendingChanges)
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
                            enabled = hasSelectedNote,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Text("Copy From", fontSize = 11.sp, maxLines = 1)
                        }

                        DropdownMenu(
                            expanded = showCopyFromMenu,
                            onDismissRequest = { showCopyFromMenu = false }
                        ) {
                            allNotes.forEachIndexed { idx, note ->
                                if (selectedNoteIndex != null && idx == selectedNoteIndex) return@forEachIndexed
                                val noteLabel = if (note.isChord) {
                                    note.pitches.joinToString(" ") { pitch -> PitchUtils.midiToNoteName(pitch) }
                                } else {
                                    PitchUtils.midiToNoteName(note.pitches.firstOrNull() ?: 0)
                                }
                                DropdownMenuItem(
                                    text = { Text("#${idx + 1} $noteLabel") },
                                    onClick = {
                                        onCopyFromNote(idx)
                                        showCopyFromMenu = false
                                    }
                                )
                            }
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
