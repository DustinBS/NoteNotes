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
    var selectedStringIndex by remember { mutableIntStateOf(selectedNote?.guitarString ?: 0) }
    var selectedFret by remember { mutableIntStateOf(selectedNote?.guitarFret ?: 0) }
    var showCopyFromMenu by remember(selectedNoteIndex) { mutableStateOf(false) }
    var showDeleteWholeChordConfirm by remember { mutableStateOf(false) }

    // Which chord note is being edited: 0 = primary, 1..n = chord pitch index
    // Tracked by (stringIndex, fret) identity — unique per guitar string, avoids MIDI pitch collisions
    var editingChordNoteIndex by remember { mutableIntStateOf(0) }
    var editingTargetStringFret by remember { mutableStateOf(Pair(selectedNote?.guitarString ?: 0, selectedNote?.guitarFret ?: 0)) }

    var draftPrimaryMidi by remember { mutableIntStateOf(selectedNote?.midiPitch ?: 0) }
    var draftPrimaryString by remember { mutableIntStateOf(selectedNote?.guitarString ?: 0) }
    var draftPrimaryFret by remember { mutableIntStateOf(selectedNote?.guitarFret ?: 0) }
    val draftChordPitches = remember { mutableStateListOf<Int>() }
    val draftChordStringFrets = remember { mutableStateListOf<Pair<Int, Int>>() }

    var originalPrimaryMidi by remember { mutableIntStateOf(selectedNote?.midiPitch ?: 0) }
    var originalPrimaryString by remember { mutableIntStateOf(selectedNote?.guitarString ?: 0) }
    var originalPrimaryFret by remember { mutableIntStateOf(selectedNote?.guitarFret ?: 0) }
    val originalChordPitches = remember { mutableStateListOf<Int>() }
    val originalChordStringFrets = remember { mutableStateListOf<Pair<Int, Int>>() }

    val hasPendingChanges by remember {
        derivedStateOf {
            if (!hasSelectedNote || selectedNoteIndex == null) return@derivedStateOf false
            draftPrimaryMidi != originalPrimaryMidi ||
                draftPrimaryString != originalPrimaryString ||
                draftPrimaryFret != originalPrimaryFret ||
                draftChordPitches.toList() != originalChordPitches.toList() ||
                draftChordStringFrets.toList() != originalChordStringFrets.toList()
        }
    }

    SideEffect {
        onPendingChangesChanged?.invoke(hasSelectedNote && selectedNoteIndex != null && hasPendingChanges)
    }

    LaunchedEffect(
        selectedNoteIndex,
        selectedNote?.midiPitch,
        selectedNote?.guitarString,
        selectedNote?.guitarFret,
        selectedNote?.chordPitches,
        selectedNote?.chordStringFrets
    ) {
        val note = selectedNote
        if (selectedNoteIndex == null || note == null) {
            draftChordPitches.clear()
            draftChordStringFrets.clear()
            originalChordPitches.clear()
            originalChordStringFrets.clear()
            return@LaunchedEffect
        }

        val primaryString = note.guitarString ?: 0
        val primaryFret = note.guitarFret ?: 0
        val safeChordSF = note.safeChordStringFrets

        draftPrimaryMidi = note.midiPitch
        draftPrimaryString = primaryString
        draftPrimaryFret = primaryFret
        draftChordPitches.clear()
        draftChordPitches.addAll(note.chordPitches)
        draftChordStringFrets.clear()
        draftChordStringFrets.addAll(
            note.chordPitches.indices.map { idx ->
                if (idx < safeChordSF.size) safeChordSF[idx] else Pair(0, 0)
            }
        )

        originalPrimaryMidi = note.midiPitch
        originalPrimaryString = primaryString
        originalPrimaryFret = primaryFret
        originalChordPitches.clear()
        originalChordPitches.addAll(draftChordPitches)
        originalChordStringFrets.clear()
        originalChordStringFrets.addAll(draftChordStringFrets)

        selectedStringIndex = primaryString
        selectedFret = primaryFret
        editingChordNoteIndex = 0
        editingTargetStringFret = Pair(primaryString, primaryFret)
    }

    // Update local draft only. Changes are committed by explicit confirm action.
    LaunchedEffect(selectedStringIndex, selectedFret, editingChordNoteIndex, selectedNoteIndex, hasSelectedNote) {
        if (!hasSelectedNote || selectedNoteIndex == null) return@LaunchedEffect
        val newMidi = GuitarUtils.toMidi(selectedStringIndex, selectedFret)
        if (editingChordNoteIndex == 0) {
            draftPrimaryMidi = newMidi
            draftPrimaryString = selectedStringIndex
            draftPrimaryFret = selectedFret
            editingTargetStringFret = Pair(selectedStringIndex, selectedFret)
        } else {
            val chordIdx = editingChordNoteIndex - 1
            if (chordIdx in draftChordPitches.indices) {
                draftChordPitches[chordIdx] = newMidi
                draftChordStringFrets[chordIdx] = Pair(selectedStringIndex, selectedFret)
                editingTargetStringFret = Pair(selectedStringIndex, selectedFret)
            }
        }
    }

    val currentMidi = GuitarUtils.toMidi(selectedStringIndex, selectedFret)

    fun normalizedStringLabel(rawLabel: String): String {
        val trimmed = rawLabel.trim()
        val withoutPrefix = trimmed.dropWhile { it.isDigit() || it.isWhitespace() }
        val notePart = withoutPrefix
            .takeWhile { it.isLetter() || it == '#' || it == 'b' }
            .ifBlank { withoutPrefix }
        return notePart.ifBlank { trimmed }
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
                ) { Text("Delete") }
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

            Text(
                text = "String Selector - Tap a string row to add/edit notes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(1.dp))

            fun formatEntry(entry: Triple<Int, Int, Int>): String {
                val pitch = entry.first
                val stringIndex = entry.second
                val fret = entry.third
                val rawLabel = GuitarUtils.STRINGS.getOrNull(stringIndex)?.label ?: "?"
                val stringLabel = normalizedStringLabel(rawLabel)
                val stringNumber = (GuitarUtils.STRINGS.size - stringIndex).coerceAtLeast(1)
                return "${PitchUtils.midiToNoteName(pitch)} | $stringNumber $stringLabel Fret $fret | MIDI $pitch"
            }

            val hasDuplicateStrings = draftChordStringFrets.map { it.first }.contains(draftPrimaryString) ||
                draftChordStringFrets.map { it.first }.size != draftChordStringFrets.map { it.first }.toSet().size

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (index in GuitarUtils.STRINGS.indices.reversed()) {
                    val gs = GuitarUtils.STRINGS[index]
                    val rowSelected = index == selectedStringIndex
                    val sColor = Color(gs.colorArgb)

                    val currentChordIndex = draftChordStringFrets.indexOfFirst { it.first == index }
                    val currentEntry = when {
                        hasSelectedNote && index == draftPrimaryString -> Triple(draftPrimaryMidi, draftPrimaryString, draftPrimaryFret)
                        currentChordIndex >= 0 -> {
                            val sf = draftChordStringFrets[currentChordIndex]
                            val pitch = draftChordPitches.getOrElse(currentChordIndex) { GuitarUtils.toMidi(sf.first, sf.second) }
                            Triple(pitch, sf.first, sf.second)
                        }
                        !hasSelectedNote && index == selectedStringIndex -> Triple(currentMidi, selectedStringIndex, selectedFret)
                        else -> null
                    }

                    val originalChordIndex = originalChordStringFrets.indexOfFirst { it.first == index }
                    val originalEntry = when {
                        hasSelectedNote && index == originalPrimaryString -> Triple(originalPrimaryMidi, originalPrimaryString, originalPrimaryFret)
                        originalChordIndex >= 0 -> {
                            val sf = originalChordStringFrets[originalChordIndex]
                            val pitch = originalChordPitches.getOrElse(originalChordIndex) { GuitarUtils.toMidi(sf.first, sf.second) }
                            Triple(pitch, sf.first, sf.second)
                        }
                        else -> null
                    }

                    val isActive = currentEntry != null
                    val changed = hasSelectedNote && currentEntry != originalEntry
                    val rowHighlighted = isActive
                    val canRemove = hasSelectedNote && (
                        currentChordIndex >= 0 || index == draftPrimaryString
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val existingChordIndex = draftChordStringFrets.indexOfFirst { it.first == index }
                                when {
                                    hasSelectedNote && index == draftPrimaryString -> {
                                        editingChordNoteIndex = 0
                                        editingTargetStringFret = Pair(draftPrimaryString, draftPrimaryFret)
                                        selectedStringIndex = draftPrimaryString
                                        selectedFret = draftPrimaryFret
                                    }
                                    hasSelectedNote && existingChordIndex >= 0 -> {
                                        val sf = draftChordStringFrets[existingChordIndex]
                                        editingChordNoteIndex = existingChordIndex + 1
                                        editingTargetStringFret = sf
                                        selectedStringIndex = sf.first
                                        selectedFret = sf.second
                                    }
                                    hasSelectedNote && selectedNoteIndex != null -> {
                                        val newFret = 0
                                        draftChordPitches.add(GuitarUtils.toMidi(index, newFret))
                                        draftChordStringFrets.add(Pair(index, newFret))
                                        editingChordNoteIndex = draftChordPitches.size
                                        editingTargetStringFret = Pair(index, newFret)
                                        selectedStringIndex = index
                                        selectedFret = newFret
                                    }
                                    else -> {
                                        selectedStringIndex = index
                                        if (currentEntry != null) selectedFret = currentEntry.third
                                    }
                                }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (rowHighlighted) sColor.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            width = if (rowSelected) 2.dp else 1.dp,
                            color = if (rowSelected) MaterialTheme.colorScheme.primary
                            else if (rowHighlighted) sColor
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (changed && originalEntry != null) {
                                    Text(
                                        text = formatEntry(originalEntry),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                }

                                if (currentEntry != null) {
                                    Text(
                                        text = if (changed) {
                                            "-> ${formatEntry(currentEntry)}"
                                        } else {
                                            formatEntry(currentEntry)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Normal,
                                        color = if (isActive) sColor else MaterialTheme.colorScheme.onSurface
                                    )
                                } else {
                                    val movedTargetEntry = if (hasSelectedNote && changed && originalEntry != null) {
                                        val stagedEntries = buildList {
                                            add(Triple(draftPrimaryMidi, draftPrimaryString, draftPrimaryFret))
                                            draftChordStringFrets.forEachIndexed { chordIdx, sf ->
                                                val pitch = draftChordPitches.getOrElse(chordIdx) {
                                                    GuitarUtils.toMidi(sf.first, sf.second)
                                                }
                                                add(Triple(pitch, sf.first, sf.second))
                                            }
                                        }
                                        stagedEntries.firstOrNull { staged ->
                                            staged.second != index &&
                                                staged.first == originalEntry.first
                                        }
                                    } else {
                                        null
                                    }

                                    if (movedTargetEntry != null) {
                                        Text(
                                            text = "-> ${formatEntry(movedTargetEntry)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    } else {
                                        val rowStringLabel = normalizedStringLabel(gs.label)
                                        val rowStringNumber = (GuitarUtils.STRINGS.size - index).coerceAtLeast(1)
                                        Text(
                                            text = "- | $rowStringNumber $rowStringLabel Fret - | MIDI -",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (canRemove) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (currentChordIndex in draftChordPitches.indices) {
                                                draftChordPitches.removeAt(currentChordIndex)
                                                draftChordStringFrets.removeAt(currentChordIndex)
                                                editingChordNoteIndex = 0
                                                editingTargetStringFret = Pair(draftPrimaryString, draftPrimaryFret)
                                                selectedStringIndex = draftPrimaryString
                                                selectedFret = draftPrimaryFret
                                            } else if (index == draftPrimaryString && draftChordPitches.isNotEmpty()) {
                                                val promotedPitch = draftChordPitches.removeAt(0)
                                                val promotedSf = draftChordStringFrets.removeAt(0)
                                                draftPrimaryMidi = promotedPitch
                                                draftPrimaryString = promotedSf.first
                                                draftPrimaryFret = promotedSf.second
                                                editingChordNoteIndex = 0
                                                editingTargetStringFret = promotedSf
                                                selectedStringIndex = promotedSf.first
                                                selectedFret = promotedSf.second
                                            } else {
                                                showDeleteWholeChordConfirm = true
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove note",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (hasSelectedNote && hasDuplicateStrings) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Duplicate strings",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Warning: Multiple notes on the same string",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
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
                            .clickable {
                                selectedFret = fret
                                if (hasSelectedNote && selectedNoteIndex != null) {
                                    val newMidi = GuitarUtils.toMidi(selectedStringIndex, fret)
                                    if (editingChordNoteIndex == 0) {
                                        draftPrimaryMidi = newMidi
                                        draftPrimaryString = selectedStringIndex
                                        draftPrimaryFret = fret
                                        editingTargetStringFret = Pair(selectedStringIndex, fret)
                                    } else {
                                        val chordIdx = editingChordNoteIndex - 1
                                        if (chordIdx in draftChordPitches.indices) {
                                            draftChordPitches[chordIdx] = newMidi
                                            draftChordStringFrets[chordIdx] = Pair(selectedStringIndex, fret)
                                            editingTargetStringFret = Pair(selectedStringIndex, fret)
                                        }
                                    }
                                }
                            },
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

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!hasSelectedNote) {
                    // "Place Note" — places a new single note at cursor position
                    Button(
                        onClick = {
                            val note = EditorNote(selectedStringIndex, selectedFret)
                            val pairs = listOf(Pair(note.midiPitch, Pair(note.stringIndex, note.fret)))
                            onAddNote(pairs)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = editCursorActive,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Place Note", fontSize = 12.sp)
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

            }

            if (hasSelectedNote && selectedNoteIndex != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            val idx = selectedNoteIndex
                            onUpdateNote?.invoke(idx, draftPrimaryString, draftPrimaryFret)
                            val fullPitches = mutableListOf(draftPrimaryMidi).apply {
                                addAll(draftChordPitches)
                            }
                            val fullStringFrets = mutableListOf(Pair(draftPrimaryString, draftPrimaryFret)).apply {
                                addAll(draftChordStringFrets)
                            }
                            onUpdateChordNote?.invoke(idx, fullPitches, fullStringFrets)

                            originalPrimaryMidi = draftPrimaryMidi
                            originalPrimaryString = draftPrimaryString
                            originalPrimaryFret = draftPrimaryFret
                            originalChordPitches.clear()
                            originalChordPitches.addAll(draftChordPitches)
                            originalChordStringFrets.clear()
                            originalChordStringFrets.addAll(draftChordStringFrets)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Confirm Changes", fontSize = 12.sp)
                    }
                }
            }

            if (onToggleMoveMode != null || onCopyFromNote != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onToggleMoveMode != null) {
                        OutlinedButton(
                            onClick = onToggleMoveMode,
                            modifier = Modifier.weight(1f),
                            enabled = hasSelectedNote,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isMoveMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isMoveMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Filled.OpenWith, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isMoveMode) "Move ON" else "Move", fontSize = 12.sp)
                        }
                    }

                    if (onCopyFromNote != null && allNotes.isNotEmpty()) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { showCopyFromMenu = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy From", fontSize = 12.sp)
                            }

                            DropdownMenu(
                                expanded = showCopyFromMenu,
                                onDismissRequest = { showCopyFromMenu = false }
                            ) {
                                allNotes.forEachIndexed { idx, note ->
                                    if (selectedNoteIndex != null && idx == selectedNoteIndex) return@forEachIndexed
                                    val noteLabel = if (note.isChord) {
                                        note.allPitches.joinToString(" ") { pitch -> PitchUtils.midiToNoteName(pitch) }
                                    } else {
                                        PitchUtils.midiToNoteName(note.midiPitch)
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
