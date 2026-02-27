package com.notenotes.model

data class KeySignature(
    val root: String,       // "C", "G", "F#", etc.
    val mode: String,       // "major" or "minor"
    val fifths: Int         // MusicXML fifths value: -7 to +7
) {
    override fun toString(): String = "$root $mode"

    companion object {
        val C_MAJOR = KeySignature("C", "major", 0)
        val G_MAJOR = KeySignature("G", "major", 1)
        val D_MAJOR = KeySignature("D", "major", 2)
        val A_MAJOR = KeySignature("A", "major", 3)
        val E_MAJOR = KeySignature("E", "major", 4)
        val B_MAJOR = KeySignature("B", "major", 5)
        val F_SHARP_MAJOR = KeySignature("F#", "major", 6)
        val C_SHARP_MAJOR = KeySignature("C#", "major", 7)
        val F_MAJOR = KeySignature("F", "major", -1)
        val B_FLAT_MAJOR = KeySignature("Bb", "major", -2)
        val E_FLAT_MAJOR = KeySignature("Eb", "major", -3)
        val A_FLAT_MAJOR = KeySignature("Ab", "major", -4)
        val D_FLAT_MAJOR = KeySignature("Db", "major", -5)
        val G_FLAT_MAJOR = KeySignature("Gb", "major", -6)
        val C_FLAT_MAJOR = KeySignature("Cb", "major", -7)

        val A_MINOR = KeySignature("A", "minor", 0)
        val E_MINOR = KeySignature("E", "minor", 1)
        val B_MINOR = KeySignature("B", "minor", 2)
        val F_SHARP_MINOR = KeySignature("F#", "minor", 3)
        val C_SHARP_MINOR = KeySignature("C#", "minor", 4)
        val G_SHARP_MINOR = KeySignature("G#", "minor", 5)
        val D_SHARP_MINOR = KeySignature("D#", "minor", 6)
        val A_SHARP_MINOR = KeySignature("A#", "minor", 7)
        val D_MINOR = KeySignature("D", "minor", -1)
        val G_MINOR = KeySignature("G", "minor", -2)
        val C_MINOR = KeySignature("C", "minor", -3)
        val F_MINOR = KeySignature("F", "minor", -4)
        val B_FLAT_MINOR = KeySignature("Bb", "minor", -5)
        val E_FLAT_MINOR = KeySignature("Eb", "minor", -6)
        val A_FLAT_MINOR = KeySignature("Ab", "minor", -7)

        val ALL_KEYS = listOf(
            C_MAJOR, G_MAJOR, D_MAJOR, A_MAJOR, E_MAJOR, B_MAJOR, F_SHARP_MAJOR, C_SHARP_MAJOR,
            F_MAJOR, B_FLAT_MAJOR, E_FLAT_MAJOR, A_FLAT_MAJOR, D_FLAT_MAJOR, G_FLAT_MAJOR, C_FLAT_MAJOR,
            A_MINOR, E_MINOR, B_MINOR, F_SHARP_MINOR, C_SHARP_MINOR, G_SHARP_MINOR, D_SHARP_MINOR, A_SHARP_MINOR,
            D_MINOR, G_MINOR, C_MINOR, F_MINOR, B_FLAT_MINOR, E_FLAT_MINOR, A_FLAT_MINOR
        )
    }
}
