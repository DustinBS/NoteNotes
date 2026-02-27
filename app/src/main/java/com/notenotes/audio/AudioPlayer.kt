package com.notenotes.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

private const val TAG = "NNAudio"

/**
 * Simple audio player for voice memo playback.
 * Uses Android's MediaPlayer for WAV/OGG playback.
 */
class AudioPlayer {

    enum class PlaybackState {
        IDLE, PLAYING, PAUSED
    }

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    /**
     * Load and play an audio file.
     */
    fun play(file: File) {
        stop()
        try {
            Log.i(TAG, "AudioPlayer.play: ${file.absolutePath}")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    Log.d(TAG, "AudioPlayer: Playback completed")
                    stopProgressPolling()
                    _state.value = PlaybackState.IDLE
                    _progress.value = 0f
                }
                start()
            }
            _state.value = PlaybackState.PLAYING
            startProgressPolling()
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlayer.play: Error", e)
            _state.value = PlaybackState.IDLE
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                stopProgressPolling()
                _state.value = PlaybackState.PAUSED
            }
        }
    }

    /**
     * Resume playback.
     */
    fun resume() {
        mediaPlayer?.let {
            it.start()
            _state.value = PlaybackState.PLAYING
            startProgressPolling()
        }
    }

    /**
     * Stop playback and release resources.
     */
    fun stop() {
        stopProgressPolling()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        mediaPlayer = null
        _state.value = PlaybackState.IDLE
        _progress.value = 0f
    }

    /**
     * Seek to a position (0.0 to 1.0).
     */
    fun seekTo(fraction: Float) {
        mediaPlayer?.let {
            val position = (fraction * it.duration).toInt()
            it.seekTo(position)
            _progress.value = fraction
        }
    }

    /**
     * Get current playback position as fraction (0.0 to 1.0).
     */
    fun getCurrentProgress(): Float {
        val mp = mediaPlayer ?: return 0f
        return if (mp.duration > 0) mp.currentPosition.toFloat() / mp.duration else 0f
    }

    /**
     * Get duration of loaded audio in milliseconds.
     */
    fun getDurationMs(): Int {
        return mediaPlayer?.duration ?: 0
    }

    /**
     * Start polling MediaPlayer position to update progress flow.
     */
    private fun startProgressPolling() {
        stopProgressPolling()
        progressJob = playerScope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying && mp.duration > 0) {
                    _progress.value = mp.currentPosition.toFloat() / mp.duration
                }
                delay(100L)
            }
        }
    }

    /**
     * Stop the progress polling coroutine.
     */
    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * Release all resources. Call when the player is no longer needed.
     */
    fun release() {
        stop()
        playerScope.cancel()
    }
}
