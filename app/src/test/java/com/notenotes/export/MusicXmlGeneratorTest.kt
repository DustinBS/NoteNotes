package com.notenotes.export

import com.notenotes.model.KeySignature
import com.notenotes.model.MusicalNote
import com.notenotes.model.TimeSignature
import com.notenotes.model.TranscriptionResult
import org.junit.Assert.*
import org.junit.Test

class MusicXmlGeneratorTest {

    private val generator = MusicXmlGenerator()

    private fun makeNote(
        midi: Int,
        type: String = "quarter",
        ticks: Int = 480,
        velocity: Int = 80,
        isRest: Boolean = false,
        dotted: Boolean = false,
        tiedToNext: Boolean = false
    ): MusicalNote {
        return MusicalNote(
            midiPitch = midi,
            durationTicks = ticks,
            type = type,
            dotted = dotted,
            isRest = isRest,
            tiedToNext = tiedToNext,
            velocity = velocity
        )
    }

    private fun makeResult(
        notes: List<MusicalNote>,
        key: KeySignature = KeySignature.C_MAJOR,
        time: TimeSignature = TimeSignature.FOUR_FOUR,
        tempo: Int = 120
    ): TranscriptionResult {
        return TranscriptionResult(
            notes = notes,
            keySignature = key,
            timeSignature = time,
            tempoBpm = tempo,
            divisions = 480
        )
    }

    /** T7.1: Single C4 quarter in C major 4/4 → XML contains pitch, step, key, time */
    @Test
    fun singleC4Quarter_containsPitchStepKeyTime() {
        val result = makeResult(notes = listOf(makeNote(midi = 60)))
        val xml = generator.generateMusicXml(result)

        assertTrue("XML should contain <pitch>", xml.contains("<pitch>"))
        assertTrue("XML should contain <step>C</step>", xml.contains("<step>C</step>"))
        assertTrue("XML should contain <key>", xml.contains("<key>"))
        assertTrue("XML should contain <time>", xml.contains("<time>"))
    }

    /** T7.2: G major scale → XML contains "fifths" with value "1" */
    @Test
    fun gMajorScale_containsFifthsOne() {
        val pitches = listOf(67, 69, 71, 72, 74, 76, 78, 79)
        val notes = pitches.map { makeNote(midi = it) }
        val result = makeResult(notes = notes, key = KeySignature.G_MAJOR)
        val xml = generator.generateMusicXml(result)

        assertTrue("XML should contain <fifths>1</fifths>", xml.contains("<fifths>1</fifths>"))
    }

    /** T7.3: Melody in 3/4 → XML contains beats and beat-type */
    @Test
    fun melodyInThreeFour_containsCorrectTimeSignature() {
        val notes = listOf(makeNote(midi = 60), makeNote(midi = 62), makeNote(midi = 64))
        val result = makeResult(notes = notes, time = TimeSignature.THREE_FOUR)
        val xml = generator.generateMusicXml(result)

        assertTrue("XML should contain <beats>3</beats>", xml.contains("<beats>3</beats>"))
        assertTrue("XML should contain <beat-type>4</beat-type>", xml.contains("<beat-type>4</beat-type>"))
    }

    /** T7.4: Dotted half note → XML contains <dot/> */
    @Test
    fun dottedHalfNote_containsDotElement() {
        val note = makeNote(midi = 60, type = "half", ticks = 1440, dotted = true)
        val result = makeResult(notes = listOf(note))
        val xml = generator.generateMusicXml(result)

        assertTrue("XML should contain <dot/>", xml.contains("<dot/>") || xml.contains("<dot />"))
    }

    /** T7.5: Rest in notes → XML contains <rest/> */
    @Test
    fun restInNotes_containsRestElement() {
        val notes = listOf(
            makeNote(midi = 60),
            makeNote(midi = 0, isRest = true),
            makeNote(midi = 64)
        )
        val result = makeResult(notes = notes)
        val xml = generator.generateMusicXml(result)

        assertTrue("XML should contain <rest/>", xml.contains("<rest/>") || xml.contains("<rest />"))
    }

    /** T7.6: Long note (whole + quarter ticks = 2400 in 4/4 with 480 div) → produces tied notes or no crash */
    @Test
    fun longNote_produceTiedNotesOrNoCrash() {
        val note = makeNote(midi = 60, type = "whole", ticks = 2400)
        val result = makeResult(notes = listOf(note))
        val xml = generator.generateMusicXml(result)

        assertNotNull("XML should not be null", xml)
        assertTrue("XML should not be empty", xml.isNotEmpty())
        // Either contains tie elements or at least produces valid output
        val hasTie = xml.contains("<tie") || xml.contains("<tied")
        val hasNote = xml.contains("<note")
        assertTrue("XML should contain tie elements or at least note elements", hasTie || hasNote)
    }

    /** T7.7: Valid XML structure: starts with "<?xml" and contains "<score-partwise" */
    @Test
    fun validXmlStructure_startsWithXmlDeclaration() {
        val result = makeResult(notes = listOf(makeNote(midi = 60)))
        val xml = generator.generateMusicXml(result)

        assertTrue("XML should start with <?xml", xml.trimStart().startsWith("<?xml"))
        assertTrue("XML should contain <score-partwise", xml.contains("<score-partwise"))
    }

    /** T7.8: Multiple notes → all appear in XML */
    @Test
    fun multipleNotes_allAppearInXml() {
        val notes = listOf(
            makeNote(midi = 60),
            makeNote(midi = 64),
            makeNote(midi = 67),
            makeNote(midi = 72)
        )
        val result = makeResult(notes = notes)
        val xml = generator.generateMusicXml(result)

        // Count occurrences of <note> elements
        val noteCount = "<note[> ]".toRegex().findAll(xml).count()
        assertTrue("XML should contain at least 4 note elements", noteCount >= 4)
    }

    /** T7.9: Empty notes list → generates valid XML (no crash, has score-partwise envelope) */
    @Test
    fun emptyNotesList_generatesValidXml() {
        val result = makeResult(notes = emptyList())
        val xml = generator.generateMusicXml(result)

        assertNotNull("XML should not be null", xml)
        assertTrue("XML should contain <score-partwise", xml.contains("<score-partwise"))
    }
}
