package com.notenotes.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

private const val TAG = "NNAudio"

/**
 * Records audio using AudioRecord for raw PCM access.
 * Saves to WAV file and provides raw samples for processing.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    enum class RecordingState {
        IDLE, RECORDING, STOPPED
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state
    
    private val _amplitudeLevel = MutableStateFlow(0f)
    val amplitudeLevel: StateFlow<Float> = _amplitudeLevel

    // Use a list of ShortArray chunks to avoid boxing overhead of MutableList<Short>
    private var rawChunks = mutableListOf<ShortArray>()
    private var totalSamples = 0

    /**
     * Check if RECORD_AUDIO permission is granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording audio.
     * @return true if recording started successfully
     */
    fun startRecording(): Boolean {
        if (_state.value == RecordingState.RECORDING) return false
        if (!hasPermission()) {
            Log.e(TAG, "startRecording: RECORD_AUDIO permission not granted")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        Log.d(TAG, "startRecording: bufferSize=$bufferSize")
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "startRecording: Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "startRecording: AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            rawChunks = mutableListOf()
            totalSamples = 0
            audioRecord?.startRecording()
            _state.value = RecordingState.RECORDING
            Log.i(TAG, "startRecording: Recording started at ${SAMPLE_RATE}Hz")

            // Read audio data in a coroutine
            recordingJob = recordingScope.launch {
                val buffer = ShortArray(bufferSize / 2)
                while (isActive && _state.value == RecordingState.RECORDING) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        val chunk = buffer.copyOfRange(0, read)
                        synchronized(rawChunks) {
                            rawChunks.add(chunk)
                            totalSamples += read
                        }
                        // Update amplitude level for waveform display
                        val maxAmplitude = buffer.take(read).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                        _amplitudeLevel.value = maxAmplitude.toFloat() / Short.MAX_VALUE
                    }
                }
                Log.d(TAG, "startRecording: Recording coroutine ended, total samples: $totalSamples")
            }

            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "startRecording: SecurityException", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: Unexpected error", e)
            return false
        }
    }

    /**
     * Stop recording and return the raw samples.
     * @return ShortArray of recorded PCM samples
     */
    fun stopRecording(): ShortArray {
        Log.i(TAG, "stopRecording: Stopping recording...")
        _state.value = RecordingState.STOPPED
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _amplitudeLevel.value = 0f

        return synchronized(rawChunks) {
            val result = ShortArray(totalSamples)
            var offset = 0
            for (chunk in rawChunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            Log.i(TAG, "stopRecording: Captured ${result.size} samples (${result.size.toDouble() / SAMPLE_RATE}s)")
            // Clear chunks to free memory (M11)
            rawChunks.clear()
            totalSamples = 0
            result
        }
    }

    /**
     * Save raw PCM samples to a WAV file.
     */
    fun saveToWav(samples: ShortArray, file: File) {
        WavWriter.writeWav(samples, file, SAMPLE_RATE)
    }

    /**
     * Get the current recording duration in seconds.
     */
    fun getRecordingDurationSeconds(): Double {
        return synchronized(rawChunks) {
            totalSamples.toDouble() / SAMPLE_RATE
        }
    }
}
