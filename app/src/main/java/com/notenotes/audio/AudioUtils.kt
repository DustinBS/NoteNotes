package com.notenotes.audio

import android.content.Context
import java.io.File

object AudioUtils {
    fun saveToTemporaryWaveFile(context: Context, samples: ShortArray, filename: String = "temp_recording.wav"): File {
        val file = File(context.cacheDir, filename)
        WavWriter.writeWav(samples, file)
        return file
    }
}