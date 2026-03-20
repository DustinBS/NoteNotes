package com.notenotes.ui.components

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.model.MusicalNote
import com.notenotes.util.PitchUtils
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException

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
        fun fromSamples(samples: ShortArray, sampleRate: Int = 44100, numBins: Int = 2000): WaveformData {
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
    val label: androidx.compose.ui.text.AnnotatedString,          // e.g., "C4", "Am"
    val startFraction: Float,   // 0.0–1.0 position along GLOBAL timeline
    val endFraction: Float,
    val pitches: List<Int>,
    val noteIndex: Int,
    val hasTab: Boolean = false,
    val tabLabel: String? = null // e.g., "S1 F3" for string 1, fret 3
)

/**
 * Windowed waveform visualization with note overlays, playback position, and editing support.
 *
 * Shows only a configurable time window of the audio (default 5s).
 * Tap to place edit cursor for precise note placement.
 * Optional move mode lets users drag the selected note to a new start time.
 */
@Composable
fun WaveformView(
    waveformData: WaveformData?,
    notes: List<MusicalNote>,
    playbackProgress: Float,
    durationMs: Int,
    tempoBpm: Int,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    selectedNoteIndex: Int? = null,
    editCursorFraction: Float? = null,
    onNoteSelected: ((Int?) -> Unit)? = null,
    onEditCursorSet: ((Float?) -> Unit)? = null,
    onEditIntent: ((Int?, Float?, Float?) -> Unit)? = null,
    windowStartFraction: Float = 0f,
    windowSizeSec: Float = 5f,
    isMoveMode: Boolean = false,
    onMoveSelectedNote: ((Int, Float) -> Unit)? = null
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

    val durationSec = durationMs / 1000f
    val windowFractionSize = if (durationSec > 0) (windowSizeSec / durationSec).coerceIn(0.01f, 1f) else 1f
    val windowEndFraction = (windowStartFraction + windowFractionSize).coerceAtMost(1f)

    // Compute note overlay positions (global fractions)
    val noteOverlays = remember(notes, tempoBpm, durationMs) {
        computeNoteOverlays(notes, tempoBpm, durationMs)
    }

    val textMeasurer = rememberTextMeasurer()
    val waveColor = MaterialTheme.colorScheme.primary
    val waveBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val playheadColor = MaterialTheme.colorScheme.error
    val noteBoxColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val noteBorderColor = MaterialTheme.colorScheme.tertiary
    val selectedNoteColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
    val selectedNoteBorder = MaterialTheme.colorScheme.error
    val editCursorColor = MaterialTheme.colorScheme.secondary
    val noteTextColor = MaterialTheme.colorScheme.onTertiaryContainer
    val tabTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val timeTextStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp
    )
    val noteTextStyle = TextStyle(
        color = noteTextColor,
        fontSize = 11.sp
    )
    val tabTextStyle = TextStyle(
        color = tabTextColor,
        fontSize = 9.sp
    )

    val context = LocalContext.current

    val currentNoteOverlays by rememberUpdatedState(noteOverlays)
    val currentWindowStartFraction by rememberUpdatedState(windowStartFraction)
    val currentWindowFractionSize by rememberUpdatedState(windowFractionSize)
    val currentSelectedNoteIndex by rememberUpdatedState(selectedNoteIndex)
    val currentIsMoveMode by rememberUpdatedState(isMoveMode)

    fun dispatchEditIntent(noteIndex: Int?, cursorFraction: Float?, seekFraction: Float?) {
        val intent = onEditIntent
        if (intent != null) {
            intent(noteIndex, cursorFraction, seekFraction)
            return
        }

        onNoteSelected?.invoke(noteIndex)
        onEditCursorSet?.invoke(cursorFraction)
        if (seekFraction != null) onSeek(seekFraction)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) {
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val downX = down.position.x

                    // Race: drag slop vs long-press timeout
                    var dragPointerChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    var isLongPress = false

                    try {
                        withTimeout(longPressTimeoutMs) {
                            dragPointerChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        isLongPress = true
                    }

                    when {
                        isLongPress -> {
                            // Long press: select note + haptic
                            val localFrac = (downX / size.width).coerceIn(0f, 1f)
                            val globalFrac = currentWindowStartFraction + localFrac * currentWindowFractionSize
                            val tappedNote = currentNoteOverlays.find { overlay ->
                                globalFrac >= overlay.startFraction && globalFrac <= overlay.endFraction
                            }
                            if (tappedNote != null) {
                                dispatchEditIntent(tappedNote.noteIndex, null, null)
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val vibratorManager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                                        vibratorManager?.defaultVibrator?.vibrate(
                                            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                                        }
                                    }
                                } catch (_: Exception) {}
                            } else {
                                dispatchEditIntent(null, globalFrac, globalFrac)
                            }
                        }
                        dragPointerChange != null -> {
                            // Drag detected
                            val change = dragPointerChange!!
                            if (currentIsMoveMode && currentSelectedNoteIndex != null && onMoveSelectedNote != null) {
                                val initialSelectedOverlay = currentNoteOverlays.find { it.noteIndex == currentSelectedNoteIndex }
                                if (initialSelectedOverlay != null) {
                                    // By storing grabOffset, the note smoothly tracks the finger without suddenly teleporting the start edge to the pointer.
                                    var grabOffset = change.position.x - (initialSelectedOverlay.startFraction - currentWindowStartFraction) / currentWindowFractionSize * size.width

                                    var dragNoteIndex = currentSelectedNoteIndex!!

                                    fun moveToPointerX(x: Float) {
                                        val adjustedX = x - grabOffset
                                        val localFrac = (adjustedX / size.width)
                                        val globalFrac = currentWindowStartFraction + localFrac * currentWindowFractionSize
                                        val targetStart = globalFrac.coerceIn(0f, 1f)
                                        
                                        // Once we move the note, PreviewViewModel might sort it and change its index!
                                        // We rely on ViewModel sorting logic, but must ensure we follow it.
                                        // However, PreviewViewModel only changes `selectedNoteIndex` which we read via currentSelectedNoteIndex.
                                        onMoveSelectedNote(dragNoteIndex, targetStart)
                                        dragNoteIndex = currentSelectedNoteIndex ?: dragNoteIndex

                                        // Recalculate grabOffset based on the new note position so it stays glued to finger
                                        val newlySortedOverlay = currentNoteOverlays.find { it.noteIndex == dragNoteIndex }
                                        if (newlySortedOverlay != null) {
                                            val noteScreenX = (newlySortedOverlay.startFraction - currentWindowStartFraction) / currentWindowFractionSize * size.width
                                            grabOffset = x - noteScreenX
                                        }

                                        onEditCursorSet?.invoke(null)
                                    }

                                    moveToPointerX(change.position.x)
                                    horizontalDrag(change.id) { dragChange ->
                                        dragChange.consume()
                                        moveToPointerX(dragChange.position.x)
                                    }
                                }
                            } else {
                                val localFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                                val globalFrac = currentWindowStartFraction + localFrac * currentWindowFractionSize
                                dispatchEditIntent(null, globalFrac, globalFrac)
                                horizontalDrag(change.id) { dragChange ->
                                    dragChange.consume()
                                    val lf = (dragChange.position.x / size.width).coerceIn(0f, 1f)
                                    val gf = currentWindowStartFraction + lf * currentWindowFractionSize
                                    dispatchEditIntent(null, gf, gf)
                                }
                            }
                        }
                        else -> {
                            // Short tap
                            val localFrac = (downX / size.width).coerceIn(0f, 1f)
                            val globalFrac = currentWindowStartFraction + localFrac * currentWindowFractionSize
                            dispatchEditIntent(null, globalFrac, globalFrac)
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val waveTop = 30f
        val waveBottom = h - 40f  // more room for note labels + tab info
        val waveHeight = waveBottom - waveTop
        val waveMid = waveTop + waveHeight / 2f
        val moveModeBorderEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

        // Draw waveform (only the sub-range visible in the window)
        drawWindowedWaveform(waveformData, w, waveTop, waveBottom, waveColor, waveBgColor,
            windowStartFraction, windowEndFraction)

        // Draw note overlays (only those visible in window)
        for (overlay in noteOverlays) {
            // Skip notes completely outside window
            if (overlay.endFraction < windowStartFraction || overlay.startFraction > windowEndFraction) continue

            // Map global fraction to window-local x
            val x1 = ((overlay.startFraction - windowStartFraction) / windowFractionSize * w).coerceAtLeast(0f)
            val x2 = ((overlay.endFraction - windowStartFraction) / windowFractionSize * w).coerceAtMost(w)
            val noteWidth = (x2 - x1).coerceAtLeast(2f)
            val isSelected = overlay.noteIndex == selectedNoteIndex
            val isMoveSelected = isSelected && isMoveMode

            // Note region highlight
            drawRect(
                color = if (isMoveSelected) Color.Transparent else if (isSelected) selectedNoteColor else noteBoxColor,
                topLeft = Offset(x1, waveTop),
                size = Size(noteWidth, waveHeight)
            )

            // Note border
            drawRect(
                color = if (isSelected) selectedNoteBorder else noteBorderColor,
                topLeft = Offset(x1, waveTop),
                size = Size(noteWidth, waveHeight),
                style = if (isMoveSelected) {
                    Stroke(width = 3f, pathEffect = moveModeBorderEffect)
                } else {
                    Stroke(width = if (isSelected) 2.5f else 1f)
                }
            )

            // Note label at bottom
            val labelResult = textMeasurer.measure(overlay.label, noteTextStyle)
            val labelX = x1 + 2f
            val labelY = waveBottom + 2f
            if (labelX + labelResult.size.width < w && labelX >= 0f) {
                drawText(labelResult, topLeft = Offset(labelX, labelY))
            }

            // Tab label below note name if exists
            if (overlay.tabLabel != null) {
                val tabResult = textMeasurer.measure(overlay.tabLabel, tabTextStyle)
                val tabY = labelY + labelResult.size.height + 1f
                if (labelX + tabResult.size.width < w && labelX >= 0f) {
                    drawText(tabResult, topLeft = Offset(labelX, tabY))
                }
            }
        }

        // Draw time markers for the visible window
        if (durationSec > 0) {
            val windowStartSec = windowStartFraction * durationSec
            val windowEndSec = windowEndFraction * durationSec
            val windowDurSec = windowEndSec - windowStartSec

            val tickInterval = when {
                windowDurSec < 3 -> 0.5f
                windowDurSec < 10 -> 1f
                windowDurSec < 30 -> 2f
                else -> 5f
            }

            // Start from first tick at or after windowStartSec
            var t = (kotlin.math.ceil(windowStartSec / tickInterval) * tickInterval).toFloat()
            while (t <= windowEndSec) {
                val localFrac = (t - windowStartSec) / windowDurSec
                val x = localFrac * w
                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = Offset(x, waveTop),
                    end = Offset(x, waveTop + 8f),
                    strokeWidth = 1f
                )
                val timeLabel = if (tickInterval < 1f) String.format("%.1fs", t) else "${t.toInt()}s"
                val timeLabelResult = textMeasurer.measure(timeLabel, timeTextStyle)
                if (x + timeLabelResult.size.width < w) {
                    drawText(timeLabelResult, topLeft = Offset(x + 2f, 2f))
                }
                t += tickInterval
            }
        }

        // Draw playback position line (mapped to window)
        if (playbackProgress > 0f && playbackProgress >= windowStartFraction && playbackProgress <= windowEndFraction) {
            val localFrac = (playbackProgress - windowStartFraction) / windowFractionSize
            val playX = localFrac * w
            drawLine(
                color = playheadColor,
                start = Offset(playX, 0f),
                end = Offset(playX, h),
                strokeWidth = 2.5f
            )
        }

        // Draw edit cursor
        if (editCursorFraction != null && editCursorFraction >= windowStartFraction && editCursorFraction <= windowEndFraction) {
            val localFrac = (editCursorFraction - windowStartFraction) / windowFractionSize
            val cursorX = localFrac * w
            drawLine(
                color = editCursorColor,
                start = Offset(cursorX, 0f),
                end = Offset(cursorX, h),
                strokeWidth = 3f
            )
            // Triangle at top
            val triPath = Path()
            triPath.moveTo(cursorX - 8f, 0f)
            triPath.lineTo(cursorX + 8f, 0f)
            triPath.lineTo(cursorX, 12f)
            triPath.close()
            drawPath(triPath, editCursorColor, style = Fill)
        }

        // Center line
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(0f, waveMid),
            end = Offset(w, waveMid),
            strokeWidth = 0.5f
        )
    }
}

