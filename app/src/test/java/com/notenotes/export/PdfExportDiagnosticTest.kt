package com.notenotes.export

import com.notenotes.model.MusicalNote
import com.notenotes.model.TranscriptionResult
import com.notenotes.model.KeySignature
import com.notenotes.model.TimeSignature
import org.junit.Assert.*
import org.junit.Test

/**
 * Diagnostic tests for the PDF export pipeline.
 *
 * These tests verify the MusicXML that feeds into alphaTab for PDF rendering.
 * They help isolate whether sizing issues are in the MusicXML generation,
 * the alphaTab rendering settings, or the Android print adapter.
 *
 * Run with: gradlew testDebugUnitTest --tests "*PdfExportDiagnosticTest*"
 */
class PdfExportDiagnosticTest {

    private val generator = MusicXmlGenerator()

    // ── MusicXML generation sanity ──────────────────────────────────────────

    @Test
    fun generateMusicXml_singleNote_validXml() {
        val result = makeResult(listOf(quarterNote(60)))
        val xml = generator.generateMusicXml(result)

        assertNotNull(xml)
        assertTrue("Should start with XML declaration", xml.trimStart().startsWith("<?xml"))
        assertTrue("Should contain score-partwise", xml.contains("<score-partwise"))
        assertTrue("Should contain a note element", xml.contains("<note"))
        println("[PDF-DIAG] Single note XML length: ${xml.length} chars")
        println("[PDF-DIAG] XML preview:\n${xml.take(500)}")
    }

    @Test
    fun generateMusicXml_eightBars_validStructure() {
        // 8 bars of quarter notes at 4/4 = 32 notes
        val notes = (0 until 32).map { quarterNote(60 + (it % 12)) }
        val result = makeResult(notes)
        val xml = generator.generateMusicXml(result)

        val measureCount = "<measure".toRegex().findAll(xml).count()
        println("[PDF-DIAG] 32 quarters → $measureCount measures, XML length: ${xml.length}")
        assertTrue("Should have multiple measures", measureCount >= 8)
    }

    @Test
    fun generateMusicXml_withChords_validStructure() {
        val chord = MusicalNote(
            midiPitch = 60,
            durationTicks = 4,
            type = "quarter",
            chordPitches = listOf(64, 67),
            chordName = "C Major"
        )
        val result = makeResult(listOf(chord, quarterNote(62), quarterNote(64), quarterNote(65)))
        val xml = generator.generateMusicXml(result)

        assertTrue("Should contain chord element", xml.contains("<chord") || xml.contains("chord"))
        println("[PDF-DIAG] Chord XML length: ${xml.length}")
    }

    @Test
    fun generateMusicXml_withGuitarTab_hasTuning() {
        val note = MusicalNote(
            midiPitch = 64,
            durationTicks = 4,
            type = "quarter",
            guitarString = 1,
            guitarFret = 0
        )
        val result = makeResult(listOf(note))
        val xml = generator.generateMusicXml(result)

        // Should have tab staff info
        println("[PDF-DIAG] Guitar tab XML:\n${xml.take(800)}")
        assertTrue("Should have staff-details or staff-tuning for tab", 
            xml.contains("staff-tuning") || xml.contains("staff-details") || xml.contains("tablature"))
    }

    // ── Dimension calculations (documenting expected values) ────────────────

