package com.notenotes.export

import com.notenotes.model.KeySignature
import com.notenotes.model.MusicalNote
import com.notenotes.model.TimeSignature
import com.notenotes.model.TranscriptionResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Extended MusicXML round-trip tests.
 *
 * Tests the full cycle: MusicalNote list → MusicXmlGenerator → XML string
 * → MusicXmlParser → MusicalNote list, verifying that pitches, durations,
 * chords, and metadata survive the round-trip.
 *
 * Uses the same "starship og" data as the NNT file format for consistency.
 */
class MusicXmlRoundTripTest {

    private lateinit var parser: MusicXmlParser
    private lateinit var generator: MusicXmlGenerator

    // The starship og notes — the exact MusicalNote objects (manual notes with chords)
    private val starshipNotes = listOf(
        MusicalNote(
            midiPitch = 45, durationTicks = 14, type = "half", isManual = true,
            timePositionMs = 2214.3555f, velocity = 80,
            chordPitches = listOf(52, 57, 60),
            chordStringFrets = listOf(Pair(2, 2), Pair(3, 2), Pair(4, 1)),
            chordName = "Chord",
            guitarString = 1, guitarFret = 0
        ),
        MusicalNote(
            midiPitch = 45, durationTicks = 47, type = "whole", isManual = true,
            timePositionMs = 4053.955f, velocity = 80,
            guitarString = 1, guitarFret = 0
        ),
        MusicalNote(
            midiPitch = 52, durationTicks = 28, type = "whole", isManual = true,
            timePositionMs = 10052.61f, velocity = 80,
            chordPitches = listOf(59, 64, 67),
            chordStringFrets = listOf(Pair(3, 4), Pair(4, 5), Pair(5, 3)),
            chordName = "Chord",
            guitarString = 2, guitarFret = 2
        ),
        MusicalNote(
            midiPitch = 64, durationTicks = 32, type = "whole", isManual = true,
            timePositionMs = 13616.391f, velocity = 80,
            guitarString = 5, guitarFret = 0
        ),
        MusicalNote(
            midiPitch = 45, durationTicks = 74, type = "whole", isManual = true,
            timePositionMs = 17719.193f, velocity = 80,
            chordPitches = listOf(52, 57, 60),
            chordStringFrets = listOf(Pair(2, 2), Pair(3, 2), Pair(4, 1)),
            chordName = "Chord",
            guitarString = 1, guitarFret = 0
        ),
        MusicalNote(
            midiPitch = 52, durationTicks = 25, type = "whole", isManual = true,
            timePositionMs = 27001.225f, velocity = 80,
            chordPitches = listOf(59, 64, 67),
            chordStringFrets = listOf(Pair(3, 4), Pair(4, 5), Pair(5, 3)),
            chordName = "Chord",
            guitarString = 2, guitarFret = 2
        ),
        MusicalNote(
            midiPitch = 76, durationTicks = 19, type = "whole", isManual = true,
            timePositionMs = 30183.422f, velocity = 80,
            guitarString = 5, guitarFret = 12
        ),
        MusicalNote(
            midiPitch = 74, durationTicks = 12, type = "half", isManual = true,
            timePositionMs = 32652.836f, velocity = 80,
            guitarString = 5, guitarFret = 10
        ),
        MusicalNote(
            midiPitch = 71, durationTicks = 21, type = "whole", isManual = true,
            timePositionMs = 34233.645f, velocity = 80,
            guitarString = 5, guitarFret = 7
        ),
        MusicalNote(
            midiPitch = 45, durationTicks = 12, type = "half", isManual = true,
            timePositionMs = 36936.562f, velocity = 80,
            chordPitches = listOf(52, 57, 60),
            chordStringFrets = listOf(Pair(2, 2), Pair(3, 2), Pair(4, 1)),
            chordName = "Chord",
            guitarString = 1, guitarFret = 0
        ),
        MusicalNote(
            midiPitch = 64, durationTicks = 27, type = "whole", isManual = true,
            timePositionMs = 38436.805f, velocity = 80,
            guitarString = 5, guitarFret = 0
        )
    )

    @Before
    fun setUp() {
        parser = MusicXmlParser()
        generator = MusicXmlGenerator()
    }

    private fun buildResult(notes: List<MusicalNote>, bpm: Int = 120): TranscriptionResult {
        return TranscriptionResult(
            notes = notes,
            keySignature = KeySignature.ALL_KEYS.find { it.toString() == "F minor" }
                ?: KeySignature.C_MAJOR,
            timeSignature = TimeSignature(4, 4),
            tempoBpm = bpm
        )
    }