/**
 * Draw only the waveform bins visible in the current window.
 */
private fun DrawScope.drawWindowedWaveform(
    data: WaveformData,
    width: Float,
    top: Float,
    bottom: Float,
    waveColor: Color,
    bgColor: Color,
    windowStart: Float,
    windowEnd: Float
) {
    val mid = (top + bottom) / 2f
    val maxAmp = (bottom - top) / 2f
    val bins = data.peaks.size
    if (bins == 0) return

    val startBin = (windowStart * bins).toInt().coerceIn(0, bins - 1)
    val endBin = (windowEnd * bins).toInt().coerceIn(0, bins)
    val visibleBins = endBin - startBin
    if (visibleBins <= 0) return

    val binWidth = width / visibleBins

    // Draw filled waveform
    val pathTop = Path()
    val pathBottom = Path()
    pathTop.moveTo(0f, mid)
    pathBottom.moveTo(0f, mid)

    for (i in startBin until endBin) {
        val x = (i - startBin) * binWidth
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

    // Draw outline
    val outlinePath = Path()
    outlinePath.moveTo(0f, mid)
    for (i in startBin until endBin) {
        val x = (i - startBin) * binWidth
        val amp = data.peaks[i] * maxAmp
        outlinePath.lineTo(x, mid - amp)
    }
    for (i in (endBin - 1) downTo startBin) {
        val x = (i - startBin) * binWidth
        val amp = data.peaks[i] * maxAmp
        outlinePath.lineTo(x, mid + amp)
    }
    outlinePath.close()
    drawPath(outlinePath, waveColor, style = Stroke(width = 1.5f))
}

/**
 * Convert MusicalNotes to overlay positions based on tempo and duration.
 * Notes with timePositionMs use exact positioning; others use cumulative timing.
 */
private fun computeNoteOverlays(
    notes: List<MusicalNote>,
    tempoBpm: Int,
    durationMs: Int
): List<NoteOverlayItem> {
    if (notes.isEmpty() || durationMs <= 0) return emptyList()

    val durationSec = durationMs / 1000f
    val beatDurationSec = 60f / tempoBpm
    val divisions = 4

    val overlays = mutableListOf<NoteOverlayItem>()
    var currentTimeSec = 0f
    var noteIndex = 0

    for (note in notes) {
        if (note.isRest) {
            currentTimeSec += note.durationTicks * beatDurationSec / divisions
            noteIndex++
            continue
        }

        // Use timePositionMs for precise positioning if available
        val noteStartSec = if (note.timePositionMs != null) {
            note.timePositionMs / 1000f
        } else {
            currentTimeSec
        }

        val noteDurationSec = note.durationTicks * beatDurationSec / divisions
        val startFraction = (noteStartSec / durationSec).coerceIn(0f, 1f)
        val endFraction = ((noteStartSec + noteDurationSec) / durationSec).coerceIn(0f, 1f)

        val label = com.notenotes.utils.NoteTextUtils.buildPitchFretAnnotated(note) 

        overlays.add(NoteOverlayItem(
            label = label,
            startFraction = startFraction,
            endFraction = endFraction,
            pitches = note.pitches,
            noteIndex = noteIndex,
            hasTab = note.hasTab,
            tabLabel = null
        ))

        currentTimeSec = noteStartSec + noteDurationSec
        noteIndex++
    }

    return overlays
}