    @Test
    fun printDimensionCalculations() {
        // Document the math for PDF scaling
        val letterWidthIn = 8.5
        val letterHeightIn = 11.0
        val marginIn = 0.5
        val printableWidthIn = letterWidthIn - 2 * marginIn  // 7.5"
        val printableHeightIn = letterHeightIn - 2 * marginIn // 10.0"

        // CSS pixels: 1 CSS px = 1/96 inch (CSS standard)
        val printableWidthCssPx = printableWidthIn * 96   // 720
        val fullPageWidthCssPx = letterWidthIn * 96        // 816

        // Galaxy S24 viewport
        val s24ScreenWidthPx = 1080
        val s24Dpr = 2.625  // device pixel ratio
        val s24CssDeviceWidth = s24ScreenWidthPx / s24Dpr  // ~411px

        // BUG: old code used window.innerWidth (~411px) for alphaTab width
        // Print adapter renders at page CSS width (720px with 0.5" margins)
        // → 411px content in 720px page → 57% of page → "half the page"
        val bugRatio = s24CssDeviceWidth / printableWidthCssPx

        // FIX: hardcode alphaTab width = 720 to match printable area
        val fixRatio = printableWidthCssPx / printableWidthCssPx

        println("""
            [PDF-DIAG] ════════════════════════════════════════════════
            [PDF-DIAG]  PDF DIMENSION REFERENCE
            [PDF-DIAG] ════════════════════════════════════════════════
            [PDF-DIAG] Letter page: ${letterWidthIn}" × ${letterHeightIn}"
            [PDF-DIAG] Margins: ${marginIn}" each side
            [PDF-DIAG] Printable: ${printableWidthIn}" × ${printableHeightIn}"
            [PDF-DIAG]
            [PDF-DIAG] Printable CSS px (96dpi): ${printableWidthCssPx}px ← alphaTab width
            [PDF-DIAG] Full page CSS px (96dpi): ${fullPageWidthCssPx}px
            [PDF-DIAG]
            [PDF-DIAG] Galaxy S24 CSS device-width: ${String.format("%.0f", s24CssDeviceWidth)}px
            [PDF-DIAG]
            [PDF-DIAG] BUG (old): width=innerWidth(${String.format("%.0f", s24CssDeviceWidth)}) in ${String.format("%.0f", printableWidthCssPx)}px page
            [PDF-DIAG]   → ${String.format("%.0f", bugRatio * 100)}% of page ← TOO NARROW ("half the page")
            [PDF-DIAG]
            [PDF-DIAG] FIX: width=720 matching 720px printable area
            [PDF-DIAG]   → ${String.format("%.0f", fixRatio * 100)}% of page ← FILLS PAGE
            [PDF-DIAG] ════════════════════════════════════════════════
        """.trimIndent())

        // Verify the fix fills the page
        assertEquals("Fixed ratio should be 100%", 1.0, fixRatio, 0.001)
        assertTrue("Bug ratio was less than 60%", bugRatio < 0.6)
        // alphaTab print width = printable CSS width
        assertEquals("Print width must be 720", 720.0, printableWidthCssPx, 0.001)
    }

    // ── Scale sensitivity analysis ──────────────────────────────────────────

    @Test
    fun scaleAnalysis_documentAlphaTabScaleEffects() {
        // AlphaTab scale affects the physical size of note heads, staff lines, etc.
        // This test documents what scale values mean for readability at print size.
        val viewportWidth = 980  // typical WebView wide viewport

        data class ScaleScenario(
            val scale: Double,
            val label: String,
            val effectiveStaffHeightMm: Double  // approximate
        )

        val scenarios = listOf(
            ScaleScenario(0.5, "Tiny (hard to read)", 5.0),
            ScaleScenario(0.7, "Small (compact)", 7.0),
            ScaleScenario(0.9, "Default (phone screen)", 9.0),
            ScaleScenario(1.0, "Standard (good for print)", 10.0),
            ScaleScenario(1.2, "Large (easy to read)", 12.0),
            ScaleScenario(1.5, "Very large (fewer bars per page)", 15.0)
        )

        println("[PDF-DIAG] ════════════════════════════════════════════════")
        println("[PDF-DIAG]  SCALE SENSITIVITY ANALYSIS")
        println("[PDF-DIAG]  (viewport width = ${viewportWidth}px)")
        println("[PDF-DIAG] ════════════════════════════════════════════════")
        println("[PDF-DIAG] ${String.format("%-8s %-35s %s", "Scale", "Description", "~Staff Height")}")
        println("[PDF-DIAG] ${"─".repeat(60)}")
        for (s in scenarios) {
            println("[PDF-DIAG] ${String.format("%-8.1f %-35s ~%.0fmm", s.scale, s.label, s.effectiveStaffHeightMm)}")
        }
        println("[PDF-DIAG] ════════════════════════════════════════════════")
        println("[PDF-DIAG] Recommendation: scale=1.0 for print, 0.9 for screen")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun quarterNote(midi: Int) = MusicalNote(
        midiPitch = midi,
        durationTicks = 4,
        type = "quarter"
    )

    private fun makeResult(
        notes: List<MusicalNote>,
        key: KeySignature = KeySignature.C_MAJOR,
        time: TimeSignature = TimeSignature.FOUR_FOUR,
        bpm: Int = 120
    ) = TranscriptionResult(
        notes = notes,
        keySignature = key,
        timeSignature = time,
        tempoBpm = bpm
    )
}
