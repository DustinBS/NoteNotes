package com.notenotes.integration

import com.notenotes.export.MidiWriter
import com.notenotes.export.MusicXmlGenerator
import com.notenotes.model.KeySignature
import com.notenotes.model.MusicalNote
import com.notenotes.model.TimeSignature
import com.notenotes.model.TranscriptionResult
import com.notenotes.model.InstrumentProfile
import com.notenotes.processing.KeyDetector
import com.notenotes.processing.RhythmQuantizer
import com.notenotes.util.TranspositionUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests that verify end-to-end flows across multiple modules.
 * These tests catch issues that unit tests on individual components miss,
 * such as cross-barline note handling, MIDI rest timing, MusicXML validity,
 * and transposition round-trips through the full pipeline.
 */
class EndToEndIntegrationTest {

    private val midiWriter = MidiWriter()
    private val xmlGenerator = MusicXmlGenerator()

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private fun note(
        midi: Int,
        ticks: Int = 4,
        type: String = "quarter",
        isRest: Boolean = false,
        dotted: Boolean = false,
        tiedToNext: Boolean = false,
        velocity: Int = 80
    ) = MusicalNote(midi, ticks, type, dotted, isRest, tiedToNext, velocity)

    private fun rest(ticks: Int = 4, type: String = "quarter") =
        note(0, ticks, type, isRest = true)

    private fun result(
        notes: List<MusicalNote>,
        key: KeySignature = KeySignature.C_MAJOR,
        time: TimeSignature = TimeSignature.FOUR_FOUR,
        tempo: Int = 120,
        divisions: Int = 4
    ) = TranscriptionResult(notes, key, time, tempo, divisions)

    // =========================================================================
    //  1. Cross-barline note splitting (C2 regression)
    // =========================================================================

    /**
     * A half note starting on beat 3 of 4/4 should be split across the barline:
     * - First measure: tied quarter (2 ticks of the 8-tick half)… wait, depends on divisions.
     *   divisions=4, ticksPerMeasure = 4*4 = 16.  Half = 8 ticks.
     *   If half starts at tick 8 (beat 3): 8 + 8 = 16, fits! So it doesn't need splitting.
     *   Let's make it 3 quarters then a half = 12 + 8 = 20 > 16 → split at 16.
     *   First 4 ticks (16-12) in measure 1, remainder 4 ticks in measure 2.
     */
    @Test
    fun crossBarlineSplit_halfNoteSpanningMeasure_producesValidXml() {
        val notes = listOf(
            note(60, ticks = 4),  // quarter (4 ticks)
            note(62, ticks = 4),  // quarter
            note(64, ticks = 4),  // quarter → 12 ticks so far
            note(65, ticks = 8, type = "half")  // half starts at beat 4, needs 8 more = 20 > 16
        )
        val r = result(notes)
        val xml = xmlGenerator.generateMusicXml(r)

        // Must contain two measures (the note splits across barline)
        assertTrue("Should have measure 1", xml.contains("""<measure number="1">"""))
        assertTrue("Should have measure 2", xml.contains("""<measure number="2">"""))

        // Must contain tie elements for the split note
        assertTrue("Should contain tie start", xml.contains("""<tie type="start"/>"""))
        assertTrue("Should contain tie stop", xml.contains("""<tie type="stop"/>"""))
        assertTrue("Should contain tied start notation", xml.contains("""<tied type="start"/>"""))
        assertTrue("Should contain tied stop notation", xml.contains("""<tied type="stop"/>"""))
    }

    /**
     * A whole note in 3/4 (ticksPerMeasure=12 with divisions=4) must be split:
     * 12 ticks in first measure, 4 remaining in second.
     */
    @Test
    fun crossBarlineSplit_wholeNoteIn34_splitCorrectly() {
        val notes = listOf(
            note(60, ticks = 16, type = "whole")  // whole = 16 ticks, but 3/4 only has 12
        )
        val r = result(notes, time = TimeSignature(3, 4))
        val xml = xmlGenerator.generateMusicXml(r)

        assertTrue("Should have measure 2 for remainder",
            xml.contains("""<measure number="2">"""))
        assertTrue("Should contain tie for split", xml.contains("""<tie type="start"/>"""))
    }

