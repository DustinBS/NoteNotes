package com.notenotes.export

import com.notenotes.model.KeySignature
import com.notenotes.model.MusicalNote
import com.notenotes.model.TimeSignature
import com.notenotes.model.TranscriptionResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests MusicXML round-trip: parse → generate → parse should be stable.
 * Uses the "starship og" export as a real-world test case.
 */
class MusicXmlParserTest {

    private lateinit var parser: MusicXmlParser
    private lateinit var generator: MusicXmlGenerator
    private lateinit var starshipXml: String

    @Before
    fun setUp() {
        parser = MusicXmlParser()
        generator = MusicXmlGenerator()
        starshipXml = javaClass.classLoader!!
            .getResourceAsStream("starship_og.musicxml")!!
            .bufferedReader().readText()
    }

    // ── Metadata extraction ──────────────────────────────────────────────

    @Test
    fun parse_extractsInstrument() {
        val result = parser.parse(starshipXml)
        assertEquals("guitar", result.instrument)
    }

    @Test
    fun parse_extractsKeySignature() {
        val result = parser.parse(starshipXml)
        assertEquals("F minor", result.keySignature)
    }

    @Test
    fun parse_extractsTimeSignature() {
        val result = parser.parse(starshipXml)
        assertEquals("4/4", result.timeSignature)
    }

    @Test
    fun parse_extractsTempo() {
        val result = parser.parse(starshipXml)
        assertEquals(120, result.tempoBpm)
    }

    @Test
    fun parse_extractsDivisions() {
        val result = parser.parse(starshipXml)
        assertEquals(4, result.divisions)
    }

    // ── Tie merging ─────────────────────────────────────────────────────

    @Test
    fun parse_mergesTiedNotes_correctNoteCount() {
        val result = parser.parse(starshipXml)
        // Original idea had 11 notes; the XML should parse back to 11 notes
        // (not 32+ from unmerged tie segments)
        assertEquals(
            "Expected 11 notes after tie merging, got ${result.notes.size}",
            11, result.notes.size
        )
    }

    @Test
    fun parse_mergesTiedNotes_correctDurations() {
        val result = parser.parse(starshipXml)
        val expectedDurations = listOf(14, 47, 28, 32, 74, 25, 19, 12, 21, 12, 27)
        val actualDurations = result.notes.map { it.durationTicks }
        assertEquals("Duration ticks should match original", expectedDurations, actualDurations)
    }

    @Test
    fun parse_mergesTiedNotes_correctPitches() {
        val result = parser.parse(starshipXml)
        val expectedPitches = listOf(45, 45, 52, 64, 45, 52, 76, 74, 71, 45, 64)
        val actualPitches = result.notes.map { it.midiPitch }
        assertEquals("MIDI pitches should match original", expectedPitches, actualPitches)
    }

    @Test
    fun parse_mergesTiedNotes_noTiedToNext() {
        val result = parser.parse(starshipXml)
        // After merging, no note should be marked as tiedToNext
        // (ties are internal to XML representation, not musical intent)
        result.notes.forEachIndexed { i, note ->
            assertFalse("Note $i should not have tiedToNext", note.tiedToNext)
        }
    }

    // ── Chord preservation ──────────────────────────────────────────────

    @Test
    fun parse_preservesChords_note1() {
        val result = parser.parse(starshipXml)
        val note1 = result.notes[0]
        // Original: A2 chord with E3(52), A3(57), C4(60)
        assertEquals("Note 1 should have 3 chord pitches", 3, note1.chordPitches.size)
        assertEquals(listOf(52, 57, 60), note1.chordPitches)
    }

    @Test
    fun parse_preservesChords_note3() {
        val result = parser.parse(starshipXml)
        val note3 = result.notes[2]
        // Original: E3 chord with B3(59), E4(64), G4(67)
        assertEquals("Note 3 should have 3 chord pitches", 3, note3.chordPitches.size)
        assertEquals(listOf(59, 64, 67), note3.chordPitches)
    }

    @Test
    fun parse_preservesStringFrets() {
        val result = parser.parse(starshipXml)
        val note1 = result.notes[0]
        // String/fret for chord members (0-based strings in our model)
        // XML has string=4/fret=2, string=3/fret=2, string=2/fret=1
        // After -1 conversion: (3,2), (2,2), (1,1)
        assertEquals(3, note1.chordStringFrets.size)
        assertEquals(Pair(3, 2), note1.chordStringFrets[0])
        assertEquals(Pair(2, 2), note1.chordStringFrets[1])
        assertEquals(Pair(1, 1), note1.chordStringFrets[2])
    }

