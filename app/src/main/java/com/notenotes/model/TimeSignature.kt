package com.notenotes.model

data class TimeSignature(
    val beats: Int,        // numerator (e.g., 3)
    val beatType: Int      // denominator (e.g., 4)
) {
    override fun toString(): String = "$beats/$beatType"

    companion object {
        val TWO_FOUR = TimeSignature(2, 4)
        val THREE_FOUR = TimeSignature(3, 4)
        val FOUR_FOUR = TimeSignature(4, 4)
        val FIVE_FOUR = TimeSignature(5, 4)
        val SIX_EIGHT = TimeSignature(6, 8)
        val THREE_EIGHT = TimeSignature(3, 8)
        val SEVEN_EIGHT = TimeSignature(7, 8)
        val TWO_TWO = TimeSignature(2, 2)

        val SUPPORTED = listOf(
            TWO_FOUR, THREE_FOUR, FOUR_FOUR, FIVE_FOUR,
            SIX_EIGHT, THREE_EIGHT, SEVEN_EIGHT, TWO_TWO
        )
    }
}
