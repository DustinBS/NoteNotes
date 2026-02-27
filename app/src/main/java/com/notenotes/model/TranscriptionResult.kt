package com.notenotes.model

data class TranscriptionResult(
    val notes: List<MusicalNote>,
    val keySignature: KeySignature,
    val timeSignature: TimeSignature,
    val tempoBpm: Int,
    val divisions: Int = 4,  // divisions per quarter note for MusicXML
    val instrument: InstrumentProfile? = null
)