    @Test
    fun parse_singleNotes_noChords() {
        val result = parser.parse(starshipXml)
        // Notes 2, 4, 7, 8, 9, 11 should have empty chord pitches
        val singleNoteIndices = listOf(1, 3, 6, 7, 8, 10)
        for (idx in singleNoteIndices) {
            assertTrue(
                "Note ${idx + 1} should have no chord pitches, had ${result.notes[idx].chordPitches}",
                result.notes[idx].chordPitches.isEmpty()
            )
        }
    }

    // ── Round-trip stability ────────────────────────────────────────────

    private fun parseResultToTranscription(parsed: MusicXmlParser.ParseResult): TranscriptionResult {
        val keySig = KeySignature.ALL_KEYS.find { it.toString() == parsed.keySignature }
            ?: KeySignature.C_MAJOR
        val timeSig = parsed.timeSignature?.split("/")?.let { parts ->
            if (parts.size == 2) TimeSignature(
                parts[0].toIntOrNull() ?: 4,
                parts[1].toIntOrNull() ?: 4
            ) else null
        } ?: TimeSignature.FOUR_FOUR
        return TranscriptionResult(
            notes = parsed.notes,
            keySignature = keySig,
            timeSignature = timeSig,
            tempoBpm = parsed.tempoBpm ?: 120,
            divisions = parsed.divisions
        )
    }

    @Test
    fun roundTrip_parseGenerateParse_sameNoteCount() {
        val parsed1 = parser.parse(starshipXml)
        val result = parseResultToTranscription(parsed1)
        val generatedXml = generator.generateMusicXml(result)
        val parsed2 = parser.parse(generatedXml, result.tempoBpm)

        assertEquals(
            "Round-trip should preserve note count",
            parsed1.notes.size, parsed2.notes.size
        )
    }

    @Test
    fun roundTrip_parseGenerateParse_samePitches() {
        val parsed1 = parser.parse(starshipXml)
        val result = parseResultToTranscription(parsed1)
        val generatedXml = generator.generateMusicXml(result)
        val parsed2 = parser.parse(generatedXml, result.tempoBpm)

        assertEquals(
            "Round-trip should preserve pitches",
            parsed1.notes.map { it.midiPitch },
            parsed2.notes.map { it.midiPitch }
        )
    }

    @Test
    fun roundTrip_parseGenerateParse_sameDurations() {
        val parsed1 = parser.parse(starshipXml)
        val result = parseResultToTranscription(parsed1)
        val generatedXml = generator.generateMusicXml(result)
        val parsed2 = parser.parse(generatedXml, result.tempoBpm)

        assertEquals(
            "Round-trip should preserve durations",
            parsed1.notes.map { it.durationTicks },
            parsed2.notes.map { it.durationTicks }
        )
    }

    @Test
    fun roundTrip_parseGenerateParse_sameChords() {
        val parsed1 = parser.parse(starshipXml)
        val result = parseResultToTranscription(parsed1)
        val generatedXml = generator.generateMusicXml(result)
        val parsed2 = parser.parse(generatedXml, result.tempoBpm)

        for (i in parsed1.notes.indices) {
            assertEquals(
                "Note $i chord pitches should match after round-trip",
                parsed1.notes[i].chordPitches,
                parsed2.notes[i].chordPitches
            )
        }
    }

    // ── Simple synthetic tests ──────────────────────────────────────────

