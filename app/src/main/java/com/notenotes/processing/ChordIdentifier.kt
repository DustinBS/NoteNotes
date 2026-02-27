package com.notenotes.processing

/**
 * Identifies chord names from a set of detected MIDI notes using template matching.
 *
 * Supports common guitar chord types: major, minor, 7th, maj7, min7,
 * diminished, augmented, sus2, sus4, add9, power chords.
 */
object ChordIdentifier {

    /**
     * Result of chord identification.
     */
    data class ChordResult(
        val name: String,           // e.g., "C", "Am", "G7", "Dsus4"
        val rootPitchClass: Int,    // 0=C, 1=C#, ..., 11=B
        val rootName: String,       // "C", "C#", "D", etc.
        val quality: String,        // "maj", "min", "7", "maj7", etc.
        val midiNotes: List<Int>,   // original MIDI notes
        val confidence: Double      // 0.0-1.0
    )

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * Chord templates: quality name → list of semitone intervals from root.
     * Ordered from most specific (more notes) to least specific to prefer richer matches.
     */
    private val CHORD_TEMPLATES = listOf(
        // 4-note chords first (more specific)
        "maj7" to listOf(0, 4, 7, 11),
        "min7" to listOf(0, 3, 7, 10),
        "7" to listOf(0, 4, 7, 10),
        "dim7" to listOf(0, 3, 6, 9),
        "min7b5" to listOf(0, 3, 6, 10),
        "add9" to listOf(0, 4, 7, 14),   // root, 3, 5, 9
        "madd9" to listOf(0, 3, 7, 14),
        // 3-note chords
        "maj" to listOf(0, 4, 7),
        "min" to listOf(0, 3, 7),
        "dim" to listOf(0, 3, 6),
        "aug" to listOf(0, 4, 8),
        "sus2" to listOf(0, 2, 7),
        "sus4" to listOf(0, 5, 7),
        // 2-note chords
        "5" to listOf(0, 7),             // power chord
    )

    /**
     * Display-friendly quality suffixes.
     */
    private val QUALITY_DISPLAY = mapOf(
        "maj" to "",        // C major = just "C"
        "min" to "m",       // A minor = "Am"
        "7" to "7",
        "maj7" to "maj7",
        "min7" to "m7",
        "dim" to "dim",
        "dim7" to "dim7",
        "aug" to "aug",
        "sus2" to "sus2",
        "sus4" to "sus4",
        "add9" to "add9",
        "madd9" to "madd9",
        "min7b5" to "m7b5",
        "5" to "5"
    )

    /**
     * Identify a chord from a list of MIDI note numbers.
     *
     * @param midiNotes List of MIDI notes (e.g., [40, 47, 52] for E2-B2-E3)
     * @return ChordResult if a chord is identified, null for single notes or no match
     */
    fun identifyChord(midiNotes: List<Int>): ChordResult? {
        if (midiNotes.size < 2) return null

        // Reduce to pitch classes (mod 12), keep unique
        val pitchClasses = midiNotes.map { ((it % 12) + 12) % 12 }.distinct().sorted()
        if (pitchClasses.size < 2) return null // All same pitch class (e.g., octaves)

        var bestMatch: ChordResult? = null
        var bestScore = 0.0

        // Try each of the 12 possible roots
        for (rootPc in 0..11) {
            for ((quality, template) in CHORD_TEMPLATES) {
                val templatePcs = template.map { (rootPc + it) % 12 }.toSet()

                // Count: how many detected pitch classes match the template
                val matches = pitchClasses.count { it in templatePcs }
                val extras = pitchClasses.count { it !in templatePcs }
                val missing = templatePcs.count { it !in pitchClasses }

                // Score formula:
                // - Reward matches
                // - Penalize extras (notes not in template)
                // - Penalize missing template notes (but less than extras)
                // - Bonus for root being the bass note
                val totalParts = matches + extras + missing * 0.5
                if (totalParts <= 0) continue
                var score = matches.toDouble() / totalParts

                // Require at least 2 matching pitch classes
                if (matches < 2) continue

                // Bonus: if the bass note (lowest MIDI) has the root pitch class
                val bassNote = midiNotes.min()
                if (((bassNote % 12) + 12) % 12 == rootPc) {
                    score *= 1.15 // 15% bonus for root position
                }

                // Penalty for too many extras
                if (extras > 1) score *= 0.7

                // Prefer templates that match the detected note count better
                if (matches == pitchClasses.size && missing == 0) {
                    score *= 1.2 // perfect match bonus
                }

                if (score > bestScore) {
                    bestScore = score
                    val displaySuffix = QUALITY_DISPLAY[quality] ?: quality
                    val rootName = NOTE_NAMES[rootPc]
                    bestMatch = ChordResult(
                        name = "$rootName$displaySuffix",
                        rootPitchClass = rootPc,
                        rootName = rootName,
                        quality = quality,
                        midiNotes = midiNotes,
                        confidence = score.coerceIn(0.0, 1.0)
                    )
                }
            }
        }

        return bestMatch
    }

    /**
     * Get a simple display name for a single MIDI note (e.g., "E4", "A2").
     */
    fun midiToNoteName(midiNote: Int): String {
        if (midiNote !in 0..127) return "?"
        val octave = (midiNote / 12) - 1
        val noteIndex = ((midiNote % 12) + 12) % 12
        return "${SHARP_NAMES[noteIndex]}$octave"
    }
}
