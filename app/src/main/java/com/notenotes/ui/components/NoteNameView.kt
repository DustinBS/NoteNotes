package com.notenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
                tempoBpm = tempoBpm,
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
        } else {
            val displayText = buildPitchFretAnnotated(note)
            Text(
                text = displayText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun NoteNameRow(
    note: MusicalNote,
    index: Int,
    tempoBpm: Int,
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
                Text(
                    text = buildPitchFretAnnotated(note),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Text(
                text = buildPitchFretAnnotated(note),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
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

        // Right-side metadata: timestamp + duration
        Column(
            modifier = Modifier.width(52.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = formatTimestamp(timePositionSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
            Text(
                text = String.format("%.2fs", durationSeconds(note, tempoBpm)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
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

private fun formatTimestamp(seconds: Double): String {
    val totalSeconds = seconds.toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format("%d:%02d", minutes, secs)
}

private fun durationSeconds(note: MusicalNote, tempoBpm: Int): Float {
    val tickDurationSec = 60f / tempoBpm.coerceAtLeast(1) / 4f
    return note.durationTicks.coerceAtLeast(0) * tickDurationSec
}

private fun buildPitchEntries(note: MusicalNote): List<Triple<Int, Int, Int>> {
    val entries = mutableListOf<Triple<Int, Int, Int>>()
    for (i in note.pitches.indices) {
        val pitch = note.pitches[i]
        val sf = note.tabPositions.getOrNull(i) ?: Pair(0, 0)
        entries.add(Triple(pitch, sf.first, sf.second))
    }
    return entries.sortedBy { it.first }
}

private fun buildPitchFretAnnotated(note: MusicalNote) = buildAnnotatedString {
    val entries = buildPitchEntries(note)
    entries.forEachIndexed { idx, (pitch, stringIndex, fret) ->
        val stringColor = if (stringIndex in GuitarUtils.STRINGS.indices)
            Color(GuitarUtils.STRINGS[stringIndex].colorArgb)
        else Color.Unspecified

        withStyle(SpanStyle(color = stringColor, fontWeight = FontWeight.SemiBold)) {
            append(midiToDisplayName(pitch))
        }
        if (!note.isRest && (note.isChord || note.hasTab)) {
            withStyle(SpanStyle(color = stringColor, fontSize = 8.sp, baselineShift = BaselineShift.Superscript)) {
                append(fret.toString())
            }
        }
        if (idx != entries.lastIndex) append(" ")
    }
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
    var editedPrimaryString by remember { mutableIntStateOf(note.tabPositions.firstOrNull()?.first ?: 0) }
    var editedPrimaryFret by remember { mutableIntStateOf(note.tabPositions.firstOrNull()?.second ?: 0) }

    // String/fret selector state (what the selectors show)
    var selectedStringIndex by remember { mutableIntStateOf(note.tabPositions.firstOrNull()?.first ?: 0) }
    var selectedFret by remember { mutableIntStateOf(note.tabPositions.firstOrNull()?.second ?: 0) }

    // Mutable list of chord pitches for editing
    val editableChordPitches = remember { mutableStateListOf<Int>().also { it.addAll(note.pitches.drop(1)) } }
    // Parallel list of chord string/fret positions
    val editableChordPositions = remember {
        mutableStateListOf<Pair<Int, Int>>().also { list ->
            val pitches = note.pitches.drop(1)
            val tabPos = note.tabPositions.drop(1)
            pitches.forEachIndexed { i, pitch ->
                val stored = tabPos.getOrNull(i)
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
    val originalPrimaryString = note.tabPositions.firstOrNull()?.first ?: 0
    val originalPrimaryFret = note.tabPositions.firstOrNull()?.second ?: 0
    val originalChordPitches = remember { note.pitches.drop(1).toList() }
    val originalChordPositions = remember {
        val pitches = note.pitches.drop(1)
        val tabPos = note.tabPositions.drop(1)
        pitches.mapIndexed { i, pitch ->
            tabPos.getOrNull(i) ?: (GuitarUtils.fromMidi(pitch) ?: Pair(0, 0))
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
                val isChordEditing = note.isChord || editableChordPitches.isNotEmpty()
                Text(
                    text = "String Selector",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Tap a string row to add/edit notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                fun normalizedStringLabel(rawLabel: String): String {
                    val trimmed = rawLabel.trim()
                    val withoutPrefix = trimmed.dropWhile { it.isDigit() || it.isWhitespace() }
                    val notePart = withoutPrefix
                        .takeWhile { it.isLetter() || it == '#' || it == 'b' }
                        .ifBlank { withoutPrefix }
                    return notePart.ifBlank { trimmed }
                }

                fun formatEntry(entry: Triple<Int, Int, Int>): String {
                    val pitch = entry.first
                    val stringIndex = entry.second
                    val fret = entry.third
                    val rawLabel = GuitarUtils.STRINGS.getOrNull(stringIndex)?.label ?: "?"
                    val stringLabel = normalizedStringLabel(rawLabel)
                    val stringNumber = (GuitarUtils.STRINGS.size - stringIndex).coerceAtLeast(1)
                    return "${midiToDisplayName(pitch)} | $stringNumber $stringLabel Fret $fret | MIDI $pitch"
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (index in GuitarUtils.STRINGS.indices.reversed()) {
                        val gs = GuitarUtils.STRINGS[index]
                        val rowSelected = index == selectedStringIndex
                        val sColor = Color(gs.colorArgb)

                        val currentChordIndex = editableChordPositions.indexOfFirst { it.first == index }
                        val currentEntry = when {
                            index == editedPrimaryString -> Triple(editedPrimaryMidi, editedPrimaryString, editedPrimaryFret)
                            currentChordIndex >= 0 -> {
                                val sf = editableChordPositions[currentChordIndex]
                                val pitch = editableChordPitches.getOrElse(currentChordIndex) { GuitarUtils.toMidi(sf.first, sf.second) }
                                Triple(pitch, sf.first, sf.second)
                            }
                            else -> null
                        }

                        val originalChordIndex = originalChordPositions.indexOfFirst { it.first == index }
                        val originalEntry = when {
                              index == originalPrimaryString -> Triple(note.pitches.firstOrNull() ?: 0, originalPrimaryString, originalPrimaryFret)
                            originalChordIndex >= 0 -> {
                                val sf = originalChordPositions[originalChordIndex]
                                val pitch = originalChordPitches.getOrElse(originalChordIndex) { GuitarUtils.toMidi(sf.first, sf.second) }
                                Triple(pitch, sf.first, sf.second)
                            }
                            else -> null
                        }

                        val isActive = currentEntry != null
                        val changed = currentEntry != originalEntry
                        val canRemove = currentChordIndex >= 0

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val existingChordIndex = editableChordPositions.indexOfFirst { it.first == index }
                                    when {
                                        index == editedPrimaryString -> {
                                            editingChordPitchIndex = null
                                            selectedStringIndex = editedPrimaryString
                                            selectedFret = editedPrimaryFret
                                        }
                                        existingChordIndex >= 0 -> {
                                            val sf = editableChordPositions[existingChordIndex]
                                            editingChordPitchIndex = existingChordIndex
                                            selectedStringIndex = sf.first
                                            selectedFret = sf.second
                                        }
                                        isChordEditing -> {
                                            val newFret = 0
                                            val newMidi = GuitarUtils.toMidi(index, newFret)
                                            editableChordPitches.add(newMidi)
                                            editableChordPositions.add(Pair(index, newFret))
                                            editingChordPitchIndex = editableChordPitches.size - 1
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
                            color = if (isActive) sColor.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                width = if (rowSelected) 2.dp else 1.dp,
                                color = if (rowSelected) MaterialTheme.colorScheme.primary
                                else if (isActive) sColor
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
                                            text = formatEntry(currentEntry),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (rowSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isActive) sColor else MaterialTheme.colorScheme.onSurface
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

                                if (canRemove) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                editableChordPitches.removeAt(currentChordIndex)
                                                editableChordPositions.removeAt(currentChordIndex)
                                                editingChordPitchIndex = null
                                                selectedStringIndex = editedPrimaryString
                                                selectedFret = editedPrimaryFret
                                            },
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
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
                if (isChordEditing) {
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
            }
        },
        confirmButton = {
            Row {
                FilledTonalButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete Chord")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (hasUnsavedChanges) showDiscardWarning = true else onDismiss()
                }) { Text("Cancel") }
                TextButton(onClick = {
                    if ((note.isChord || editableChordPitches.isNotEmpty()) && onUpdateChordPitches != null) {
                        onSave(noteIndex, editedPrimaryString, editedPrimaryFret)
                        onUpdateChordPitches(noteIndex, editableChordPitches.toList(), editableChordPositions.toList())
                    } else {
                        onSave(noteIndex, editedPrimaryString, editedPrimaryFret)
                    }
                }) { Text("Save") }
            }
        },
        dismissButton = null
    )
}