    @Test
    fun parse_simpleTiedHalf_mergesIntoWhole() {
        // Two half notes (8 ticks each at div=4) tied together = 1 whole (16 ticks)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Test</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>4</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                  </attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>8</duration><tie type="start"/><voice>1</voice><type>half</type>
                    <notations><tied type="start"/></notations>
                  </note>
                </measure>
                <measure number="2">
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>8</duration><tie type="stop"/><voice>1</voice><type>half</type>
                    <notations><tied type="stop"/></notations>
                  </note>
                </measure>
              </part>
            </score-partwise>"""

        val result = parser.parse(xml)
        assertEquals("Should merge to 1 note", 1, result.notes.size)
        assertEquals("Duration should be 16 ticks", 16, result.notes[0].durationTicks)
        assertEquals("Pitch should be C4 (60)", 60, result.notes[0].midiPitch)
        assertFalse("Should not be tied", result.notes[0].tiedToNext)
    }

    @Test
    fun parse_threeWayTieChain_mergesIntoOne() {
        // C4 quarter → C4 half (relay) → C4 quarter (stop) = 16 ticks total
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Test</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>4</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                  </attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>4</duration><tie type="start"/><voice>1</voice><type>quarter</type>
                    <notations><tied type="start"/></notations>
                  </note>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>8</duration><tie type="stop"/><tie type="start"/><voice>1</voice><type>half</type>
                    <notations><tied type="stop"/><tied type="start"/></notations>
                  </note>
                </measure>
                <measure number="2">
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>4</duration><tie type="stop"/><voice>1</voice><type>quarter</type>
                    <notations><tied type="stop"/></notations>
                  </note>
                </measure>
              </part>
            </score-partwise>"""

        val result = parser.parse(xml)
        assertEquals("Should merge to 1 note", 1, result.notes.size)
        assertEquals("Duration should be 16 ticks (4+8+4)", 16, result.notes[0].durationTicks)
    }

    @Test
    fun parse_tiedChord_mergesCorrectly() {
        // A2+E3 chord, tied across measures
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Test</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>4</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                  </attributes>
                  <note>
                    <pitch><step>A</step><octave>2</octave></pitch>
                    <duration>8</duration><tie type="start"/><voice>1</voice><type>half</type>
                    <notations><tied type="start"/><technical><string>5</string><fret>0</fret></technical></notations>
                  </note>
                  <note>
                    <chord/>
                    <pitch><step>E</step><octave>3</octave></pitch>
                    <duration>8</duration><tie type="start"/><voice>1</voice><type>half</type>
                    <notations><tied type="start"/><technical><string>4</string><fret>2</fret></technical></notations>
                  </note>
                </measure>
                <measure number="2">
                  <note>
                    <pitch><step>A</step><octave>2</octave></pitch>
                    <duration>8</duration><tie type="stop"/><voice>1</voice><type>half</type>
                    <notations><tied type="stop"/><technical><string>5</string><fret>0</fret></technical></notations>
                  </note>
                  <note>
                    <chord/>
                    <pitch><step>E</step><octave>3</octave></pitch>
                    <duration>8</duration><tie type="stop"/><voice>1</voice><type>half</type>
                    <notations><tied type="stop"/><technical><string>4</string><fret>2</fret></technical></notations>
                  </note>
                </measure>
              </part>
            </score-partwise>"""

        val result = parser.parse(xml)
        assertEquals("Should be 1 merged note", 1, result.notes.size)
        assertEquals("Duration should be 16", 16, result.notes[0].durationTicks)
        assertEquals("Primary pitch is A2=45", 45, result.notes[0].midiPitch)
        assertEquals("Chord should have E3=52", listOf(52), result.notes[0].chordPitches)
    }

    @Test
    fun parse_consecutiveSamePitch_notMerged() {
        // Two separate C4 quarters (no ties) should remain as 2 notes
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Test</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>4</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                  </attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>4</duration><voice>1</voice><type>quarter</type>
                  </note>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>4</duration><voice>1</voice><type>quarter</type>
                  </note>
                </measure>
              </part>
            </score-partwise>"""

        val result = parser.parse(xml)
        assertEquals("Should be 2 separate notes", 2, result.notes.size)
    }

    // ── Fifths-to-key conversion ────────────────────────────────────────

    @Test
    fun fifthsToKeyName_majorKeys() {
        assertEquals("C major", MusicXmlParser.fifthsToKeyName(0, "major"))
        assertEquals("G major", MusicXmlParser.fifthsToKeyName(1, "major"))
        assertEquals("F major", MusicXmlParser.fifthsToKeyName(-1, "major"))
        assertEquals("D major", MusicXmlParser.fifthsToKeyName(2, "major"))
    }

    @Test
    fun fifthsToKeyName_minorKeys() {
        assertEquals("A minor", MusicXmlParser.fifthsToKeyName(0, "minor"))
        assertEquals("F minor", MusicXmlParser.fifthsToKeyName(-4, "minor"))
        assertEquals("E minor", MusicXmlParser.fifthsToKeyName(1, "minor"))
    }
}
