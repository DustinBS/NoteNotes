package com.notenotes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.model.MusicalNote
import com.notenotes.util.PitchUtils

/**
 * Waveform data holder with downsampled amplitude peaks.
 */
data class WaveformData(
    val peaks: FloatArray,          // normalized peak amplitudes (0.0–1.0), one per bin
    val durationSeconds: Float,     // total audio duration
    val sampleRate: Int = 44100
) {
    val binsCount: Int get() = peaks.size

    companion object {
        /** Downsample raw PCM samples into a fixed number of amplitude bins. */
        fun fromSamples(samples: ShortArray, sampleRate: Int = 44100, numBins: Int = 1000): WaveformData {
            if (samples.isEmpty()) return WaveformData(FloatArray(0), 0f, sampleRate)

            val samplesPerBin = samples.size / numBins
            val peaks = FloatArray(numBins)
            var globalMax = 1f

            for (bin in 0 until numBins) {
                val start = bin * samplesPerBin
                val end = minOf(start + samplesPerBin, samples.size)
                var maxAmp = 0f
                for (i in start until end) {
                    val abs = kotlin.math.abs(samples[i].toInt()).toFloat()
                    if (abs > maxAmp) maxAmp = abs
                }
                peaks[bin] = maxAmp
                if (maxAmp > globalMax) globalMax = maxAmp
            }

            // Normalize to 0.0–1.0
            for (i in peaks.indices) {
                peaks[i] = peaks[i] / globalMax
            }

            return WaveformData(
                peaks = peaks,
                durationSeconds = samples.size.toFloat() / sampleRate,
                sampleRate = sampleRate
            )
        }
    }
}

/**
 * Represents a note overlay item positioned on the waveform timeline.
 */
data class NoteOverlayItem(
    val label: String,          // e.g., "C4", "Am"
    val startFraction: Float,   // 0.0–1.0 position along timeline
    val endFraction: Float,     // end position
    val midiPitch: Int
)

/**
 * Waveform visualization with note name overlay and playback position.
 *
 * Features:
 * - Audio waveform display from amplitude data
 * - Note names overlaid at their time positions
 * - Playback position indicator (vertical line)
 * - Touch-to-seek support
 */
@Composable
fun WaveformView(
    waveformData: WaveformData?,
    notes: List<MusicalNote>,
    playbackProgress: Float,
    durationMs: Int,
    tempoBpm: Int,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (waveformData == null || waveformData.peaks.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text("Loading waveform...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Compute note overlay positions
    val noteOverlays = remember(notes, tempoBpm, durationMs) {
        computeNoteOverlays(notes, tempoBpm, durationMs)
    }

    val textMeasurer = rememberTextMeasurer()
    val waveColor = MaterialTheme.colorScheme.primary
    val waveBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val playheadColor = MaterialTheme.colorScheme.error
    val noteBoxColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val noteBorderColor = MaterialTheme.colorScheme.tertiary
    val noteTextColor = MaterialTheme.colorScheme.onTertiaryContainer
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val timeTextStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp
    )
    val noteTextStyle = TextStyle(
        color = noteTextColor,
        fontSize = 11.sp
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val waveTop = 30f  // space for time markers at top
        val waveBottom = h - 30f  // space for note labels at bottom
        val waveHeight = waveBottom - waveTop
        val waveMid = waveTop + waveHeight / 2f

        // Draw waveform
        drawWaveform(waveformData, w, waveTop, waveBottom, waveColor, waveBgColor)

        // Draw note overlays
        for (overlay in noteOverlays) {
            val x1 = overlay.startFraction * w
            val x2 = overlay.endFraction * w
            val noteWidth = (x2 - x1).coerceAtLeast(2f)

            // Note region highlight
            drawRect(
                color = noteBoxColor,
                topLeft = Offset(x1, waveTop),
                size = Size(noteWidth, waveHeight)
            )

            // Note label at bottom
            val labelResult = textMeasurer.measure(overlay.label, noteTextStyle)
            val labelX = x1 + 2f
            val labelY = waveBottom + 4f
            if (labelX + labelResult.size.width < w) {
                drawText(labelResult, topLeft = Offset(labelX, labelY))
            }
        }

        // Draw time markers along the top
        val durationSec = waveformData.durationSeconds
        if (durationSec > 0) {
            val tickInterval = when {
                durationSec < 5 -> 1f
                durationSec < 20 -> 2f
                durationSec < 60 -> 5f
                else -> 10f
            }
            var t = 0f
            while (t <= durationSec) {
                val x = (t / durationSec) * w
                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = Offset(x, waveTop),
                    end = Offset(x, waveTop + 8f),
                    strokeWidth = 1f
                )
                val timeLabel = "${t.toInt()}s"
                val timeLabelResult = textMeasurer.measure(timeLabel, timeTextStyle)
                drawText(timeLabelResult, topLeft = Offset(x + 2f, 2f))
                t += tickInterval
            }
        }

        // Draw playback position line
        if (playbackProgress > 0f) {
            val playX = playbackProgress * w
            drawLine(
                color = playheadColor,
                start = Offset(playX, 0f),
                end = Offset(playX, h),
                strokeWidth = 2.5f
            )
        }

        // Draw center line
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(0f, waveMid),
            end = Offset(w, waveMid),
            strokeWidth = 0.5f
        )
    }
}

