package com.notenotes.export

import com.notenotes.model.MusicalNote
import com.notenotes.model.NntTranscription
import org.junit.Assert.*
import org.junit.Test

class NntFileTest {

    @Test
    fun `round-trip preserves all note fields`() {
        val notes = listOf(
            MusicalNote(
                midiPitch = 60,
                durationTicks = 2,
                type = "half",
                dotted = false,
                isRest = false,
                tiedToNext = true,
                velocity = 95,
                chordPitches = listOf(64, 67),
                chordName = "C",
                guitarString = 4,
                guitarFret = 3,
                isManual = true,
                timePositionMs = 1234.5f
            ),
            MusicalNote(
                midiPitch = 60,
                durationTicks = 1,
                type = "quarter",
                isRest = false,
                tiedToNext = false,
                velocity = 95,
                isManual = true,
                timePositionMs = 2500.0f
            ),
            MusicalNote(
                midiPitch = 0,
                durationTicks = 1,
                type = "quarter",
                isRest = true
            )
        )

        val original = NntTranscription(
            instrument = "guitar",
            tempoBpm = 140,
            keySignature = "G major",
            timeSignature = "3/4",
            notes = notes
        )

        val json = NntFile.toJson(original)
        val parsed = NntFile.parse(json)

        assertEquals(original.formatVersion, parsed.formatVersion)
        assertEquals(original.instrument, parsed.instrument)
        assertEquals(original.tempoBpm, parsed.tempoBpm)
        assertEquals(original.keySignature, parsed.keySignature)
        assertEquals(original.timeSignature, parsed.timeSignature)
        assertEquals(original.notes.size, parsed.notes.size)

        // Verify every field on the first note (most complex)
        val n0 = parsed.notes[0]
        assertEquals(60, n0.midiPitch)
        assertEquals(2, n0.durationTicks)
        assertEquals("half", n0.type)
        assertFalse(n0.dotted)
        assertFalse(n0.isRest)
        assertTrue(n0.tiedToNext)
        assertEquals(95, n0.velocity)
        assertEquals(listOf(64, 67), n0.chordPitches)
        assertEquals("C", n0.chordName)
        assertEquals(4, n0.guitarString)
        assertEquals(3, n0.guitarFret)
        assertTrue(n0.isManual)
        assertEquals(1234.5f, n0.timePositionMs!!, 0.001f)
    }

    @Test
    fun `round-trip preserves timePositionMs`() {
        val notes = listOf(
            MusicalNote(midiPitch = 64, durationTicks = 1, type = "quarter",
                timePositionMs = 0.0f, isManual = true),
            MusicalNote(midiPitch = 67, durationTicks = 1, type = "quarter",
                timePositionMs = 500.0f, isManual = true),
            MusicalNote(midiPitch = 72, durationTicks = 1, type = "quarter",
                timePositionMs = 1000.0f)
        )
        val nnt = NntTranscription(notes = notes)
        val parsed = NntFile.parse(NntFile.toJson(nnt))

        assertEquals(0.0f, parsed.notes[0].timePositionMs!!, 0.001f)
        assertEquals(500.0f, parsed.notes[1].timePositionMs!!, 0.001f)
        assertEquals(1000.0f, parsed.notes[2].timePositionMs!!, 0.001f)
    }

    @Test
    fun `fromNotesJson builds transcription from raw JSON`() {
        val gson = com.google.gson.Gson()
        val notes = listOf(
            MusicalNote(midiPitch = 60, durationTicks = 1, type = "quarter", velocity = 80),
            MusicalNote(midiPitch = 62, durationTicks = 1, type = "quarter", velocity = 90)
        )
        val notesJson = gson.toJson(notes)

        val nnt = NntFile.fromNotesJson(
            notesJson = notesJson,
            instrument = "piano",
            tempoBpm = 120,
            keySignature = "C major",
            timeSignature = "4/4"
        )

        assertEquals(2, nnt.notes.size)
        assertEquals(60, nnt.notes[0].midiPitch)
        assertEquals(62, nnt.notes[1].midiPitch)
        assertEquals("piano", nnt.instrument)
        assertEquals(120, nnt.tempoBpm)
        assertEquals("C major", nnt.keySignature)
        assertEquals("4/4", nnt.timeSignature)
    }

    @Test
    fun `file extension is nnt`() {
        assertEquals("nnt", NntTranscription.FILE_EXTENSION)
    }

    @Test
    fun `empty notes list round-trips`() {
        val nnt = NntTranscription(notes = emptyList())
        val parsed = NntFile.parse(NntFile.toJson(nnt))
        assertTrue(parsed.notes.isEmpty())
    }

    @Test
    fun `null metadata round-trips`() {
        val nnt = NntTranscription(
            keySignature = null,
            timeSignature = null,
            notes = listOf(MusicalNote(midiPitch = 60, durationTicks = 1, type = "quarter"))
        )
        val parsed = NntFile.parse(NntFile.toJson(nnt))
        assertNull(parsed.keySignature)
        assertNull(parsed.timeSignature)
        assertEquals(1, parsed.notes.size)
    }
}
