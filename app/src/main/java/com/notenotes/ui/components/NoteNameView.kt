package com.notenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.model.MusicalNote

/**
 * Displays transcribed notes as a scrollable grid of note names with octave numbers.
 * Shows note names like "E4", "A2", "C#3" and chord names like "Am" or stacked notes.
 * Designed for guitarists who can't read sheet music.
 */
@Composable
fun NoteNameView(
    notes: List<MusicalNote>,
    modifier: Modifier = Modifier,
    tempoBpm: Int = 120
) {
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
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Title
        Text(
            text = "Note Names",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Wrap notes in a flow-like horizontal scroll
        val horizontalScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (note in notes) {
                NoteNameChip(note = note, tempoBpm = tempoBpm)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Also show a vertical sequential list view (easier to follow)
        Text(
            text = "Sequence",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        var beatCounter = 0.0
        val beatDuration = 60.0 / tempoBpm // seconds per beat
        val divisionsPerBeat = 4 // assuming quarter = 1 division * 4

        for ((index, note) in notes.withIndex()) {
            val beatPosition = beatCounter / divisionsPerBeat
            NoteNameRow(
                note = note,
                index = index + 1,
                beatPosition = beatPosition
            )
            beatCounter += note.durationTicks
        }
    }
}

@Composable
private fun NoteNameChip(
    note: MusicalNote,
    tempoBpm: Int
) {
    val bgColor = when {
        note.isRest -> MaterialTheme.colorScheme.surfaceVariant
        note.isChord -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = when {
        note.isRest -> MaterialTheme.colorScheme.onSurfaceVariant
        note.isChord -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    // Width proportional to duration
    val baseWidth = 48.dp
    val durationRatio = note.durationTicks.toFloat() / 4f // relative to quarter note
    val chipWidth = (baseWidth * durationRatio.coerceIn(0.5f, 4f))

    Box(
        modifier = Modifier
            .width(chipWidth)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
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
    beatPosition: Double
) {
    val bgColor = when {
        note.isRest -> Color.Transparent
        note.isChord -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Beat position indicator
        Text(
            text = String.format("%.1f", beatPosition + 1), // 1-based beat numbering
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
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

        // Duration type
        Text(
            text = durationSymbol(note.type, note.dotted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(50.dp)
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
