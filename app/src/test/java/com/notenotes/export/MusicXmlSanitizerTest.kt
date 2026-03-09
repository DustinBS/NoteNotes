package com.notenotes.export

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for MusicXmlSanitizer to verify non-standard durations
 * are correctly decomposed into tied standard-duration notes.
 */
class MusicXmlSanitizerTest {

    private val starshipXml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
<score-partwise version="4.0">
  <part-list>
    <score-part id="P1">
      <part-name>Melody</part-name>
    </score-part>
  </part-list>
  <part id="P1">
    <measure number="1">
      <attributes>
        <divisions>4</divisions>
        <key><fifths>-4</fifths><mode>minor</mode></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef>
      </attributes>
      <note>
        <pitch><step>A</step><octave>2</octave></pitch>
        <duration>14</duration>
        <voice>1</voice>
        <type>half</type>
        <notations>
          <technical><string>5</string><fret>0</fret></technical>
        </notations>
      </note>
      <note>
        <chord/>
        <pitch><step>E</step><octave>3</octave></pitch>
        <duration>14</duration>
        <voice>1</voice>
        <type>half</type>
        <notations>
          <technical><string>4</string><fret>2</fret></technical>
        </notations>
      </note>
      <note>
        <pitch><step>A</step><octave>2</octave></pitch>
        <duration>2</duration>
        <tie type="start"/>
        <voice>1</voice>
        <type>eighth</type>
        <notations>
          <tied type="start"/>
          <technical><string>5</string><fret>0</fret></technical>
        </notations>
      </note>
    </measure>
  </part>
</score-partwise>"""

    @Test
    fun sanitize_nonStandardDuration14_decomposedCorrectly() {
        val result = MusicXmlSanitizer.sanitize(starshipXml)

        // Duration 14 should NOT exist in output
        assertFalse(
            "Duration 14 should be decomposed",
            result.contains("<duration>14</duration>")
        )

        // Should have dotted half (12) and eighth (2) from decomposition
        assertTrue(
            "Should contain duration 12 (dotted half)",
            result.contains("<duration>12</duration>")
        )

        // Original duration 2 should still exist
        assertTrue(
            "Should contain duration 2 (eighth)",
            result.contains("<duration>2</duration>")
        )

        // Chord notes should be preserved
        assertTrue("Chord indicator preserved", result.contains("<chord/>"))

        // Voice should be preserved
        assertTrue("Voice preserved", result.contains("<voice>1</voice>"))

        println("=== SANITIZED OUTPUT ===")
        println(result)
        println("========================")
    }

    @Test
    fun sanitize_standardDurations_unchanged() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="4.0">
  <part-list><score-part id="P1"><part-name>M</part-name></score-part></part-list>
  <part id="P1">
    <measure number="1">
      <attributes><divisions>4</divisions></attributes>
      <note>
        <pitch><step>C</step><octave>4</octave></pitch>
        <duration>4</duration>
        <voice>1</voice>
        <type>quarter</type>
      </note>
      <note>
        <pitch><step>D</step><octave>4</octave></pitch>
        <duration>8</duration>
        <voice>1</voice>
        <type>half</type>
      </note>
      <note>
        <rest/>
        <duration>4</duration>
        <voice>1</voice>
        <type>quarter</type>
      </note>
    </measure>
  </part>
</score-partwise>"""

        val result = MusicXmlSanitizer.sanitize(xml)
        // With all standard durations, only type fixing should occur
        assertTrue(result.contains("<duration>4</duration>"))
        assertTrue(result.contains("<duration>8</duration>"))
        assertFalse("Should not add ties to already-correct notes", result.contains("<tie"))
    }

    @Test
    fun sanitize_duration9_decomposedTo8plus1() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="4.0">
  <part-list><score-part id="P1"><part-name>M</part-name></score-part></part-list>
  <part id="P1">
    <measure number="1">
      <attributes><divisions>4</divisions></attributes>
      <note>
        <pitch><step>E</step><octave>3</octave></pitch>
        <duration>9</duration>
        <tie type="stop"/>
        <voice>1</voice>
        <type>half</type>
        <notations><tied type="stop"/></notations>
      </note>
      <note>
        <pitch><step>E</step><octave>4</octave></pitch>
        <duration>7</duration>
        <tie type="start"/>
        <voice>1</voice>
        <type>quarter</type>
        <notations>
          <tied type="start"/>
          <technical><string>1</string><fret>0</fret></technical>
        </notations>
      </note>
    </measure>
  </part>
</score-partwise>"""

        val result = MusicXmlSanitizer.sanitize(xml)
        assertFalse("Duration 9 should be decomposed", result.contains("<duration>9</duration>"))
        assertFalse("Duration 7 should be decomposed", result.contains("<duration>7</duration>"))
        assertTrue("Should have duration 8 (half)", result.contains("<duration>8</duration>"))
        assertTrue("Should have duration 6 (dotted quarter)", result.contains("<duration>6</duration>"))
        assertTrue("Should have duration 1 (16th)", result.contains("<duration>1</duration>"))

        println("=== SANITIZED M6-like ===")
        println(result)
    }

    @Test
    fun sanitize_allStarshipBadDurations_eliminated() {
        // Test all non-standard durations from the starship file: 14, 13, 9, 7, 15, 11, 5
        val badDurations = listOf(14, 13, 9, 7, 15, 11, 5)
        val stdSet = setOf(16, 12, 8, 6, 4, 3, 2, 1)

        for (dur in badDurations) {
            assertFalse("$dur should not be standard", dur in stdSet)

            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="4.0">
  <part-list><score-part id="P1"><part-name>M</part-name></score-part></part-list>
  <part id="P1">
    <measure number="1">
      <attributes><divisions>4</divisions></attributes>
      <note>
        <pitch><step>A</step><octave>2</octave></pitch>
        <duration>$dur</duration>
        <voice>1</voice>
        <type>quarter</type>
      </note>
    </measure>
  </part>
</score-partwise>"""

            val result = MusicXmlSanitizer.sanitize(xml)
            assertFalse(
                "Duration $dur should be eliminated by sanitizer",
                result.contains("<duration>$dur</duration>")
            )

            // Extract all durations from result
            val resultDurs = Regex("<duration>(\\d+)</duration>")
                .findAll(result).map { it.groupValues[1].toInt() }.toList()
            assertTrue(
                "All durations in result for $dur should be standard: $resultDurs",
                resultDurs.all { it in stdSet }
            )
            assertEquals(
                "Durations for $dur should sum correctly: $resultDurs",
                dur, resultDurs.sum()
            )
        }
    }
}
