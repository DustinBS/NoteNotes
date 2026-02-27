package com.notenotes.processing

import com.notenotes.model.KeySignature
import com.notenotes.util.PitchUtils

/**
 * Key signature detection using the Krumhansl-Schmuckler key-finding algorithm.
 * 
 * Analyzes the distribution of pitch classes in detected notes and correlates
 * against known major and minor key profiles to determine the most likely key.
 * 
 * Reference: Krumhansl, C. L. (1990). Cognitive foundations of musical pitch.
 */
class KeyDetector {

    companion object {
        // Krumhansl-Kessler probe-tone ratings for major keys
        // Index 0 = tonic, rotated for each key
        val MAJOR_PROFILE = doubleArrayOf(
            6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
        )

        // Krumhansl-Kessler probe-tone ratings for minor keys
        val MINOR_PROFILE = doubleArrayOf(
            6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
        )

        // Pitch class names in chromatic order starting from C
        private val PITCH_CLASS_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        
        // Flat equivalents for keys that use flats
        private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

        // Map from root pitch class to fifths value for major keys
        private val MAJOR_FIFTHS = mapOf(
            0 to 0,   // C
            1 to 7,   // C# (or Db = -5)
            2 to 2,   // D
            3 to -3,  // Eb
            4 to 4,   // E
            5 to -1,  // F
            6 to 6,   // F# (or Gb = -6)
            7 to 1,   // G
            8 to -4,  // Ab
            9 to 3,   // A
            10 to -2, // Bb
            11 to 5   // B (or Cb = -7)
        )

        // Map from root pitch class to fifths value for minor keys
        private val MINOR_FIFTHS = mapOf(
            0 to -3,  // C minor
            1 to 4,   // C# minor
            2 to -1,  // D minor
            3 to -6,  // Eb minor (6 flats)
            4 to 1,   // E minor
            5 to -4,  // F minor
            6 to 3,   // F# minor
            7 to -2,  // G minor
            8 to 5,   // G# minor (or Ab minor = -7)
            9 to 0,   // A minor
            10 to -5, // Bb minor
            11 to 2   // B minor
        )
    }

    /**
     * Detect the key signature from a list of MIDI note numbers.
     * @param midiNotes list of detected MIDI note numbers
     * @return the most likely key signature, or C major if input is ambiguous
     */
    fun detectKey(midiNotes: List<Int>): KeySignature {
        if (midiNotes.isEmpty()) return KeySignature.C_MAJOR

        // Build pitch class histogram
        val histogram = buildPitchClassHistogram(midiNotes)

        // Check if input is too sparse or uniform
        val nonZeroCount = histogram.count { it > 0 }
        if (nonZeroCount <= 1) {
            // Only one pitch class — ambiguous, return major key of that note
            val pc = histogram.indexOfFirst { it > 0 }
            if (pc >= 0) {
                return buildKeySignature(pc, "major")
            }
            return KeySignature.C_MAJOR
        }

        // Correlate against all 24 key profiles
        var bestCorrelation = Double.NEGATIVE_INFINITY
        var bestPitchClass = 0
        var bestMode = "major"

        for (pitchClass in 0..11) {
            // Rotate the profile to start from this pitch class
            val majorCorr = correlate(histogram, rotateProfile(MAJOR_PROFILE, pitchClass))
            val minorCorr = correlate(histogram, rotateProfile(MINOR_PROFILE, pitchClass))

            if (majorCorr > bestCorrelation) {
                bestCorrelation = majorCorr
                bestPitchClass = pitchClass
                bestMode = "major"
            }
            if (minorCorr > bestCorrelation) {
                bestCorrelation = minorCorr
                bestPitchClass = pitchClass
                bestMode = "minor"
            }
        }

        return buildKeySignature(bestPitchClass, bestMode)
    }

    /**
     * Detect key from a list of frequencies in Hz.
     */
    fun detectKeyFromFrequencies(frequencies: List<Double>): KeySignature {
        val midiNotes = frequencies
            .map { PitchUtils.frequencyToMidi(it) }
            .filter { it >= 0 }
        return detectKey(midiNotes)
    }

    /**
     * Build a pitch class histogram from MIDI notes.
     * Returns array of 12 counts, indexed by pitch class (C=0, C#=1, ..., B=11).
     */
    fun buildPitchClassHistogram(midiNotes: List<Int>): DoubleArray {
        val histogram = DoubleArray(12)
        for (note in midiNotes) {
            if (note in 0..127) {
                val pitchClass = PitchUtils.midiToPitchClass(note)
                histogram[pitchClass] += 1.0
            }
        }
        return histogram
    }

    /**
     * Rotate a profile array so that index 0 maps to the given pitch class.
     * E.g., rotating by 7 makes the profile start from G.
     */
    private fun rotateProfile(profile: DoubleArray, shift: Int): DoubleArray {
        val rotated = DoubleArray(12)
        for (i in 0..11) {
            rotated[i] = profile[(i - shift + 12) % 12]
        }
        return rotated
    }

    /**
     * Compute Pearson correlation coefficient between two arrays.
     */
    private fun correlate(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size && a.size == 12)

        val meanA = a.average()
        val meanB = b.average()

        var numerator = 0.0
        var denomA = 0.0
        var denomB = 0.0

        for (i in 0..11) {
            val diffA = a[i] - meanA
            val diffB = b[i] - meanB
            numerator += diffA * diffB
            denomA += diffA * diffA
            denomB += diffB * diffB
        }

        val denominator = kotlin.math.sqrt(denomA * denomB)
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    /**
     * Build a KeySignature from pitch class and mode.
     */
    private fun buildKeySignature(pitchClass: Int, mode: String): KeySignature {
        val fifths = if (mode == "major") {
            MAJOR_FIFTHS[pitchClass] ?: 0
        } else {
            MINOR_FIFTHS[pitchClass] ?: 0
        }

        // Use flat names for keys with flats, sharp names for keys with sharps
        val rootName = if (fifths < 0) {
            FLAT_NAMES[pitchClass]
        } else {
            PITCH_CLASS_NAMES[pitchClass]
        }

        return KeySignature(rootName, mode, fifths)
    }
}
