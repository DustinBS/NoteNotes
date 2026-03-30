package com.notenotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Small helpers to standardize playback lifecycle handling across composables.
 */
@Composable
fun StopPlaybackOnDispose(stopPlayback: () -> Unit) {
    DisposableEffect(Unit) {
        onDispose {
            try { stopPlayback() } catch (_: Exception) {}
        }
    }
}
