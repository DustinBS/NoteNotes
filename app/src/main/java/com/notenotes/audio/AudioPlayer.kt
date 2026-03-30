package com.notenotes.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import com.notenotes.audio.PlaybackUtils

private const val TAG = "NNAudio"

/**
 * Simple audio player for voice memo playback.
 * Uses Android's MediaPlayer for WAV/OGG playback.
 */
class AudioPlayer {

    init {
        // Register so other screens can pause/stop players when loading new audio
        PlaybackUtils.register(this)
    }

    enum class PlaybackState {
        IDLE, PLAYING, PAUSED
    }

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var currentSpeed = 1f
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    /**
     * Load an audio file without playing it immediately.
     */
    fun load(file: File) {
        stop()
        try {
            Log.i(TAG, "AudioPlayer.load: ${file.absolutePath}")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                try { playbackParams = playbackParams.setSpeed(currentSpeed) } catch (e:Exception) {}
                setOnCompletionListener {
                    Log.d(TAG, "AudioPlayer: Playback completed")
                    stopProgressPolling()
                    _state.value = PlaybackState.IDLE
                    _progress.value = 1.0f
                }
            }
            _state.value = PlaybackState.PAUSED
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlayer.load: Error", e)
            _state.value = PlaybackState.IDLE
        }
    }

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
                try { playbackParams = playbackParams.setSpeed(currentSpeed) } catch (e:Exception) {}
                setOnCompletionListener {
                    Log.d(TAG, "AudioPlayer: Playback completed")
                    stopProgressPolling()
                    _state.value = PlaybackState.IDLE
                    _progress.value = 1.0f
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
            try {
                // Ensure resumed playback uses the currently selected speed.
                it.playbackParams = it.playbackParams.setSpeed(currentSpeed)
            } catch (_: Exception) {}
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
     * Updates progress even if media player hasn't been initialized yet,
     * so the UI reflects the scrubbed position.
     */
    fun seekTo(fraction: Float) {
        _progress.value = fraction
        mediaPlayer?.let {
            val position = (fraction * it.duration).toInt()
            it.seekTo(position)
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
     * Set playback speed (e.g. 0.25, 0.5, 1.0).
     * Requires API 23+ (Android 6.0+), which is above our minSdk 26.
     */
    fun setPlaybackSpeed(speed: Float) {
        // Always update the stored current speed so newly-created players use it.
        currentSpeed = speed
        mediaPlayer?.let {
            try {
                val params = it.playbackParams.setSpeed(speed)
                it.playbackParams = params
                Log.d(TAG, "AudioPlayer: Speed set to ${speed}x")
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlayer: Failed to set speed", e)
            }
        }
    }

    /**
     * Release all resources. Call when the player is no longer needed.
     */
    fun release() {
        try { PlaybackUtils.unregister(this) } catch (_: Exception) {}
        stop()
        playerScope.cancel()
    }
}
