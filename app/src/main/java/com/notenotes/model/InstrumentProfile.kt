package com.notenotes.model

data class InstrumentProfile(
    val name: String,
    val displayName: String,
    val transposeSemitones: Int  // semitones to transpose UP from concert pitch for notation
) {
    companion object {
        val PIANO = InstrumentProfile("piano", "Piano", 0)
        val GUITAR = InstrumentProfile("guitar", "Guitar", 12)  // written octave above sounding
        val BB_TRUMPET = InstrumentProfile("bb_trumpet", "Bb Trumpet", 2)
        val BB_TENOR_SAX = InstrumentProfile("bb_tenor_sax", "Bb Tenor Sax", 14)
        val BB_SOPRANO_SAX = InstrumentProfile("bb_soprano_sax", "Bb Soprano Sax", 2)
        val EB_ALTO_SAX = InstrumentProfile("eb_alto_sax", "Eb Alto Sax", 9)
        val EB_BARITONE_SAX = InstrumentProfile("eb_baritone_sax", "Eb Baritone Sax", 21)
        val BASS_GUITAR = InstrumentProfile("bass_guitar", "Bass Guitar", 12)
        val UKULELE = InstrumentProfile("ukulele", "Ukulele", 0)
        val VOICE = InstrumentProfile("voice", "Voice", 0)

        val ALL = listOf(
            PIANO, GUITAR, BB_TRUMPET, BB_TENOR_SAX, BB_SOPRANO_SAX,
            EB_ALTO_SAX, EB_BARITONE_SAX, BASS_GUITAR, UKULELE, VOICE
        )
    }
}
