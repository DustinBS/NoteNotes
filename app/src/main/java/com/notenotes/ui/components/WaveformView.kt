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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.notenotes.model.MusicalNote
import com.notenotes.util.PitchUtils
import com.notenotes.util.GuitarUtils
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException

/**
 * Waveform data holder with downsampled amplitude peaks.
 */
data class WaveformData(
    val peaks: FloatArray,          // normalized peak amplitudes (0.0–1.0), one per bin
    val durationSeconds: Float,     // total audio duration
    val sampleRate: Int = 44100,
    val maxAmplitude: Float = 1f
) {
    val binsCount: Int get() = peaks.size

    companion object {
        /** Downsample raw PCM samples into a fixed number of amplitude bins. */
        fun fromSamples(samples: ShortArray, sampleRate: Int = 44100, numBins: Int? = null): WaveformData {
            if (samples.isEmpty()) return WaveformData(FloatArray(0), 0f, sampleRate)
            
            val durationSec = samples.size.toFloat() / sampleRate
            val actualBins = numBins ?: (durationSec * 80).toInt().coerceAtLeast(200)

            val samplesPerBin = samples.size / actualBins
            val peaks = FloatArray(actualBins)
            var globalMax = 1f

            for (bin in 0 until actualBins) {
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
                sampleRate = sampleRate,
                maxAmplitude = globalMax
            )
        }
    }
}

/**
 * Represents a note overlay item positioned on the waveform timeline.
 */
data class NoteOverlayItem(
    val labelMap: Map<Int, androidx.compose.ui.text.AnnotatedString>, // stringIndex -> label (single-line per string)
    val startFraction: Float,   // 0.0–1.0 position along GLOBAL timeline
    val endFraction: Float,
    val pitches: List<Int>,
    val noteIndex: Int,
    val hasTab: Boolean = false,
    val tabLabel: String? = null // e.g., "S1 F3" for string 1, fret 3
)

