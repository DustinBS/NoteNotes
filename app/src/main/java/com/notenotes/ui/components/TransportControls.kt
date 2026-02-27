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
 * Playback transport controls (play/pause/stop) for voice memo.
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Progress indicator
        if (durationMs > 0) {
            LinearProgressIndicator(
                progress = currentProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            // Time display
            val currentMs = (currentProgress * durationMs).toInt()
            Text(
                text = "${formatTime(currentMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Control buttons
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Stop button
            IconButton(
                onClick = onStop,
                enabled = playbackState != AudioPlayer.PlaybackState.IDLE
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Play/Pause button
            FilledIconButton(
                onClick = {
                    when (playbackState) {
                        AudioPlayer.PlaybackState.IDLE -> onPlay()
                        AudioPlayer.PlaybackState.PLAYING -> onPause()
                        AudioPlayer.PlaybackState.PAUSED -> onResume()
                    }
                },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = when (playbackState) {
                        AudioPlayer.PlaybackState.PLAYING -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = when (playbackState) {
                        AudioPlayer.PlaybackState.PLAYING -> "Pause"
                        else -> "Play"
                    },
                    modifier = Modifier.size(32.dp)
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