private fun DrawScope.drawWaveform(
    data: WaveformData,
    width: Float,
    top: Float,
    bottom: Float,
    waveColor: Color,
    bgColor: Color
) {
    val mid = (top + bottom) / 2f
    val maxAmp = (bottom - top) / 2f
    val bins = data.peaks.size
    if (bins == 0) return

    val binWidth = width / bins

    // Draw filled waveform
    val pathTop = Path()
    val pathBottom = Path()

    pathTop.moveTo(0f, mid)
    pathBottom.moveTo(0f, mid)

    for (i in 0 until bins) {
        val x = i * binWidth
        val amp = data.peaks[i] * maxAmp
        pathTop.lineTo(x, mid - amp)
        pathBottom.lineTo(x, mid + amp)
    }

    pathTop.lineTo(width, mid)
    pathTop.close()
    pathBottom.lineTo(width, mid)
    pathBottom.close()

    drawPath(pathTop, bgColor, style = Fill)
    drawPath(pathBottom, bgColor, style = Fill)

    // Draw waveform outline
    val outlinePath = Path()
    outlinePath.moveTo(0f, mid)
    for (i in 0 until bins) {
        val x = i * binWidth
        val amp = data.peaks[i] * maxAmp
        outlinePath.lineTo(x, mid - amp)
    }
    for (i in bins - 1 downTo 0) {
        val x = i * binWidth
        val amp = data.peaks[i] * maxAmp
        outlinePath.lineTo(x, mid + amp)
    }
    outlinePath.close()

    drawPath(outlinePath, waveColor, style = Stroke(width = 1.5f))
}

/**
 * Convert MusicalNotes to overlay positions based on tempo and duration.
 */
private fun computeNoteOverlays(
    notes: List<MusicalNote>,
    tempoBpm: Int,
    durationMs: Int
): List<NoteOverlayItem> {
    if (notes.isEmpty() || durationMs <= 0) return emptyList()

    val durationSec = durationMs / 1000f
    val beatDurationSec = 60f / tempoBpm
    val divisions = 4 // ticks per quarter note

    val overlays = mutableListOf<NoteOverlayItem>()
    var currentTimeSec = 0f

    for (note in notes) {
        if (note.isRest) {
            currentTimeSec += note.durationTicks * beatDurationSec / divisions
            continue
        }

        val noteDurationSec = note.durationTicks * beatDurationSec / divisions
        val startFraction = (currentTimeSec / durationSec).coerceIn(0f, 1f)
        val endFraction = ((currentTimeSec + noteDurationSec) / durationSec).coerceIn(0f, 1f)

        val label = if (note.chordName != null) {
            note.chordName!!
        } else {
            PitchUtils.midiToNoteName(note.midiPitch)
        }

        overlays.add(NoteOverlayItem(
            label = label,
            startFraction = startFraction,
            endFraction = endFraction,
            midiPitch = note.midiPitch
        ))

        currentTimeSec += noteDurationSec
    }

    return overlays
}
