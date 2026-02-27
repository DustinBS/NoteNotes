package com.notenotes.util

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Utilities for converting between frequencies (Hz), MIDI note numbers, and note names.
 */
object PitchUtils {

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    
    // For MusicXML, we need sharp and flat versions
    private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** A4 reference frequency */
    const val A4_FREQUENCY = 440.0
    const val A4_MIDI = 69

    /**
     * Convert frequency in Hz to the nearest MIDI note number.
     * Returns -1 if frequency is invalid (<=0 or out of MIDI range).
     */
    fun frequencyToMidi(frequencyHz: Double): Int {
        if (frequencyHz <= 0.0) return -1
        val midiNote = A4_MIDI + 12.0 * ln(frequencyHz / A4_FREQUENCY) / ln(2.0)
        val rounded = midiNote.roundToInt()
        return if (rounded in 0..127) rounded else -1
    }

    /**
     * Convert MIDI note number to frequency in Hz.
     */
    fun midiToFrequency(midiNote: Int): Double {
        return A4_FREQUENCY * 2.0.pow((midiNote - A4_MIDI) / 12.0)
    }

    /**
     * Get note name with octave from MIDI note number (e.g., 60 -> "C4", 69 -> "A4").
     */
    fun midiToNoteName(midiNote: Int): String {
        if (midiNote !in 0..127) return "?"
        val octave = (midiNote / 12) - 1
        val noteIndex = midiNote % 12
        return "${NOTE_NAMES[noteIndex]}$octave"
    }

    /**
     * Get pitch class (0-11) from MIDI note number (C=0, C#=1, ..., B=11).
     */
    fun midiToPitchClass(midiNote: Int): Int {
        return ((midiNote % 12) + 12) % 12  // handle negative notes safely
    }

    /**
     * Get the MusicXML step name (C, D, E, F, G, A, B) and alter value from a MIDI note.
     * Takes key signature fifths to decide sharps vs flats.
     * Returns Triple(step, alter, octave) e.g., (G, 0, 4) or (F, 1, 4) for F#.
     */
    fun midiToMusicXmlPitch(midiNote: Int, keyFifths: Int = 0): Triple<String, Int, Int> {
        val octave = (midiNote / 12) - 1
        val pitchClass = midiNote % 12
        
        // Based on key signature, determine if we use sharps or flats
        val useFlats = keyFifths < 0
        
        return when (pitchClass) {
            0 -> Triple("C", 0, octave)
            1 -> if (useFlats) Triple("D", -1, octave) else Triple("C", 1, octave)
            2 -> Triple("D", 0, octave)
            3 -> if (useFlats) Triple("E", -1, octave) else Triple("D", 1, octave)
            4 -> Triple("E", 0, octave)
            5 -> Triple("F", 0, octave)
            6 -> if (useFlats) Triple("G", -1, octave) else Triple("F", 1, octave)
            7 -> Triple("G", 0, octave)
            8 -> if (useFlats) Triple("A", -1, octave) else Triple("G", 1, octave)
            9 -> Triple("A", 0, octave)
            10 -> if (useFlats) Triple("B", -1, octave) else Triple("A", 1, octave)
            11 -> Triple("B", 0, octave)
            else -> Triple("C", 0, octave)
        }
    }

    /**
     * Get the cents deviation from the nearest MIDI note.
     * Positive = sharp, negative = flat.
     */
    fun frequencyCentsDeviation(frequencyHz: Double): Double {
        if (frequencyHz <= 0.0) return 0.0
        val midiExact = A4_MIDI + 12.0 * ln(frequencyHz / A4_FREQUENCY) / ln(2.0)
        val midiRounded = midiExact.roundToInt()
        return (midiExact - midiRounded) * 100.0
    }

    /**
     * Generate a sine wave as ShortArray (16-bit PCM samples).
     * Useful for testing.
     */
    fun generateSineWave(
        frequencyHz: Double,
        durationSec: Double,
        sampleRate: Int = 44100,
        amplitude: Double = 0.8
    ): ShortArray {
        val numSamples = (sampleRate * durationSec).toInt()
        return ShortArray(numSamples) { i ->
            (Short.MAX_VALUE * amplitude * kotlin.math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate)).toInt().toShort()
        }
    }

    /**
     * Generate silence as ShortArray.
     */
    fun generateSilence(durationSec: Double, sampleRate: Int = 44100): ShortArray {
        return ShortArray((sampleRate * durationSec).toInt())
    }

    /**
     * Concatenate multiple ShortArrays into one.
     */
    fun concatenate(vararg arrays: ShortArray): ShortArray {
        val totalLength = arrays.sumOf { it.size }
        val result = ShortArray(totalLength)
        var offset = 0
        for (array in arrays) {
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }
}