    // ── Generate → Parse round-trip ─────────────────────────────────────

    @Test
    fun roundTrip_starship_noteCount() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals(
            "Round-trip should preserve 11 notes",
            11, parsed.notes.size
        )
    }

    @Test
    fun roundTrip_starship_pitches() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        val expectedPitches = starshipNotes.map { it.midiPitch }
        val actualPitches = parsed.notes.map { it.midiPitch }
        assertEquals("Pitches should survive round-trip", expectedPitches, actualPitches)
    }

    @Test
    fun roundTrip_starship_durations() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        val expectedDurations = starshipNotes.map { it.durationTicks }
        val actualDurations = parsed.notes.map { it.durationTicks }
        assertEquals("Durations should survive round-trip", expectedDurations, actualDurations)
    }

    @Test
    fun roundTrip_starship_chordPitches() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        for (i in starshipNotes.indices) {
            assertEquals(
                "Chord pitches for note $i should survive round-trip",
                starshipNotes[i].chordPitches,
                parsed.notes[i].chordPitches
            )
        }
    }

    @Test
    fun roundTrip_starship_chordStringFrets() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        for (i in starshipNotes.indices) {
            assertEquals(
                "Chord string/frets for note $i should survive round-trip",
                starshipNotes[i].chordStringFrets,
                parsed.notes[i].chordStringFrets
            )
        }
    }

    @Test
    fun roundTrip_starship_guitarStringFret() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        for (i in starshipNotes.indices) {
            assertEquals(
                "Guitar string for note $i",
                starshipNotes[i].guitarString, parsed.notes[i].guitarString
            )
            assertEquals(
                "Guitar fret for note $i",
                starshipNotes[i].guitarFret, parsed.notes[i].guitarFret
            )
        }
    }

    // ── Metadata round-trip ─────────────────────────────────────────────

    @Test
    fun roundTrip_starship_tempo() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals("Tempo should survive round-trip", 120, parsed.tempoBpm)
    }

    @Test
    fun roundTrip_starship_keySignature() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals("Key signature should survive round-trip", "F minor", parsed.keySignature)
    }

    @Test
    fun roundTrip_starship_timeSignature() {
        val result = buildResult(starshipNotes)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals("Time signature should survive round-trip", "4/4", parsed.timeSignature)
    }

    // ── Sanitized XML round-trip ────────────────────────────────────────

    @Test
    fun roundTrip_starship_sanitized_pitches() {
        val result = buildResult(starshipNotes)
        val rawXml = generator.generateMusicXml(result)
        val xml = MusicXmlSanitizer.sanitize(rawXml)
        val parsed = parser.parse(xml, 120)
        val expectedPitches = starshipNotes.map { it.midiPitch }
        val actualPitches = parsed.notes.map { it.midiPitch }
        assertEquals("Pitches should survive sanitized round-trip", expectedPitches, actualPitches)
    }

    @Test
    fun roundTrip_starship_sanitized_durations() {
        val result = buildResult(starshipNotes)
        val rawXml = generator.generateMusicXml(result)
        val xml = MusicXmlSanitizer.sanitize(rawXml)
        val parsed = parser.parse(xml, 120)
        val expectedDurations = starshipNotes.map { it.durationTicks }
        val actualDurations = parsed.notes.map { it.durationTicks }
        assertEquals("Durations should survive sanitized round-trip", expectedDurations, actualDurations)
    }

    @Test
    fun roundTrip_starship_sanitized_noteCount() {
        val result = buildResult(starshipNotes)
        val rawXml = generator.generateMusicXml(result)
        val xml = MusicXmlSanitizer.sanitize(rawXml)
        val parsed = parser.parse(xml, 120)
        assertEquals("Note count should survive sanitized round-trip", 11, parsed.notes.size)
    }

    @Test
    fun roundTrip_starship_sanitized_chords() {
        val result = buildResult(starshipNotes)
        val rawXml = generator.generateMusicXml(result)
        val xml = MusicXmlSanitizer.sanitize(rawXml)
        val parsed = parser.parse(xml, 120)
        for (i in starshipNotes.indices) {
            assertEquals(
                "Chord pitches for note $i should survive sanitized round-trip",
                starshipNotes[i].chordPitches,
                parsed.notes[i].chordPitches
            )
        }
    }

    // ── Double round-trip stability ─────────────────────────────────────

    @Test
    fun doubleRoundTrip_starship_stable() {
        val result1 = buildResult(starshipNotes)
        val xml1 = generator.generateMusicXml(result1)
        val parsed1 = parser.parse(xml1, 120)

        val result2 = buildResult(parsed1.notes)
        val xml2 = generator.generateMusicXml(result2)
        val parsed2 = parser.parse(xml2, 120)

        assertEquals("Note count stable across 2 round-trips", parsed1.notes.size, parsed2.notes.size)
        assertEquals(
            "Pitches stable across 2 round-trips",
            parsed1.notes.map { it.midiPitch },
            parsed2.notes.map { it.midiPitch }
        )
        assertEquals(
            "Durations stable across 2 round-trips",
            parsed1.notes.map { it.durationTicks },
            parsed2.notes.map { it.durationTicks }
        )
    }

    // ── BPM change + round-trip ─────────────────────────────────────────

    @Test
    fun roundTrip_starship_at60bpm_pitchesPreserved() {
        // Re-quantize to 60 BPM, generate XML, parse back
        val reQuantized = starshipNotes.map { note ->
            val newTicks = Math.round(note.durationTicks.toDouble() * 60 / 120).toInt()
                .coerceAtLeast(1)
            note.copy(durationTicks = newTicks)
        }
        val result = buildResult(reQuantized, 60)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 60)

        val expectedPitches = starshipNotes.map { it.midiPitch }
        val actualPitches = parsed.notes.map { it.midiPitch }
        assertEquals("Pitches preserved at 60 BPM", expectedPitches, actualPitches)
    }

    @Test
    fun roundTrip_starship_at60bpm_noteCount() {
        val reQuantized = starshipNotes.map { note ->
            val newTicks = Math.round(note.durationTicks.toDouble() * 60 / 120).toInt()
                .coerceAtLeast(1)
            note.copy(durationTicks = newTicks)
        }
        val result = buildResult(reQuantized, 60)
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 60)
        assertEquals("Note count preserved at 60 BPM", 11, parsed.notes.size)
    }

    // ── Simple synthetic notes ──────────────────────────────────────────

    @Test
    fun roundTrip_simpleWholeNote_preserved() {
        val notes = listOf(
            MusicalNote(midiPitch = 60, durationTicks = 16, type = "whole")
        )
        val result = TranscriptionResult(
            notes = notes, tempoBpm = 120,
            keySignature = KeySignature.C_MAJOR, timeSignature = TimeSignature(4, 4)
        )
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals(1, parsed.notes.size)
        assertEquals(60, parsed.notes[0].midiPitch)
        assertEquals(16, parsed.notes[0].durationTicks)
    }

    @Test
    fun roundTrip_differentPitches_allPreserved() {
        val pitches = listOf(40, 45, 52, 60, 64, 67, 71, 74, 76, 84)
        val notes = pitches.map { p ->
            MusicalNote(midiPitch = p, durationTicks = 4, type = "quarter")
        }
        val result = TranscriptionResult(
            notes = notes, tempoBpm = 120,
            keySignature = KeySignature.C_MAJOR, timeSignature = TimeSignature(4, 4)
        )
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals(pitches.size, parsed.notes.size)
        assertEquals(pitches, parsed.notes.map { it.midiPitch })
    }

    @Test
    fun roundTrip_nonStandardDuration_preserved() {
        // 14 ticks is non-standard (not 4, 8, 12, 16) — gets decomposed into ties
        val notes = listOf(
            MusicalNote(midiPitch = 60, durationTicks = 14, type = "half")
        )
        val result = TranscriptionResult(
            notes = notes, tempoBpm = 120,
            keySignature = KeySignature.C_MAJOR, timeSignature = TimeSignature(4, 4)
        )
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals(1, parsed.notes.size)
        assertEquals(60, parsed.notes[0].midiPitch)
        assertEquals(14, parsed.notes[0].durationTicks)
    }

    @Test
    fun roundTrip_47ticks_preserved() {
        // 47 ticks — highly non-standard, gets decomposed into 16+16+12+2+1
        val notes = listOf(
            MusicalNote(midiPitch = 45, durationTicks = 47, type = "whole")
        )
        val result = TranscriptionResult(
            notes = notes, tempoBpm = 120,
            keySignature = KeySignature.C_MAJOR, timeSignature = TimeSignature(4, 4)
        )
        val xml = generator.generateMusicXml(result)
        val parsed = parser.parse(xml, 120)
        assertEquals(1, parsed.notes.size)
        assertEquals(45, parsed.notes[0].midiPitch)
        assertEquals(47, parsed.notes[0].durationTicks)
    }
}
