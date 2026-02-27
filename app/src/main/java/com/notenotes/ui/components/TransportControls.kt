package com.notenotes.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Window back
            IconButton(onClick = onWindowBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Window Back", modifier = Modifier.size(24.dp))
            }

            // Stop
            IconButton(
                onClick = onStop,
                enabled = playbackState != AudioPlayer.PlaybackState.IDLE,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.width(8.dp))

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

            Spacer(modifier = Modifier.width(8.dp))

            // Window forward
            IconButton(onClick = onWindowForward, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Window Forward", modifier = Modifier.size(24.dp))
            }

            // Lock toggle
            IconButton(onClick = onToggleLock, modifier = Modifier.size(44.dp)) {
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

/**
 * Format milliseconds to MM:SS string.
 */
private fun formatTime(ms: Int): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}
