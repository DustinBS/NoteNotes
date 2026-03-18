package com.notenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.util.GuitarUtils
import com.notenotes.util.PitchUtils

/**
 * A shared UI component for editing a single note or chord on a guitar.
 * Displays the 6-string "tablature-style" selector and the fret selector.
 * Bound to the [GuitarChordEditState] which holds all the reactive state logic.
 */
@Composable
fun GuitarNoteEditor(
    state: GuitarChordEditState,
    hasSelectedNote: Boolean = true,
    onDeleteWholeNoteWarning: () -> Unit = {}
) {
    Column {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("String Selector: ")
                }
                append("Tap a string row to add/edit notes.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        fun normalizedStringLabel(rawLabel: String): String {
            val trimmed = rawLabel.trim()
            val withoutPrefix = trimmed.dropWhile { it.isDigit() || it.isWhitespace() }
            val notePart = withoutPrefix
                .takeWhile { it.isLetter() || it == '#' || it == 'b' }
                .ifBlank { withoutPrefix }
            return notePart.ifBlank { trimmed }
        }

        fun formatEntryAnnotated(pitch: Int, stringIndex: Int, fret: Int, isStrikethrough: Boolean = false): AnnotatedString {
            val rawLabel = GuitarUtils.STRINGS.getOrNull(stringIndex)?.label ?: "?"
            val stringLabel = normalizedStringLabel(rawLabel)
            val stringNumber = (GuitarUtils.STRINGS.size - stringIndex).coerceAtLeast(1)
            val sharpNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            val noteIndex = pitch % 12
            val octave = (pitch / 12) - 1
            val noteName = "${sharpNames[noteIndex]}$octave"
            val stringColor = if (stringIndex in GuitarUtils.STRINGS.indices && !isStrikethrough) Color(GuitarUtils.STRINGS[stringIndex].colorArgb) else Color.Unspecified
            val textDeco = if (isStrikethrough) TextDecoration.LineThrough else TextDecoration.None
            val textWeight = if (isStrikethrough) FontWeight.Normal else FontWeight.SemiBold
            
            return buildAnnotatedString {
                withStyle(SpanStyle(color = stringColor, fontWeight = textWeight, textDecoration = textDeco)) {
                    append(noteName)
                }
                withStyle(SpanStyle(textDecoration = textDeco)) {
                    append(" | $stringNumber $stringLabel Fret $fret | MIDI $pitch")
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (index in GuitarUtils.STRINGS.indices.reversed()) {
                val gs = GuitarUtils.STRINGS[index]
                val rowSelected = index == state.selectedStringIndex
                val sColor = Color(gs.colorArgb)

                val currentChordIndex = state.editableChordPositions.indexOfFirst { it.first == index }
                
                val currentEntry = when {
                    hasSelectedNote && index == state.editedPrimaryString -> Triple(state.editedPrimaryMidi, state.editedPrimaryString, state.editedPrimaryFret)
                    currentChordIndex >= 0 -> {
                        val sf = state.editableChordPositions[currentChordIndex]
                        val pitch = state.editableChordPitches.getOrElse(currentChordIndex) { GuitarUtils.toMidi(sf.first, sf.second) }
                        Triple(pitch, sf.first, sf.second)
                    }
                    !hasSelectedNote && index == state.selectedStringIndex -> Triple(GuitarUtils.toMidi(state.selectedStringIndex, state.selectedFret), state.selectedStringIndex, state.selectedFret)
                    else -> null
                }

                val originalChordIndex = state.originalChordPositions.indexOfFirst { it.first == index }
                val originalEntry = when {
                    hasSelectedNote && index == state.originalPrimaryString -> Triple(state.originalPrimaryMidi, state.originalPrimaryString, state.originalPrimaryFret)
                    originalChordIndex >= 0 -> {
                        val sf = state.originalChordPositions[originalChordIndex]
                        val pitch = state.originalChordPitches.getOrElse(originalChordIndex) { GuitarUtils.toMidi(sf.first, sf.second) }
                        Triple(pitch, sf.first, sf.second)
                    }
                    else -> null
                }

                val isActive = currentEntry != null
                val changed = hasSelectedNote && currentEntry != originalEntry
                val rowHighlighted = isActive
                val canRemove = hasSelectedNote && (currentChordIndex >= 0 || index == state.editedPrimaryString)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val existingChordIndex = state.editableChordPositions.indexOfFirst { it.first == index }
                            when {
                                hasSelectedNote && index == state.editedPrimaryString -> {
                                    state.selectPrimaryNote()
                                }
                                hasSelectedNote && existingChordIndex >= 0 -> {
                                    state.selectChordNote(existingChordIndex)
                                }
                                hasSelectedNote -> {
                                    // Add new chord note
                                    state.addChordNote(index, state.selectedFret)
                                }
                                else -> {
                                    state.selectedStringIndex = index
                                    state.updateSelectedStringFret(index, state.selectedFret)
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
                            if (hasSelectedNote && changed && originalEntry != null) {
                                Text(
                                    text = formatEntryAnnotated(originalEntry.first, originalEntry.second, originalEntry.third, isStrikethrough = true),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (currentEntry != null) {
                                val textAnnotated = if (hasSelectedNote && changed && !(originalEntry == null && !hasSelectedNote)) {
                                    buildAnnotatedString {
                                        append("-> ")
                                        append(formatEntryAnnotated(currentEntry.first, currentEntry.second, currentEntry.third))
                                    }
                                } else {
                                    formatEntryAnnotated(currentEntry.first, currentEntry.second, currentEntry.third)
                                }
                                Text(
                                    text = textAnnotated,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isActive) sColor else MaterialTheme.colorScheme.onSurface
                                )
                            } else if (hasSelectedNote && changed && originalEntry != null) {
                                val movedTargetEntry = run {
                                    val stagedEntries = buildList {
                                        if (state.editedPrimaryFret != null) {
                                            add(Triple(state.editedPrimaryMidi!!, state.editedPrimaryString!!, state.editedPrimaryFret!!))
                                        }
                                        state.editableChordPositions.forEachIndexed { chordIdx, sf ->
                                            val pitch = GuitarUtils.toMidi(sf.first, sf.second)
                                            add(Triple(pitch, sf.first, sf.second))
                                        }
                                    }
                                    stagedEntries.firstOrNull { staged ->
                                        staged.second != index && staged.first == originalEntry.first
                                    }
                                }

                                if (movedTargetEntry != null) {
                                    Text(
                                        text = buildAnnotatedString {
                                            append("-> ")
                                            append(formatEntryAnnotated(movedTargetEntry.first, movedTargetEntry.second, movedTargetEntry.third))
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
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
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (index == state.editedPrimaryString) {
                                            val promoted = state.removePrimaryNote()
                                            if (!promoted) {
                                                onDeleteWholeNoteWarning()
                                            }
                                        } else {
                                            val cIndex = state.editableChordPositions.indexOfFirst { it.first == index }
                                            if (cIndex >= 0) {
                                                state.removeChordNote(cIndex)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
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

        Spacer(modifier = Modifier.height(8.dp))

        // Fret selector
        Text(
            text = "Fret: ${state.selectedFret}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        
        val fretScrollState = rememberScrollState()
        val density = LocalDensity.current

        // Auto-scroll to selected fret when it changes
        LaunchedEffect(state.selectedFret) {
            with(density) {
                val targetPx = (state.selectedFret * 33.dp.toPx()).toInt()
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
                val isSelected = fret == state.selectedFret
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable {
                            state.updateSelectedStringFret(state.selectedStringIndex, fret)
                        },
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
        if (state.hasDuplicateStrings && (state.editableChordPitches.isNotEmpty() || hasSelectedNote)) {
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
    }
}