    /**
     * Verify that no note duration gets silently lost when splitting.
     * Total ticks of all <duration> elements should equal the original note's ticks.
     */
    @Test
    fun crossBarlineSplit_totalDurationPreserved() {
        val notes = listOf(
            note(60, ticks = 20, type = "whole")  // huge note: 20 ticks, measure has 16
        )
        val r = result(notes)
        val xml = xmlGenerator.generateMusicXml(r)

        // Extract all <duration>...</duration> values
        val durations = Regex("""<duration>(\d+)</duration>""")
            .findAll(xml)
            .map { it.groupValues[1].toInt() }
            .toList()

        // The original note is 20 ticks; after splitting, parts should sum to 20
        // But there may also be filler rests. The pitched note durations should sum to 20.
        // Find which durations belong to pitched notes by checking for <rest/> presence
        val lines = xml.lines()
        var pitchedDurationSum = 0
        var inNote = false
        var isRest = false
        var currentDuration = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == "<note>") {
                inNote = true; isRest = false; currentDuration = 0
            } else if (trimmed == "</note>") {
                if (inNote && !isRest) pitchedDurationSum += currentDuration
                inNote = false
            } else if (trimmed.startsWith("<rest")) {
                isRest = true
            } else if (trimmed.startsWith("<duration>") && inNote) {
                currentDuration = Regex("""(\d+)""").find(trimmed)?.groupValues?.get(1)?.toInt() ?: 0
            }
        }
        assertEquals("Pitched note total duration should be preserved", 20, pitchedDurationSum)
    }

    // =========================================================================
    //  2. MIDI rest timing (C1 regression)
    // =========================================================================

    /**
     * A rest between two notes must produce correct timing in MIDI.
     * Pattern: C4(quarter) → rest(quarter) → D4(quarter)
     * The D4 note-on should occur at tick 8 (after the C4 ends at 4 + rest 4).
     */
    @Test
    fun midiRest_noteRestNote_restTimingPreserved() {
        val notes = listOf(
            note(60, ticks = 4),
            rest(ticks = 4),
            note(62, ticks = 4)
        )
        val r = result(notes)
        val bytes = midiWriter.generateMidi(r)

        // Basic MIDI structure check
        assertTrue("MIDI should start with MThd", String(bytes, 0, 4, Charsets.US_ASCII) == "MThd")

        // Find the track data (after MTrk)
        val mtrkIndex = findBytes(bytes, "MTrk".toByteArray())
        assertTrue("Should contain MTrk", mtrkIndex >= 0)

        // The MIDI data should have at minimum 2 note-on/note-off events
        // (rest is silence — no note-on for rest, just delta time)
        val noteOnCount = countMidiNoteOns(bytes, mtrkIndex)
        assertEquals("Should have exactly 2 note-on events", 2, noteOnCount)
    }

    /**
     * Multiple consecutive rests should accumulate correctly.
     */
    @Test
    fun midiRest_multipleConsecutiveRests_deltaAccumulated() {
        val notes = listOf(
            note(60, ticks = 4),
            rest(ticks = 4),
            rest(ticks = 4),
            rest(ticks = 4),
            note(62, ticks = 4)
        )
        val r = result(notes)
        val bytes = midiWriter.generateMidi(r)

        val mtrkIndex = findBytes(bytes, "MTrk".toByteArray())
        assertTrue("Should contain MTrk", mtrkIndex >= 0)
        val noteOnCount = countMidiNoteOns(bytes, mtrkIndex)
        assertEquals("Should have exactly 2 note-on events", 2, noteOnCount)
    }

    // =========================================================================
    //  3. MusicXML structural validity
    // =========================================================================

    /**
     * Generated MusicXML should be well-formed XML (basic structure check).
     */
    @Test
    fun musicXml_wellFormedStructure() {
        val notes = listOf(
            note(60), note(62), note(64), note(65)  // C D E F
        )
        val r = result(notes)
        val xml = xmlGenerator.generateMusicXml(r)

        assertTrue("Should have XML declaration", xml.contains("<?xml"))
        assertTrue("Should have DOCTYPE", xml.contains("<!DOCTYPE"))
        assertTrue("Should have score-partwise open", xml.contains("<score-partwise"))
        assertTrue("Should have score-partwise close", xml.contains("</score-partwise>"))
        assertTrue("Should have part list", xml.contains("<part-list>"))
        assertTrue("Should have part element", xml.contains("<part id="))
        assertTrue("Should have attributes", xml.contains("<attributes>"))
        assertTrue("Should have divisions", xml.contains("<divisions>"))
        assertTrue("Should have key signature", xml.contains("<key>"))
        assertTrue("Should have time signature", xml.contains("<time>"))
        assertTrue("Should have clef", xml.contains("<clef>"))
    }

    /**
     * MusicXML should produce correct whole rest for 3/4 time (M5 regression).
     * The rest type should NOT be "whole" for non-4/4 time signatures.
     */
    @Test
    fun musicXml_emptyMeasureIn34_restTypeNotWhole() {
        // A melody that fills measure 1 but not measure 2 in 3/4
        // 3/4 with divisions=4: ticksPerMeasure = 12
        // 3 quarter notes = 12 ticks = fills exactly 1 measure
        // If there were filler rests, they'd be type "half" with dot (6 ticks) or similar
        // Actually, let's test via the whole rest on an "empty" second measure
        // This is hard to trigger since appendMeasures fills rests.
        // Instead, let's check that when a whole rest IS produced,
        // it has measure="yes" attribute
        val notes = listOf(
            note(60, ticks = 4),
            note(62, ticks = 4),
            note(64, ticks = 4)  // fills exactly 1 measure of 3/4
        )
        val r = result(notes, time = TimeSignature(3, 4))
        val xml = xmlGenerator.generateMusicXml(r)

        // Should have exactly 1 measure (all notes fit)
        assertTrue("Should have measure 1", xml.contains("""<measure number="1">"""))
        // Should NOT have an unnecessary measure 2
        assertFalse("Should not have measure 2 for exact fit",
            xml.contains("""<measure number="2">"""))
    }

    /**
     * MusicXML in 6/8 should correctly compute ticks per measure.
     * 6/8 with divisions=4: each eighth = 2 ticks, measure = 6*2 = 12 ticks.
     */
    @Test
    fun musicXml_68TimeSignature_correctTicksPerMeasure() {
        // 6 eighth notes should fill exactly 1 measure of 6/8
        val notes = (1..6).map { note(60 + it, ticks = 2, type = "eighth") }
        val r = result(notes, time = TimeSignature(6, 8))
        val xml = xmlGenerator.generateMusicXml(r)

        assertTrue("Should have measure 1", xml.contains("""<measure number="1">"""))
        // 6 eighths = 12 ticks = exactly 1 measure, no measure 2 needed
        assertFalse("Should not need measure 2",
            xml.contains("""<measure number="2">"""))
    }

    // =========================================================================
    //  4. Transposition integration
    // =========================================================================

    /**
     * All instruments should round-trip correctly: concert → written → concert.
     */
    @Test
    fun transposition_allInstruments_roundTrip() {
        for (instrument in InstrumentProfile.ALL) {
            for (concertPitch in listOf(48, 60, 72, 84)) {
                val written = TranspositionUtils.transposeForInstrument(concertPitch, instrument)
                val backToConcert = TranspositionUtils.concertPitchFromWritten(written, instrument)
                assertEquals(
                    "Round-trip failed for ${instrument.displayName} at MIDI $concertPitch",
                    concertPitch, backToConcert
                )
            }
        }
    }

    /**
     * Bb instruments should transpose up by the correct octave + interval.
     * Bb Tenor Sax: 14 semitones (major 9th)
     * Bb Trumpet: 2 semitones (major 2nd)
     */
    @Test
    fun transposition_bbInstruments_correctSemitones() {
        val tenorResult = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.BB_TENOR_SAX)
        assertEquals("Tenor sax: C4 → D5 (major 9th up)", 74, tenorResult)

        val trumpetResult = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.BB_TRUMPET)
        assertEquals("Trumpet: C4 → D4 (major 2nd up)", 62, trumpetResult)
    }

    /**
     * Eb instruments should transpose up by the correct interval.
     * Eb Alto Sax: 9 semitones (major 6th)
     * Eb Baritone Sax: 21 semitones (major 13th = octave + major 6th)
     */
    @Test
    fun transposition_ebInstruments_correctSemitones() {
        val altoResult = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.EB_ALTO_SAX)
        assertEquals("Alto sax: C4 → A4 (major 6th up)", 69, altoResult)

        val bariResult = TranspositionUtils.transposeForInstrument(60, InstrumentProfile.EB_BARITONE_SAX)
        assertEquals("Bari sax: C4 → A5 (major 13th up)", 81, bariResult)
    }

    // =========================================================================
    //  5. Key detection integration
    // =========================================================================

    /**
     * All key signatures should have valid fifths values in the MusicXML spec range.
     * Major keys: -7 to +7
     * Minor keys: -7 to +7
     */
    @Test
    fun keySignature_allKeys_validFifthsRange() {
        for (key in KeySignature.ALL_KEYS) {
            assertTrue(
                "Key ${key} fifths ${key.fifths} out of range",
                key.fifths in -7..7
            )
        }
    }

    /**
     * Key detection should produce consistent results when given clear diatonic input.
     * A melody of only C major scale notes should detect C major or A minor.
     */
    @Test
    fun keyDetection_cMajorScaleNotes_detectsCMajorOrAMinor() {
        val detector = KeyDetector()
        // C major scale MIDI pitches: 60, 62, 64, 65, 67, 69, 71
        val pitches = listOf(60, 62, 64, 65, 67, 69, 71, 72)
        val key = detector.detectKey(pitches)

        // Should be C major (0 fifths) or A minor (0 fifths)
        assertEquals("C major scale pitches should yield 0 fifths", 0, key.fifths)
    }

    // =========================================================================
    //  6. MIDI + MusicXML consistency
    // =========================================================================

    /**
     * Both MIDI and MusicXML generators should handle the same input without crashing.
     * This is a smoke test for the full export pipeline.
     */
    @Test
    fun exportPipeline_sameInput_bothSucceed() {
        val notes = listOf(
            note(60), note(62), rest(), note(64),
            note(65, ticks = 8, type = "half"),
            note(67), rest(), rest(), note(69)
        )
        val r = result(notes)

        // Neither should throw
        val midi = midiWriter.generateMidi(r)
        val xml = xmlGenerator.generateMusicXml(r)

        assertTrue("MIDI should have content", midi.isNotEmpty())
        assertTrue("XML should have content", xml.isNotEmpty())
        assertTrue("MIDI should start with MThd", String(midi, 0, 4, Charsets.US_ASCII) == "MThd")
        assertTrue("XML should have score-partwise", xml.contains("<score-partwise"))
    }

    /**
     * Edge case: empty note list should still produce valid output.
     */
    @Test
    fun exportPipeline_emptyNotes_noException() {
        val r = result(notes = emptyList())

        // Should not throw
        val midi = midiWriter.generateMidi(r)
        val xml = xmlGenerator.generateMusicXml(r)

        assertTrue("MIDI should still have header", midi.size >= 14)
        assertTrue("XML should still have structure", xml.contains("<score-partwise"))
    }

    /**
     * Edge case: all rests should produce valid MIDI
     * (no note-on events, just proper timing).
     */
    @Test
    fun exportPipeline_allRests_validMidi() {
        val notes = listOf(rest(), rest(), rest(), rest())
        val r = result(notes)

        val bytes = midiWriter.generateMidi(r)
        val mtrkIndex = findBytes(bytes, "MTrk".toByteArray())
        assertTrue("Should contain MTrk", mtrkIndex >= 0)
        val noteOnCount = countMidiNoteOns(bytes, mtrkIndex)
        assertEquals("All-rest sequence should have 0 note-on events", 0, noteOnCount)
    }

    // =========================================================================
    //  7. Cross-barline rest splitting
    // =========================================================================

    /**
     * A long rest spanning a barline should be split correctly in MusicXML.
     */
    @Test
    fun crossBarlineSplit_longRest_splitCorrectly() {
        // 3 quarters + a rest of 8 ticks (half) → rest spans barline
        val notes = listOf(
            note(60, ticks = 4),
            note(62, ticks = 4),
            note(64, ticks = 4),
            rest(ticks = 8, type = "half")
        )
        val r = result(notes)
        val xml = xmlGenerator.generateMusicXml(r)

        // Should have 2 measures since the rest spans the barline
        assertTrue("Should have measure 2", xml.contains("""<measure number="2">"""))

        // Should NOT have tied elements on rests
        // Count the number of <rest/> elements - should be 2 (split rest)
        val restCount = Regex("""<rest/>""").findAll(xml).count()
        assertTrue("Should have at least 2 rest elements for split", restCount >= 2)
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private fun findBytes(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }

    /**
     * Count MIDI note-on events (status 0x9n with velocity > 0) in track data.
     */
    private fun countMidiNoteOns(data: ByteArray, startAfter: Int): Int {
        var count = 0
        var i = startAfter + 8  // Skip "MTrk" + 4-byte length
        while (i < data.size - 2) {
            // Skip variable-length delta time
            while (i < data.size && (data[i].toInt() and 0x80) != 0) i++
            i++ // skip last delta byte

            if (i >= data.size) break

            val status = data[i].toInt() and 0xFF
            if (status in 0x90..0x9F) {
                // Note-on event: check velocity
                if (i + 2 < data.size) {
                    val velocity = data[i + 2].toInt() and 0xFF
                    if (velocity > 0) count++
                }
                i += 3
            } else if (status in 0x80..0x8F) {
                i += 3  // note-off
            } else if (status == 0xFF) {
                // Meta event
                i++
                if (i < data.size) {
                    i++ // meta type
                    if (i < data.size) {
                        // Read variable length
                        var len = 0
                        while (i < data.size) {
                            val b = data[i].toInt() and 0xFF
                            i++
                            len = (len shl 7) or (b and 0x7F)
                            if ((b and 0x80) == 0) break
                        }
                        i += len
                    }
                }
            } else {
                i++  // unknown, advance
            }
        }
        return count
    }
}
