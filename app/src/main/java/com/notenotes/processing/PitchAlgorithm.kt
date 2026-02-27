package com.notenotes.processing

/**
 * Available pitch detection algorithms.
 * Each can be selected independently for transcription.
 */
enum class PitchAlgorithm(val displayName: String) {
    YIN("YIN"),
    MPM("McLeod (MPM)"),
    HPS("Harmonic Product Spectrum"),
    CONSENSUS("Consensus (majority vote)");

    companion object {
        val DEFAULT = YIN
    }
}
