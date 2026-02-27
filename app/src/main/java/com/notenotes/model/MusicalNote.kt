package com.notenotes.model

data class MusicalNote(
    val midiPitch: Int,          // 0-127
    val durationTicks: Int,      // in divisions (e.g., quarter = 1 division)
    val type: String,            // "whole", "half", "quarter", "eighth", "16th"
    val dotted: Boolean = false,
    val isRest: Boolean = false,
    val tiedToNext: Boolean = false,
    val velocity: Int = 80       // 0-127, default mezzo-forte
)
