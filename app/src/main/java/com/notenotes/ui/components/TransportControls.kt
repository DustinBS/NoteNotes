package com.notenotes.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notenotes.audio.AudioPlayer

/**
 * DAW-style transport controls with global scrub bar and window navigation.
 */
@Composable
fun TransportControls(
    playbackState: AudioPlayer.PlaybackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    durationMs: Int = 0,
    currentProgress: Float = 0f,
    onSeek: (Float) -> Unit = {},
    // Window controls
    windowStartFraction: Float = 0f,
    windowSizeSec: Float = 5f,
    isWindowLocked: Boolean = false,
    onWindowBack: () -> Unit = {},
    onWindowForward: () -> Unit = {},
    onToggleLock: () -> Unit = {},
    // Speed controls
    playbackSpeed: Float = 1f,
    onSpeedChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Global scrub bar (Slider) - this is the main seek control
        if (durationMs > 0) {
            var sliderPosition by remember(currentProgress) { mutableFloatStateOf(currentProgress) }
            var isSliding by remember { mutableStateOf(false) }

            Slider(
                value = if (isSliding) sliderPosition else currentProgress,
                onValueChange = { newVal ->
                    isSliding = true
                    sliderPosition = newVal
                    onSeek(newVal) // Update window in real-time while scrubbing
                },
                onValueChangeFinished = {
                    onSeek(sliderPosition)
                    isSliding = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .height(24.dp)
            )

            // Time display
            val displayProgress = if (isSliding) sliderPosition else currentProgress
            val currentMs = (displayProgress * durationMs).toInt()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(currentMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Window info
                val windowStartSec = windowStartFraction * durationMs / 1000f
                Text(
                    text = "Window: ${String.format("%.1f", windowStartSec)}s - ${String.format("%.1f", windowStartSec + windowSizeSec)}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(0.dp))

        // Control buttons row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Speed selector — fills available space, scrollable if needed
            val speedOptions = listOf(0.25f, 0.5f, 1f, 2f, 3f)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                speedOptions.forEach { speed ->
                    val isSelected = playbackSpeed == speed
                    val label = when {
                        speed == 1f -> "1x"
                        speed == speed.toInt().toFloat() -> "${speed.toInt()}x"
                        else -> "${speed}x"
                    }
                    OutlinedButton(
                        onClick = { onSpeedChange(speed) },
                        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }

            // Playback controls — right side
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Window back
                IconButton(onClick = onWindowBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Window Back", modifier = Modifier.size(22.dp))
                }

                // Play/Pause
                FilledIconButton(
                    onClick = {
                        when (playbackState) {
                            AudioPlayer.PlaybackState.IDLE -> onPlay()
                            AudioPlayer.PlaybackState.PLAYING -> onPause()
                            AudioPlayer.PlaybackState.PAUSED -> onResume()
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = when (playbackState) {
                            AudioPlayer.PlaybackState.PLAYING -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = if (playbackState == AudioPlayer.PlaybackState.PLAYING) "Pause" else "Play",
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Window forward
                IconButton(onClick = onWindowForward, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Window Forward", modifier = Modifier.size(22.dp))
                }

                // Lock toggle
                IconButton(onClick = onToggleLock, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (isWindowLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (isWindowLocked) "Unlock Window" else "Lock Window",
                        tint = if (isWindowLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Format milliseconds to MM:SS string.
 */
private fun formatTime(ms: Int): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}