// Internal helper for deferring caption draws so we can render them on top.
private data class DeferredLabel(
    val annotated: androidx.compose.ui.text.AnnotatedString,
    val labelX: Float,
    val topY: Float,
    val effectiveTextStyle: TextStyle,
    val stringIndex: Int,
    val noteIndex: Int
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
    onMoveSelectedNote: ((Int, Float) -> Unit)? = null,
    pendingSelectedNoteLines: Map<Int, androidx.compose.ui.text.AnnotatedString>? = null
) {
    // Caption tuning parameters (tweak these values to change outline thickness)
    // - `CAPTION_STROKE_FACTOR`: fraction of font size used for outline stroke width.
    // - `CAPTION_STROKE_MIN_PX`: minimum stroke width in pixels.
    // To make outlines thicker, increase CAPTION_STROKE_FACTOR (e.g. 0.15f).
    val CAPTION_STROKE_FACTOR = 0.13f
    val CAPTION_STROKE_MIN_PX = 0.9f
    // Superscript layout tuning (used for rendering frets/ornaments)
    val SUPERSCRIPT_SHIFT_FACTOR = 0.36f
    val SUPERSCRIPT_FONT_FACTOR = 0.70f
    // Row spacing factor: slightly reduce inter-row spacing for chord captions
    val ROW_SPACING_FACTOR = 0.92f

    val isDummy = waveformData == null || waveformData.durationSeconds <= 0f || waveformData.peaks.isEmpty()
    
    val renderWaveformData = remember(isDummy, waveformData, windowSizeSec) {
        if (isDummy) {
            val dummyBins = 200
            val dummyPeaks = FloatArray(dummyBins) {
                // Random subtle noise
                (java.lang.Math.random().toFloat() * 0.2f) + 0.05f
            }
            WaveformData(dummyPeaks, windowSizeSec, 44100, 1f)
        } else {
            waveformData!!
        }
    }

    val actualDurationSec = if (isDummy) windowSizeSec else durationMs / 1000f
    val durationSec = maxOf(actualDurationSec, windowSizeSec)
    val windowFractionSize = if (durationSec > 0) (windowSizeSec / durationSec) else 1f
    val windowEndFraction = windowStartFraction + windowFractionSize

    val playbackGlobalFraction = if (actualDurationSec > 0) (playbackProgress * actualDurationSec) / durationSec else 0f
    val editGlobalFraction = if (editCursorFraction != null && actualDurationSec > 0) (editCursorFraction * actualDurationSec) / durationSec else null

    // Compute note overlay positions (global fractions)
    val noteOverlays = remember(notes, tempoBpm, durationMs, durationSec) {
        computeNoteOverlays(notes, tempoBpm, durationMs, durationSec)
    }

    // Use fixed guitar string grid (0 = low E / string 6) so rows align across chords
    val maxStrings = com.notenotes.util.GuitarUtils.STRINGS.size

    val textMeasurer = rememberTextMeasurer()
    val waveColor = if (isDummy) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary
    val waveBgColor = if (isDummy) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val playheadColor = MaterialTheme.colorScheme.error
    val noteBoxColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val noteBorderColor = MaterialTheme.colorScheme.tertiary
    val selectedNoteColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
    val selectedNoteBorder = MaterialTheme.colorScheme.error
    val editCursorColor = androidx.compose.ui.graphics.Color.White
    val noteTextColor = MaterialTheme.colorScheme.onTertiaryContainer
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val timeTextStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp
    )
    val noteTextStyle = TextStyle(
        color = noteTextColor,
        fontSize = 12.sp
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

    val effectiveModifier = modifier

    Canvas(
        modifier = effectiveModifier
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
                                        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
                                        vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
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
        // Reserve space at the top for the time/db axis so captions don't overlap it. The bottom
        // edge may be flush with the window per user request.
        // Measure text heights using Compose text measurer so we can reserve
        // sufficient top padding to avoid clipping the topmost string label.
        val maxAmp = renderWaveformData.maxAmplitude
        val maxDbValue = if (maxAmp > 1f) 20f * kotlin.math.log10(maxAmp / 32768f) else -60f
        val topDbLabel = if (maxDbValue <= -60f) "-∞ dB" else String.format("%.1f dB", maxDbValue)
        val midDbLabel = "-∞ dB"

        val timeSample = "0s"
        val timeLabelHeight = textMeasurer.measure(timeSample, timeTextStyle).size.height.toFloat()
        val dbLabelHeight = textMeasurer.measure(topDbLabel, timeTextStyle).size.height.toFloat()

        

        // Keep top padding minimal so note-block overlays sit immediately under the axis.
        // Detailed per-overlay margins will ensure labels don't overlap the axis.
        val topPadding = kotlin.math.max(dbLabelHeight, timeLabelHeight) + 2f
        val waveTop = topPadding
        val waveBottom = h
        val waveHeight = waveBottom - waveTop
        val waveMid = waveTop + waveHeight / 2f

        val moveModeBorderEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

        // Draw waveform (only the sub-range visible in the window)
        drawWindowedWaveform(renderWaveformData, w, waveTop, waveBottom, waveColor, waveBgColor,
            windowStartFraction * durationSec, windowEndFraction * durationSec, windowSizeSec)

        // Draw note overlays (only those visible in window)

        // We'll collect caption draw requests and render them at the end so
        // captions appear on top of all other canvas elements. This also lets
        // us draw optional debug bounding boxes after everything is rendered.
        val deferredLabels = mutableListOf<DeferredLabel>()

        // Split overlays into non-selected then selected so selected overlay boxes/borders
        // are drawn after others (higher visual Z).
        val visibleOverlays = noteOverlays.filter { overlay ->
            !(overlay.endFraction < windowStartFraction || overlay.startFraction > windowEndFraction)
        }
        val nonSelectedOverlays = visibleOverlays.filter { it.noteIndex != selectedNoteIndex }
        val selectedOverlays = visibleOverlays.filter { it.noteIndex == selectedNoteIndex }

        fun renderOverlay(overlay: NoteOverlayItem) {
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

            // Draw per-string rows using full note block height; compute spacing so
            // all six strings fit inside the note block with safe margins for
            // stroke outlines and superscripts.
            val labelX = x1 + 6f

            // Collect annotated strings for this overlay, preferring pending overrides for selected note.
            val annotatedByString = (1..maxStrings).associateWith { human ->
                val pending = if (isSelected) pendingSelectedNoteLines?.get(human) else null
                pending ?: overlay.labelMap[human]
            }

            // Measure per-string heights using the default text style (12sp)
            val measuredHeights = annotatedByString.values.mapNotNull { a ->
                if (a == null || a.text.isEmpty()) null else textMeasurer.measure(a, noteTextStyle).size.height.toFloat()
            }
            val overlayLabelMaxHeight = measuredHeights.maxOrNull() ?: textMeasurer.measure("A", noteTextStyle).size.height.toFloat()

            // Compute safe vertical margins so labels do not clip the waveform edges.
            val defaultFontPx = noteTextStyle.fontSize.toPx()
            val strokeWidthPx = (defaultFontPx * CAPTION_STROKE_FACTOR).coerceAtLeast(CAPTION_STROKE_MIN_PX)
            val topMargin = (overlayLabelMaxHeight / 2f) + strokeWidthPx / 2f
            // Keep bottom margin minimal so the bottommost string can be drawn
            // flush with the overlay bottom (removes trailing empty line).
            val bottomMargin = 0f

            val usableHeight = (waveHeight - topMargin - bottomMargin).coerceAtLeast(4f)
            val rawStep = if (maxStrings > 1) usableHeight / (maxStrings - 1) else usableHeight
            val step = rawStep * ROW_SPACING_FACTOR

            // If labels are taller than the per-string step, scale down the text
            val effectiveTextStyle = if (overlayLabelMaxHeight > 0f && overlayLabelMaxHeight > step * 0.9f) {
                val baseSize = 12f
                val scale = (step * 0.85f) / overlayLabelMaxHeight
                noteTextStyle.copy(fontSize = (baseSize * scale).sp)
            } else noteTextStyle

            // Fixed six-row grid: place each string's label at its canonical row
            // (single notes behave like chords with one active string).
            for (human in 1..maxStrings) {
                val annotated = annotatedByString[human]
                if (annotated != null && annotated.text.isNotEmpty()) {
                    val measured = textMeasurer.measure(annotated, effectiveTextStyle)
                    // Place string 1 (human=1, high E) at the top, human=N at the bottom
                    val centerY = waveTop + topMargin + (human - 1).toFloat() * step
                    val topY = centerY - measured.size.height / 2f
                    val drawTopY = if (human == maxStrings) {
                        // bottom row: flush with overlay bottom
                        (waveBottom - measured.size.height).coerceAtLeast(waveTop)
                    } else topY.coerceAtLeast(waveTop)

                    val fitsVertically = drawTopY + measured.size.height <= waveBottom + 1f
                    if (fitsVertically && labelX + measured.size.width.toFloat() < w && labelX >= 0f) {
                        deferredLabels.add(DeferredLabel(annotated, labelX, drawTopY, effectiveTextStyle, human, overlay.noteIndex))
                    }
                }
            }
        }

        for (overlay in nonSelectedOverlays) renderOverlay(overlay)
        for (overlay in selectedOverlays) renderOverlay(overlay)

        // Draw time markers for the visible window
        if (durationSec > 0) {
            val windowStartSec = windowStartFraction * durationSec
            val windowEndSec = windowEndFraction * durationSec
            val windowDurSec = windowEndSec - windowStartSec

            val targetTick = windowSizeSec * 0.1f
            val tickInterval = if (targetTick >= 10f) {
                kotlin.math.round(targetTick)
            } else {
                val options = listOf(0.25f, 0.5f, 1f, 2f, 3f, 4f, 5f, 10f)
                options.minByOrNull { kotlin.math.abs(it - targetTick) } ?: 1f
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
                val timeLabel = when {
                    tickInterval == 0.25f -> {
                        val str = String.format(java.util.Locale.US, "%.2fs", t)
                        if (str.endsWith("0s")) str.replace("0s", "s") else str
                    }
                    tickInterval < 1f -> String.format(java.util.Locale.US, "%.1fs", t)
                    else -> "${t.toInt()}s"
                }
                val timeLabelResult = textMeasurer.measure(timeLabel, timeTextStyle)
                if (x + timeLabelResult.size.width < w) {
                    drawText(timeLabelResult, topLeft = Offset(x + 2f, 2f))
                }
                t += tickInterval
            }
        }

        // Y-axis grid lines
        drawLine(
            color = Color.Gray.copy(alpha = 0.2f),
            start = Offset(0f, waveTop),
            end = Offset(w, waveTop),
            strokeWidth = 1f
        )
        drawLine(
            color = Color.Gray.copy(alpha = 0.2f),
            start = Offset(0f, waveBottom),
            end = Offset(w, waveBottom),
            strokeWidth = 1f
        )
        // Draw dB label near the top padding area so it does not collide with per-string captions.
        drawContext.canvas.nativeCanvas.drawText(topDbLabel, 5f, (waveTop - 6f).coerceAtLeast(18f), android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 24f
        })

        // Draw center line
        drawLine(
            color = Color.Gray.copy(alpha = 0.4f),
            start = Offset(0f, waveMid),
            end = Offset(w, waveMid),
            strokeWidth = 1.0f
        )
        drawContext.canvas.nativeCanvas.drawText(midDbLabel, 5f, waveMid - 5f, android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 24f
        })

        val hasPlay = playbackGlobalFraction >= 0f && playbackGlobalFraction >= windowStartFraction && playbackGlobalFraction <= windowEndFraction
        val hasEdit = editGlobalFraction != null && editGlobalFraction >= windowStartFraction && editGlobalFraction <= windowEndFraction
        
        var playX = -1f
        var cursorX = -1f

        if (hasPlay) {
            val localFrac = (playbackGlobalFraction - windowStartFraction) / windowFractionSize
            playX = localFrac * w
        }

        if (hasEdit) {
            val localFrac = (editGlobalFraction!! - windowStartFraction) / windowFractionSize
            cursorX = localFrac * w
        }

        val areSynced = hasPlay && hasEdit && kotlin.math.abs(playX - cursorX) < 2f

        if (hasPlay && !areSynced) {
            drawLine(
                color = playheadColor,
                start = Offset(playX, 0f),
                end = Offset(playX, h),
                strokeWidth = 2.5f
            )
            val triPath = Path()
            triPath.moveTo(playX - 8f, 0f)
            triPath.lineTo(playX + 8f, 0f)
            triPath.lineTo(playX, 12f)
            triPath.close()
            drawPath(triPath, playheadColor, style = Fill)
        }

        if (hasEdit && !areSynced) {
            drawLine(
                color = editCursorColor,
                start = Offset(cursorX, 0f),
                end = Offset(cursorX, h),
                strokeWidth = 3f
            )
            val triPath = Path()
            triPath.moveTo(cursorX - 8f, 0f)
            triPath.lineTo(cursorX + 8f, 0f)
            triPath.lineTo(cursorX, 12f)
            triPath.close()
            drawPath(triPath, editCursorColor, style = Fill)
        }

        if (areSynced) {
            val syncX = playX
            drawLine(
                color = playheadColor,
                start = Offset(syncX, 0f),
                end = Offset(syncX, h),
                strokeWidth = 3f
            )
            drawLine(
                color = editCursorColor,
                start = Offset(syncX, 0f),
                end = Offset(syncX, h),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            )
            
            val tipX = syncX
            val leftTri = Path()
            leftTri.moveTo(tipX - 10f, 0f)
            leftTri.lineTo(tipX, 0f)
            leftTri.lineTo(tipX, 14f)
            leftTri.close()
            drawPath(leftTri, editCursorColor, style = Fill)

            val rightTri = Path()
            rightTri.moveTo(tipX, 0f)
            rightTri.lineTo(tipX + 10f, 0f)
            rightTri.lineTo(tipX, 14f)
            rightTri.close()
            drawPath(rightTri, playheadColor, style = Fill)
        }

        // Render deferred caption labels now so they appear on top of other canvas elements.
        if (deferredLabels.isNotEmpty()) {
            // Use shared superscript constants declared above

            // Helper: convert Compose Color to Android ARGB
            fun colorToArgb(c: androidx.compose.ui.graphics.Color): Int = android.graphics.Color.argb(
                (c.alpha * 255f).toInt().coerceIn(0, 255),
                (c.red * 255f).toInt().coerceIn(0, 255),
                (c.green * 255f).toInt().coerceIn(0, 255),
                (c.blue * 255f).toInt().coerceIn(0, 255)
            )

            val sortedDeferred = if (selectedNoteIndex != null) deferredLabels.sortedBy { it.noteIndex == selectedNoteIndex } else deferredLabels
            for (lbl in sortedDeferred) {
                val full = lbl.annotated
                var drawX = lbl.labelX
                val topY = lbl.topY
                val native = drawContext.canvas.nativeCanvas

                // Compute a darker variant of the string color (lbl.stringIndex is human 1-based)
                val base = try { androidx.compose.ui.graphics.Color(com.notenotes.util.GuitarUtils.stringForHuman(lbl.stringIndex).colorArgb) } catch (_: Exception) { androidx.compose.ui.graphics.Color.Black }
                val factor = 0.55f
                val darkerForString = androidx.compose.ui.graphics.Color(
                    red = (base.red * factor).coerceIn(0f, 1f),
                    green = (base.green * factor).coerceIn(0f, 1f),
                    blue = (base.blue * factor).coerceIn(0f, 1f),
                    alpha = base.alpha
                )

                fun drawRun(text: String, runPx: Float, fillCol: androidx.compose.ui.graphics.Color, strokeCol: androidx.compose.ui.graphics.Color, strike: Boolean, baselineShiftPx: Float = 0f) {
                    if (text.isEmpty()) return
                    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = (runPx * CAPTION_STROKE_FACTOR).coerceAtLeast(CAPTION_STROKE_MIN_PX)
                        color = colorToArgb(strokeCol)
                        textSize = runPx
                        isAntiAlias = true
                        isStrikeThruText = false
                    }

                    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.FILL
                        color = colorToArgb(fillCol)
                        textSize = runPx
                        isAntiAlias = true
                        isStrikeThruText = false
                    }

                    val fm = fillPaint.fontMetrics
                    val baseline = topY - fm.ascent - baselineShiftPx

                    val startX = drawX
                    native.drawText(text, startX, baseline, strokePaint)
                    native.drawText(text, startX, baseline, fillPaint)

                    val textWidth = fillPaint.measureText(text)
                    if (strike) {
                        val strikePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            style = android.graphics.Paint.Style.STROKE
                            color = colorToArgb(androidx.compose.ui.graphics.Color.Black)
                            strokeWidth = (runPx * 0.09f).coerceAtLeast(1f)
                            isAntiAlias = true
                        }
                        val strikeY = baseline + (fillPaint.fontMetrics.ascent + fillPaint.fontMetrics.descent) / 2f
                        native.drawLine(startX, strikeY, startX + textWidth, strikeY, strikePaint)
                    }

                    drawX += textWidth
                }

                if (full.spanStyles.isEmpty()) {
                    val runPx = lbl.effectiveTextStyle.fontSize.toPx()
                    drawRun(full.text, runPx, noteTextColor, darkerForString, strike = false)
                } else {
                    var last = 0
                    val spans = full.spanStyles.sortedBy { it.start }
                    for (range in spans) {
                        if (range.start > last) {
                            val plain = full.text.substring(last, range.start)
                            drawRun(plain, lbl.effectiveTextStyle.fontSize.toPx(), noteTextColor, darkerForString, strike = false)
                        }
                        val chunk = full.text.substring(range.start, range.end)
                        val spanStyle = range.item
                        val isStrike = spanStyle.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough
                        val strokeForRange = if (isStrike) androidx.compose.ui.graphics.Color.Black else darkerForString
                        val fillForRange = if (spanStyle.color != androidx.compose.ui.graphics.Color.Unspecified) spanStyle.color else noteTextColor
                        val isSuperscript = spanStyle.baselineShift == BaselineShift.Superscript
                        val runPx = if (spanStyle.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) {
                            spanStyle.fontSize.toPx()
                        } else if (isSuperscript) {
                            lbl.effectiveTextStyle.fontSize.toPx() * SUPERSCRIPT_FONT_FACTOR
                        } else {
                            lbl.effectiveTextStyle.fontSize.toPx()
                        }
                        val baselineShiftPx = if (isSuperscript) (runPx * SUPERSCRIPT_SHIFT_FACTOR) else 0f
                        drawRun(chunk, runPx, fillForRange, strokeForRange, strike = isStrike, baselineShiftPx = baselineShiftPx)
                        last = range.end
                    }
                    if (last < full.text.length) {
                        val tail = full.text.substring(last)
                        drawRun(tail, lbl.effectiveTextStyle.fontSize.toPx(), noteTextColor, darkerForString, strike = false)
                    }
                }

            }
        }
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
    windowStartSec: Float,
    windowEndSec: Float,
    windowSizeSec: Float
) {
    val mid = (top + bottom) / 2f
    val maxAmp = (bottom - top) / 2f
    val bins = data.peaks.size
    if (bins == 0) return

    val actualDurationSec = data.durationSeconds
    val timePerBin = if (bins > 0) actualDurationSec / bins else 0f
    if (timePerBin <= 0f) return

    val startBin = (windowStartSec / timePerBin).toInt().coerceIn(0, bins)
    val endBin = (windowEndSec / timePerBin).toInt().coerceIn(0, bins)
    if (startBin >= endBin) return

    val pathTop = Path()
    val pathBottom = Path()
    
    val firstX = ((startBin * timePerBin - windowStartSec) / windowSizeSec) * width
    pathTop.moveTo(firstX.toFloat(), mid)
    pathBottom.moveTo(firstX.toFloat(), mid)

    for (i in startBin until endBin) {
        val x = (((i * timePerBin - windowStartSec) / windowSizeSec) * width).toFloat()
        val amp = data.peaks[i] * maxAmp
        pathTop.lineTo(x, mid - amp)
        pathBottom.lineTo(x, mid + amp)
    }

    val lastX = ((endBin * timePerBin - windowStartSec) / windowSizeSec) * width
    pathTop.lineTo(lastX.toFloat(), mid)
    pathTop.close()
    pathBottom.lineTo(lastX.toFloat(), mid)
    pathBottom.close()

    drawPath(pathTop, bgColor, style = Fill)
    drawPath(pathBottom, bgColor, style = Fill)

    // Draw outline
    val outlinePath = Path()
    outlinePath.moveTo(firstX.toFloat(), mid)
    for (i in startBin until endBin) {
        val x = (((i * timePerBin - windowStartSec) / windowSizeSec) * width).toFloat()
        val amp = data.peaks[i] * maxAmp
        outlinePath.lineTo(x, mid - amp)
    }
    for (i in (endBin - 1) downTo startBin) {
        val x = (((i * timePerBin - windowStartSec) / windowSizeSec) * width).toFloat()
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
internal fun computeNoteOverlays(
    notes: List<MusicalNote>,
    tempoBpm: Int,
    durationMs: Int,
    paddedDurationSec: Float
): List<NoteOverlayItem> {
    if (notes.isEmpty() || durationMs <= 0) return emptyList()

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
        val startFraction = (noteStartSec / paddedDurationSec).coerceIn(0f, 1f)
        val endFraction = ((noteStartSec + noteDurationSec) / paddedDurationSec).coerceIn(0f, 1f)

        // Build per-string annotated labels and store in a map keyed by human guitar string number (1..6)
        val pairs = note.pitches.mapIndexed { i, p ->
            // Use the human-aligned view; fallback to GuitarUtils.fromMidi and normalize to human 1-based
            val tpHuman = note.safeTabPositionsAsHuman.getOrNull(i) ?: run {
                val fm = com.notenotes.util.GuitarUtils.fromMidi(p)
                if (fm != null) Pair(fm.first, fm.second) else Pair(GuitarUtils.STRINGS.size, 0)
            }
            val human = tpHuman.first.coerceIn(1, GuitarUtils.STRINGS.size)
            Triple(p, human, tpHuman.second)
        }

        val labelMapMutable = mutableMapOf<Int, androidx.compose.ui.text.AnnotatedString>()
        for ((_, strHuman, fret) in pairs) {
            // Build per-string annotated label using a central helper to avoid
            // creating temporary MusicalNote instances at call sites.
            val annotated = com.notenotes.utils.NoteTextUtils.buildPitchFretAnnotatedFromPosition(strHuman, fret)
            if (labelMapMutable.containsKey(strHuman)) {
                val existing = labelMapMutable[strHuman]!!
                val combined = androidx.compose.ui.text.buildAnnotatedString {
                    append(existing)
                    append("/")
                    append(annotated)
                }
                labelMapMutable[strHuman] = combined
            } else {
                labelMapMutable[strHuman] = annotated
            }
        }
        // Do not auto-fill missing strings with open-string labels here.
        // The WaveformView rendering will display only the strings present
        // in `labelMap` for each overlay (single notes are treated as n=1).
        val labelMap = labelMapMutable.toMap()

        overlays.add(NoteOverlayItem(
            labelMap = labelMap,
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




