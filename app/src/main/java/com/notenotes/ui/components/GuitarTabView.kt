package com.notenotes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.model.MusicalNote
import com.notenotes.util.PitchUtils

/**
 * Guitar tablature view — draws 6 string lines with fret numbers at note positions.
 */
@Composable
fun GuitarTabView(
    notes: List<MusicalNote>,
    durationMs: Int,
    tempoBpm: Int,
    modifier: Modifier = Modifier,
    forceLightMode: Boolean = false
) {
    if (notes.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text("No notes to display", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Build labels dynamically from GuitarUtils so string count is not hard-coded
    val stringsCount = com.notenotes.util.GuitarUtils.STRINGS.size
    val reversedStrings = com.notenotes.util.GuitarUtils.DISPLAY_STRINGS
    val stringLabels = reversedStrings
        .map { raw ->
            // Use the short label suffix (e.g. "6 E" -> "E") and lowercase for compact display
            raw.label.split(' ').lastOrNull()?.lowercase() ?: raw.name
        }
        .toTypedArray()

    val durationSec = durationMs / 1000f
    val beatDurationSec = 60f / tempoBpm
    val pixelsPerSecond = 100f  // horizontal scale
    val totalWidth = (durationSec * pixelsPerSecond + 120f).coerceAtLeast(400f) // min 400px + margin
    val topMargin = 25f
    val lineSpacing = 20f
    val scrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    val fretTextColor = if (forceLightMode) Color(0xFF1565C0) else MaterialTheme.colorScheme.primary
    val timeColor = if (forceLightMode) Color(0x80999999) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val bgColor = if (forceLightMode) Color.White else MaterialTheme.colorScheme.surface

    val fretTextStyle = TextStyle(
        color = fretTextColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
    )
    // small label style
    val timeTextStyle = TextStyle(
        color = timeColor,
        fontSize = 9.sp
    )
    val noTabTextStyle = TextStyle(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
        fontSize = 10.sp
    )

    // Compute note positions
    data class TabNote(
        val timeSec: Float,
        val guitarString: Int?, // 0-5
        val fret: Int?,
        val label: String, // fret number or note name
        val isManual: Boolean,
        val displayLine: Int // 0-5 (top to bottom)
    )

    val tabNotes = remember(notes, tempoBpm, durationMs) {
        val result = mutableListOf<TabNote>()
        var cumulativeSec = 0f

        for (note in notes) {
            if (note.isRest) {
                cumulativeSec += note.durationTicks * beatDurationSec / 4f
                continue
            }

            val timeMs = note.timePositionMs
            val timeSec = if (timeMs != null) timeMs / 1000f else cumulativeSec

            if (note.hasTab) {
                // `tabPositions` are canonical human 1-based numbers; normalize and convert to 0-based indices for rendering
                note.safeTabPositionsAsHuman.forEach { (humanString, guitarFret) ->
                    val guitarStringIdx = com.notenotes.util.GuitarUtils.humanToIndex(
                        humanString.coerceIn(1, com.notenotes.util.GuitarUtils.STRINGS.size)
                    )!!
                    // Convert to display order (0 = top/highest string)
                    val displayLine = com.notenotes.util.GuitarUtils.indexToDisplayLine(guitarStringIdx)
                    result.add(
                        TabNote(
                            timeSec,
                            guitarStringIdx,
                            guitarFret,
                            guitarFret.toString(),
                            note.isManual,
                            displayLine.coerceIn(0, com.notenotes.util.GuitarUtils.STRINGS.size - 1)
                        )
                    )
                }
            } else {
                // No tab info — show note name on bottom area
                val primaryPitch = note.pitches.firstOrNull() ?: 0
                result.add(TabNote(timeSec, null, null,
                    PitchUtils.midiToNoteName(primaryPitch), note.isManual, 5))
            }

            // Handle chord pitches (simplified: just show primary note's tab)
            // In a more advanced version, each chord note could have its own string/fret

            cumulativeSec = timeSec + note.durationTicks * beatDurationSec / 4f
        }
        result
    }

    Row(modifier = modifier.fillMaxSize().background(bgColor)) {
        // String labels column (fixed)
        Column(
            modifier = Modifier
                .width(28.dp)
                .padding(top = topMargin.dp - 8.dp)
        ) {
            for (i in 0 until stringsCount) {
                val stringColor = Color(reversedStrings[i].colorArgb)
                Text(
                    text = stringLabels[i],
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = stringColor,
                    modifier = Modifier
                        .height(lineSpacing.dp)
                        .padding(start = 4.dp)
                )
            }
        }

        // Scrollable tab content
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(totalWidth.dp)
                .horizontalScroll(scrollState)
        ) {
            val canvasWidth = size.width

            // Draw 6 string lines
            for (i in 0 until stringsCount) {
                val y = topMargin + i * lineSpacing
                val stringColor = Color(reversedStrings[i].colorArgb)
                drawLine(
                    color = stringColor.copy(alpha = if (forceLightMode) 1f else 0.4f),
                    start = Offset(0f, y),
                    end = Offset(canvasWidth, y),
                    strokeWidth = 1f
                )
            }

            // Draw time markers
            if (durationSec > 0) {
                val tickInterval = when {
                    durationSec < 5 -> 0.5f
                    durationSec < 15 -> 1f
                    durationSec < 60 -> 2f
                    else -> 5f
                }
                var t = tickInterval
                while (t < durationSec) {
                    val x = t * pixelsPerSecond
                    drawLine(
                        color = timeColor,
                        start = Offset(x, topMargin - 10f),
                        end = Offset(x, topMargin - 3f),
                        strokeWidth = 1f
                    )
                    val label = if (tickInterval < 1f) String.format("%.1fs", t) else "${t.toInt()}s"
                    val measured = textMeasurer.measure(label, timeTextStyle)
                    drawText(measured, topLeft = Offset(x - measured.size.width / 2f, 2f))
                    t += tickInterval
                }
            }

            // Draw tab notes
            for (tabNote in tabNotes) {
                val x = tabNote.timeSec * pixelsPerSecond
                val y = topMargin + tabNote.displayLine * lineSpacing

                // Draw white background behind fret number for readability
                drawCircle(
                    color = bgColor,
                    radius = 8f,
                    center = Offset(x, y)
                )

                // Draw fret number or note name
                val stringColor = tabNote.guitarString?.let { Color(com.notenotes.util.GuitarUtils.STRINGS[it].colorArgb) } ?: fretTextColor
                val dynamicFretStyle = if (tabNote.guitarString != null) {
                    fretTextStyle.copy(color = stringColor)
                } else noTabTextStyle

                val measured = textMeasurer.measure(
                    tabNote.label,
                    dynamicFretStyle
                )
                drawText(
                    measured,
                    topLeft = Offset(x - measured.size.width / 2f, y - measured.size.height / 2f)
                )
            }
        }
    }
}
