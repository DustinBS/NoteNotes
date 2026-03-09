package com.notenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.model.MusicalNote
import com.notenotes.util.GuitarUtils
import com.notenotes.util.PitchUtils

/**
 * Displays transcribed notes as a scrollable grid of note names with octave numbers.
 * Shows note names like "E4", "A2", "C#3" and chord names like "Am" or stacked notes.
 * Designed for guitarists who can't read sheet music.
 */
@Composable
fun NoteNameView(
    notes: List<MusicalNote>,
    modifier: Modifier = Modifier,
    tempoBpm: Int = 120,
    currentNoteIndex: Int = -1,
    noteProgressFraction: Float = 0f,
    onUpdateNote: ((Int, Int, Int) -> Unit)? = null,  // (index, guitarString, guitarFret)
    onDeleteNote: ((Int) -> Unit)? = null,
    onUpdateChordNote: ((Int, List<Int>, List<Pair<Int, Int>>) -> Unit)? = null  // (index, newChordPitches, newChordStringFrets)
) {
    // Edit dialog state
    var editingNoteIndex by remember { mutableStateOf<Int?>(null) }

    // Show edit dialog
    editingNoteIndex?.let { idx ->
        if (idx in notes.indices) {
            NoteEditDialog(
                note = notes[idx],
                noteIndex = idx,
                onDismiss = { editingNoteIndex = null },
                onSave = { index, guitarString, guitarFret ->
                    onUpdateNote?.invoke(index, guitarString, guitarFret)
                    editingNoteIndex = null
                },
                onDelete = {
                    onDeleteNote?.invoke(idx)
                    editingNoteIndex = null
                },
                onUpdateChordPitches = { index, newPitches, newPositions ->
                    onUpdateChordNote?.invoke(index, newPitches, newPositions)
                    editingNoteIndex = null
                }
            )
        }
    }

    if (notes.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No notes detected",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(6.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Compact note chips row with playback pointer
        val horizontalScroll = rememberScrollState()
        val density = LocalDensity.current
        // Track each chip's x-offset and width for pointer positioning
        val chipPositions = remember { mutableStateMapOf<Int, Pair<Float, Float>>() } // index -> (x, width)
        val pointerColor = MaterialTheme.colorScheme.primary

        // Auto-scroll chip row to keep the current note visible
        LaunchedEffect(currentNoteIndex) {
            if (currentNoteIndex >= 0) {
                val pos = chipPositions[currentNoteIndex]
                if (pos != null) {
                    val chipX = pos.first
                    val chipW = pos.second
                    val viewportStart = horizontalScroll.value.toFloat()
                    val viewportEnd = viewportStart + horizontalScroll.viewportSize
                    // Scroll if the chip center is outside the visible viewport
                    val chipCenter = chipX + chipW / 2f
                    if (chipCenter < viewportStart || chipCenter > viewportEnd) {
                        horizontalScroll.animateScrollTo((chipX - 40f).toInt().coerceAtLeast(0))
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScroll),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                notes.forEachIndexed { idx, note ->
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                chipPositions[idx] = Pair(
                                    coords.positionInParent().x,
                                    coords.size.width.toFloat()
                                )
                            }
                    ) {
                        NoteNameChip(
                            note = note,
                            tempoBpm = tempoBpm,
                            isCurrentlyPlaying = idx == currentNoteIndex
                        )
                    }
                }
            }

            // Draw playback pointer triangle on top of chips
            if (currentNoteIndex >= 0 && currentNoteIndex in chipPositions) {
                val pos = chipPositions[currentNoteIndex]!!
                val chipX = pos.first
                val chipW = pos.second
                val pointerX = chipX + chipW * noteProgressFraction.coerceIn(0f, 1f)
                // Compensate for scroll offset
                val scrollOffset = horizontalScroll.value.toFloat()
                val screenX = pointerX - scrollOffset

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithContent {
                            drawContent()
                            // Downward-pointing triangle at the top (V shape)
                            val triSize = 6.dp.toPx()
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(screenX - triSize, 0f)
                                lineTo(screenX + triSize, 0f)
                                lineTo(screenX, triSize * 2)
                                close()
                            }
                            drawPath(path, pointerColor)
                            // Vertical line from triangle to bottom
                            drawLine(
                                color = pointerColor,
                                start = Offset(screenX, triSize * 2),
                                end = Offset(screenX, size.height),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Compute time in seconds for each note — matches NoteTimingHelper logic
        val tickMs = 60000.0 / tempoBpm / 4.0 // ms per tick (divisions=4)
        var cumulativeMs = 0.0

        for ((index, note) in notes.withIndex()) {
            val startMs = if (note.isManual && note.timePositionMs != null) {
                note.timePositionMs!!.toDouble()
            } else if (note.timePositionMs != null) {
                note.timePositionMs!!.toDouble()
            } else {
                cumulativeMs
            }
            val timePositionSeconds = startMs / 1000.0
            NoteNameRow(
                note = note,
                index = index + 1,
                timePositionSeconds = timePositionSeconds,
                isCurrentlyPlaying = index == currentNoteIndex,
                onClick = if (onUpdateNote != null || onDeleteNote != null) {
                    { editingNoteIndex = index }
                } else null
            )
            cumulativeMs = startMs + note.durationTicks * tickMs
        }
    }
}

@Composable
private fun NoteNameChip(
    note: MusicalNote,
    tempoBpm: Int,
    isCurrentlyPlaying: Boolean = false
) {
    val bgColor = when {
        note.isRest -> MaterialTheme.colorScheme.surfaceVariant
        note.isChord && note.hasDuplicateStrings -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = when {
        note.isRest -> MaterialTheme.colorScheme.onSurfaceVariant
        note.isChord && note.hasDuplicateStrings -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    // Width proportional to duration
    val baseWidth = 48.dp
    val durationRatio = note.durationTicks.toFloat() / 4f // relative to quarter note
    val chipWidth = (baseWidth * durationRatio.coerceIn(0.5f, 4f))

    val chipBorder = if (isCurrentlyPlaying) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
    }

    Box(
        modifier = Modifier
            .width(chipWidth)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(chipBorder),
        contentAlignment = Alignment.Center
    ) {
        if (note.isRest) {
            Text(
                text = "—",
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        } else if (note.isChord && note.chordName != null) {
            // Show chord name
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = note.chordName!!,
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                // Show individual notes below
                val noteNames = note.allPitches.map { midiToDisplayName(it) }
                Text(
                    text = noteNames.joinToString(" "),
                    color = textColor.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        } else if (note.isChord) {
            // Chord without identified name — show stacked note names
            val noteNames = note.allPitches.map { midiToDisplayName(it) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                for (name in noteNames.reversed()) { // top to bottom = high to low
                    Text(
                        text = name,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 14.sp
                    )
                }
            }
        } else {
            // Single note
            Text(
                text = midiToDisplayName(note.midiPitch),
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun NoteNameRow(
    note: MusicalNote,
    index: Int,
    timePositionSeconds: Double,
    isCurrentlyPlaying: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val bgColor = when {
        note.isRest -> Color.Transparent
        note.isChord && note.hasDuplicateStrings -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
    }

    val rowBorder = if (isCurrentlyPlaying) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .then(rowBorder)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Always show time in seconds — consistent with transport controls
        val posLabel = String.format("%.1fs", timePositionSeconds)
        Text(
            text = posLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )

        // Note name(s)
        if (note.isRest) {
            Text(
                text = "rest",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
        } else if (note.isChord) {
            Column(modifier = Modifier.weight(1f)) {
                if (note.chordName != null) {
                    Text(
                        text = note.chordName!!,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val noteNames = note.allPitches.map { midiToDisplayName(it) }
                Text(
                    text = noteNames.joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Text(
                text = midiToDisplayName(note.midiPitch),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        // Guitar tab info if available
        if (note.hasTab) {
            val stringNames = arrayOf("E2", "A2", "D3", "G3", "B3", "E4")
            val sName = if ((note.guitarString ?: 0) in stringNames.indices)
                stringNames[note.guitarString!!] else "?"
            Text(
                text = "${sName} F${note.guitarFret ?: 0}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.width(50.dp)
            )
        }

        // Duplicate string warning
        if (note.isChord && note.hasDuplicateStrings) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Duplicate strings",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Duration type
        Text(
            text = durationSymbol(note.type, note.dotted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}

// ---- Utility Functions ----

private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/**
 * Convert MIDI note number to display name with octave (e.g., 60 → "C4", 40 → "E2").
 */
private fun midiToDisplayName(midiNote: Int): String {
    if (midiNote !in 0..127) return "?"
    val octave = (midiNote / 12) - 1
    val noteIndex = midiNote % 12
    return "${SHARP_NAMES[noteIndex]}$octave"
}

/**
 * Get a user-friendly duration symbol/abbreviation.
 */
private fun durationSymbol(type: String, dotted: Boolean): String {
    val base = when (type) {
        "whole" -> "𝅝"
        "half" -> "𝅗𝅥"
        "quarter" -> "♩"
        "eighth" -> "♪"
        "16th" -> "𝅘𝅥𝅯"
        else -> type
    }
    return if (dotted) "$base." else base
}

/**
 * Dialog for editing a note's guitar string/fret or deleting it.
 * For chords, shows all individual notes for editing/removal.
 * Changes are applied in real-time as the user adjusts string/fret.
 * Save = confirm all changes, Cancel = revert to original.
 */
@Composable
private fun NoteEditDialog(
    note: MusicalNote,
    noteIndex: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int) -> Unit,  // (index, guitarString, guitarFret)
    onDelete: () -> Unit,
    onUpdateChordPitches: ((Int, List<Int>, List<Pair<Int, Int>>) -> Unit)? = null
) {
    // --- Mutable editing state for primary note ---
    var editedPrimaryString by remember { mutableIntStateOf(note.guitarString ?: 0) }
    var editedPrimaryFret by remember { mutableIntStateOf(note.guitarFret ?: 0) }

    // String/fret selector state (what the selectors show)
    var selectedStringIndex by remember { mutableIntStateOf(note.guitarString ?: 0) }
    var selectedFret by remember { mutableIntStateOf(note.guitarFret ?: 0) }

    // Mutable list of chord pitches for editing
    val editableChordPitches = remember { mutableStateListOf<Int>().also { it.addAll(note.chordPitches) } }
    // Parallel list of chord string/fret positions
    val editableChordPositions = remember {
        mutableStateListOf<Pair<Int, Int>>().also { list ->
            note.chordPitches.forEachIndexed { i, pitch ->
                val stored = note.chordStringFrets.getOrNull(i)
                if (stored != null) {
                    list.add(stored)
                } else {
                    val pos = GuitarUtils.fromMidi(pitch)
                    list.add(pos ?: Pair(0, 0))
                }
            }
        }
    }
    // Track which chord note is being edited (null = primary)
    var editingChordPitchIndex by remember { mutableStateOf<Int?>(null) }

    // --- Original values for change detection ---
    val originalPrimaryString = note.guitarString ?: 0
    val originalPrimaryFret = note.guitarFret ?: 0
    val originalChordPitches = remember { note.chordPitches.toList() }
    val originalChordPositions = remember {
        note.chordPitches.mapIndexed { i, pitch ->
            note.chordStringFrets.getOrNull(i) ?: (GuitarUtils.fromMidi(pitch) ?: Pair(0, 0))
        }
    }

    // --- Unsaved changes detection ---
    val hasUnsavedChanges by remember {
        derivedStateOf {
            editedPrimaryString != originalPrimaryString ||
            editedPrimaryFret != originalPrimaryFret ||
            editableChordPitches.toList() != originalChordPitches ||
            editableChordPositions.toList() != originalChordPositions
        }
    }
    var showDiscardWarning by remember { mutableStateOf(false) }

    // Sync string/fret selector when switching between chord notes
    LaunchedEffect(editingChordPitchIndex) {
        val idx = editingChordPitchIndex
        if (idx == null) {
            selectedStringIndex = editedPrimaryString
            selectedFret = editedPrimaryFret
        } else if (idx in editableChordPositions.indices) {
            selectedStringIndex = editableChordPositions[idx].first
            selectedFret = editableChordPositions[idx].second
        }
    }

    // Auto-apply string/fret changes in real-time to the active note
    LaunchedEffect(selectedStringIndex, selectedFret) {
        val idx = editingChordPitchIndex
        if (idx == null) {
            // Editing primary note
            editedPrimaryString = selectedStringIndex
            editedPrimaryFret = selectedFret
        } else if (idx in editableChordPitches.indices) {
            // Editing a chord note — update pitch and position
            val newMidi = GuitarUtils.toMidi(selectedStringIndex, selectedFret)
            editableChordPitches[idx] = newMidi
            editableChordPositions[idx] = Pair(selectedStringIndex, selectedFret)
        }
    }

    val editedPrimaryMidi = GuitarUtils.toMidi(editedPrimaryString, editedPrimaryFret)
    val currentNoteName = PitchUtils.midiToNoteName(editedPrimaryMidi)

    // Discard warning dialog
    if (showDiscardWarning) {
        AlertDialog(
            onDismissRequest = { showDiscardWarning = false },
            title = { Text("Discard Changes?") },
            text = { Text("You have unsaved changes. Discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardWarning = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardWarning = false }) { Text("Keep Editing") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (hasUnsavedChanges) showDiscardWarning = true else onDismiss()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit Note ${noteIndex + 1}")
                if (hasUnsavedChanges) {
                    Text(
                        text = "unsaved",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column {
                // Show all notes in chord (also when user adds notes to a single note)
                val isChordEditing = note.isChord || editableChordPitches.isNotEmpty()
                if (isChordEditing) {
                    Text(
                        text = "Chord Notes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Primary note — shows original → current if changed
                    val primaryChanged = editedPrimaryString != originalPrimaryString || editedPrimaryFret != originalPrimaryFret
                    val primaryStringColor = if (editedPrimaryString in GuitarUtils.STRINGS.indices)
                        Color(GuitarUtils.STRINGS[editedPrimaryString].colorArgb)
                    else MaterialTheme.colorScheme.primary
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                width = if (editingChordPitchIndex == null) 2.dp else 0.dp,
                                color = if (editingChordPitchIndex == null) primaryStringColor else Color.Transparent,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .background(
                                if (editingChordPitchIndex == null)
                                    primaryStringColor.copy(alpha = 0.25f)
                                else primaryStringColor.copy(alpha = 0.08f)
                            )
                            .clickable { editingChordPitchIndex = null }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (primaryChanged) {
                                // Show original
                                val origLabel = GuitarUtils.STRINGS.getOrNull(originalPrimaryString)?.label ?: "?"
                                Text(
                                    text = "${midiToDisplayName(note.midiPitch)} - $origLabel Fret $originalPrimaryFret - MIDI ${note.midiPitch}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Show arrow and new value
                                val newLabel = GuitarUtils.STRINGS.getOrNull(editedPrimaryString)?.label ?: "?"
                                Text(
                                    text = "\u2192 ${midiToDisplayName(editedPrimaryMidi)} - $newLabel Fret $editedPrimaryFret - MIDI $editedPrimaryMidi",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                val gs = GuitarUtils.STRINGS.getOrNull(originalPrimaryString)
                                val label = gs?.label ?: "?"
                                Text(
                                    text = "${midiToDisplayName(note.midiPitch)} - $label Fret $originalPrimaryFret - MIDI ${note.midiPitch}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = if (editingChordPitchIndex == null) "editing" else "tap to edit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Additional chord pitches — show original → current if changed
                    editableChordPitches.forEachIndexed { idx, pitch ->
                        val origPitch = originalChordPitches.getOrNull(idx)
                        val origPos = originalChordPositions.getOrNull(idx)
                        val curPos = editableChordPositions.getOrNull(idx)
                        val chordNoteChanged = origPitch != pitch || origPos != curPos

                        val chordStringIdx = curPos?.first ?: 0
                        val chordStringColor = if (chordStringIdx in GuitarUtils.STRINGS.indices)
                            Color(GuitarUtils.STRINGS[chordStringIdx].colorArgb)
                        else MaterialTheme.colorScheme.primary
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .border(
                                    width = if (editingChordPitchIndex == idx) 2.dp else 0.dp,
                                    color = if (editingChordPitchIndex == idx) chordStringColor else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .background(
                                    if (editingChordPitchIndex == idx)
                                        chordStringColor.copy(alpha = 0.25f)
                                    else chordStringColor.copy(alpha = 0.08f)
                                )
                                .clickable { editingChordPitchIndex = idx }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (chordNoteChanged && origPitch != null && origPos != null) {
                                    val origLabel = GuitarUtils.STRINGS.getOrNull(origPos.first)?.label ?: "?"
                                    Text(
                                        text = "${midiToDisplayName(origPitch)} - $origLabel Fret ${origPos.second} - MIDI $origPitch",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val newLabel = curPos?.let { GuitarUtils.STRINGS.getOrNull(it.first)?.label } ?: "?"
                                    val newFret = curPos?.second ?: 0
                                    Text(
                                        text = "\u2192 ${midiToDisplayName(pitch)} - $newLabel Fret $newFret - MIDI $pitch",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    val label = curPos?.let { GuitarUtils.STRINGS.getOrNull(it.first)?.label } ?: "?"
                                    val fret = curPos?.second ?: 0
                                    Text(
                                        text = "${midiToDisplayName(pitch)} - $label Fret $fret - MIDI $pitch",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (editingChordPitchIndex == idx) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                            Row {
                                if (editingChordPitchIndex == idx) {
                                    Text(
                                        text = "editing",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            editableChordPitches.removeAt(idx)
                                            if (idx in editableChordPositions.indices) {
                                                editableChordPositions.removeAt(idx)
                                            }
                                            // LIFO: select previous chord note, or null if none left
                                            editingChordPitchIndex = if (editableChordPitches.isEmpty()) null
                                                else (idx - 1).coerceAtLeast(0).coerceAtMost(editableChordPitches.size - 1)
                                        },
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Current selection preview
                Text(
                    text = "${PitchUtils.midiToNoteName(GuitarUtils.toMidi(selectedStringIndex, selectedFret))} · MIDI ${GuitarUtils.toMidi(selectedStringIndex, selectedFret)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // String selector
                Text("String", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GuitarUtils.STRINGS.forEachIndexed { index, gs ->
                        val isSelected = index == selectedStringIndex
                        val sColor = Color(gs.colorArgb)
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedStringIndex = index },
                            label = { Text(gs.label, fontSize = 10.sp, maxLines = 1, softWrap = false,
                                color = if (isSelected) Color.White else sColor) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = sColor.copy(alpha = 0.15f),
                                labelColor = sColor,
                                selectedContainerColor = sColor,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fret selector
                Text("Fret: $selectedFret", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(4.dp))
                val fretScrollState = rememberScrollState()
                val density = LocalDensity.current
                // Auto-scroll to selected fret when it changes
                LaunchedEffect(selectedFret) {
                    with(density) {
                        val targetPx = (selectedFret * 33.dp.toPx()).toInt()
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
                                .size(30.dp)
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
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Same-string conflict warning
                if (note.isChord) {
                    val allStringIndices = mutableListOf(editedPrimaryString)
                    editableChordPositions.forEach { pos ->
                        allStringIndices.add(pos.first)
                    }
                    val hasDuplicateStrings = allStringIndices.size != allStringIndices.toSet().size

                    if (hasDuplicateStrings) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "\u26A0 Warning: Multiple notes on the same string!",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Button to add current note as new chord member
                // Always available — allows turning single notes into chords too
                val addMidi = GuitarUtils.toMidi(selectedStringIndex, selectedFret)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        // Find an unused string for the new note, defaulting to Low E fret 0
                        val usedStrings = mutableSetOf(editedPrimaryString)
                        editableChordPositions.forEach { usedStrings.add(it.first) }
                        val unusedString = (0..5).firstOrNull { it !in usedStrings } ?: 0
                        val newMidi = GuitarUtils.toMidi(unusedString, 0)
                        editableChordPitches.add(newMidi)
                        editableChordPositions.add(Pair(unusedString, 0))
                        // Switch editing to the newly added note
                        editingChordPitchIndex = editableChordPitches.size - 1
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Note", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if ((note.isChord || editableChordPitches.isNotEmpty()) && onUpdateChordPitches != null) {
                    onSave(noteIndex, editedPrimaryString, editedPrimaryFret)
                    onUpdateChordPitches(noteIndex, editableChordPitches.toList(), editableChordPositions.toList())
                } else {
                    onSave(noteIndex, editedPrimaryString, editedPrimaryFret)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    if (hasUnsavedChanges) showDiscardWarning = true else onDismiss()
                }) { Text("Cancel") }
            }
        }
    )
}
