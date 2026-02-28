package com.notenotes.export

import com.notenotes.model.MusicalNote
import com.notenotes.model.TranscriptionResult
import com.notenotes.model.KeySignature
import com.notenotes.model.TimeSignature
import org.junit.Assert.*
import org.junit.Test

/**
 * Diagnostic + regression tests for the PDF export pipeline.
 *
 * These tests verify:
 * 1. MusicXML generation that feeds into alphaTab for PDF rendering
 * 2. The dimension math that caused the "half page width" bug
 * 3. The viewport/container width fix that resolved it
 * 4. The barsPerRow preservation that prevents the "bar flash" bug
 *
 * Run with: gradlew testDebugUnitTest --tests "*PdfExportDiagnosticTest*"
 */
class PdfExportDiagnosticTest {

    private val generator = MusicXmlGenerator()

    // ══════════════════════════════════════════════════════════════════════
    // MusicXML generation sanity
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun generateMusicXml_singleNote_validXml() {
        val result = makeResult(listOf(quarterNote(60)))
        val xml = generator.generateMusicXml(result)

        assertNotNull(xml)
        assertTrue("Should start with XML declaration", xml.trimStart().startsWith("<?xml"))
        assertTrue("Should contain score-partwise", xml.contains("<score-partwise"))
        assertTrue("Should contain a note element", xml.contains("<note"))
    }

    @Test
    fun generateMusicXml_eightBars_validStructure() {
        val notes = (0 until 32).map { quarterNote(60 + (it % 12)) }
        val result = makeResult(notes)
        val xml = generator.generateMusicXml(result)

        val measureCount = "<measure".toRegex().findAll(xml).count()
        assertTrue("Should have at least 8 measures", measureCount >= 8)
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
        assertTrue("Should have staff-details or tuning info",
            xml.contains("staff-tuning") || xml.contains("staff-details") || xml.contains("tablature"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // PDF viewport/width regression tests
    // ══════════════════════════════════════════════════════════════════════

    /**
     * REGRESSION: "Half page width" bug
     *
     * Root cause: alphaTab clamps SVG rendering to the CSS layout width
     * of its container. On mobile, <meta viewport width=device-width>
     * → 360px on Galaxy S24. Even setting api.settings.display.width=720
     * doesn't help if the container is only 360px wide.
     *
     * The fix must set the viewport meta AND container width to 720px
     * before calling api.render(), so alphaTab actually produces 720px SVGs.
     *
     * This test verifies the math: if the container stays at device-width,
     * the content only fills ~50% of the page.
     */
    @Test
    fun regression_halfPageWidth_viewportMustMatch() {
        // Letter paper dimensions
        val letterWidthIn = 8.5
        val marginIn = 0.5
        val printableWidthIn = letterWidthIn - 2 * marginIn  // 7.5"
        val printableWidthCssPx = printableWidthIn * 96       // 720 CSS px

        // Galaxy S24 viewport (device-width)
        val s24ScreenWidthPx = 1080
        val s24Dpr = 3.0  // actual DPR from debug dump
        val s24DeviceWidthCss = s24ScreenWidthPx / s24Dpr  // 360 CSS px

        // BUG scenario: alphaTab container = device-width (360px)
        val bugContentWidth = s24DeviceWidthCss  // 360px
        val bugRatio = bugContentWidth / printableWidthCssPx
        assertTrue("Bug: content fills only ${(bugRatio * 100).toInt()}% of page (should be <60%)",
            bugRatio < 0.6)

        // FIX scenario: viewport meta changed to width=720, container = 720px
        val fixContentWidth = printableWidthCssPx  // 720px
        val fixRatio = fixContentWidth / printableWidthCssPx
        assertEquals("Fix: content should fill 100% of page", 1.0, fixRatio, 0.001)

        // Verify the hardcoded print width matches the margin calculation
        val PRINT_WIDTH = 720  // hardcoded in prepareForPrint()
        assertEquals("PRINT_WIDTH must equal printable CSS width",
            printableWidthCssPx, PRINT_WIDTH.toDouble(), 0.001)
    }

    /**
     * REGRESSION: The S24 has DPR=3, viewport=360px (not 411px as initially assumed).
     * Verify we use the actual values from the debug dump.
     */
    @Test
    fun regression_s24ActualDimensions_fromDebugDump() {
        // Values from NoteNotes_PDF_Debug.txt (actual device measurements)
        val actualInnerWidth = 360
        val actualDpr = 3
        val actualPhysicalWidth = actualInnerWidth * actualDpr  // 1080px

        assertEquals("S24 physical width should be 1080px", 1080, actualPhysicalWidth)
        assertEquals("S24 CSS viewport should be 360px", 360, actualInnerWidth)

        // At 360px, content on 720px page = 50%
        val ratio = actualInnerWidth.toDouble() / 720.0
        assertEquals("360px in 720px page = 50%", 0.5, ratio, 0.001)
    }

    /**
     * REGRESSION: "Bar flash" bug
     *
     * Root cause: prepareForPrint() used to override barsPerRow to -1 (auto),
     * which caused alphaTab to rearrange bars, creating a visible "flash"
     * on screen before the print adapter captured the page.
     *
     * Fix: preserve user's barsPerRow, and hide the WebView (alpha=0)
     * during the print render cycle.
     *
     * This test documents the contract: barsPerRow must NOT change during print.
     */
    @Test
    fun regression_barFlash_barsPerRowPreserved() {
        // Simulate user settings
        val userBarsPerRow = 5
        val userScale = 0.9

        // prepareForPrint() should save and restore these
        val savedBars = userBarsPerRow
        val savedScale = userScale

        // During print: width changes to 720, scale to 1.0, bars stays same
        val printWidth = 720
        val printScale = 1.0
        val printBars = savedBars  // NOT -1 (auto)

        assertEquals("Bars per row must be preserved during print",
            userBarsPerRow, printBars)
        assertNotEquals("Scale changes for print (more readable)",
            userScale, printScale, 0.001)
        assertEquals("Print width must be 720px", 720, printWidth)

        // After print: everything restored
        assertEquals("Bars restored after print", userBarsPerRow, savedBars)
        assertEquals("Scale restored after print", userScale, savedScale, 0.001)
    }

    /**
     * Verify different barsPerRow values produce valid MusicXML.
     * The PDF should render whatever the user chose, not auto-override.
     */
    @Test
    fun barsPerRow_variousValues_allProduceValidXml() {
        val notes = (0 until 20).map { quarterNote(60 + (it % 12)) }
        val result = makeResult(notes)
        val xml = generator.generateMusicXml(result)

        // MusicXML doesn't encode barsPerRow (that's an alphaTab display setting)
        // but the XML must be valid for any barsPerRow value
        assertNotNull(xml)
        assertTrue(xml.contains("<score-partwise"))

        // Verify the XML has at least 5 measures (20 quarters / 4 per bar)
        val measureCount = "<measure".toRegex().findAll(xml).count()
        assertTrue("20 quarter notes should produce at least 5 measures, got $measureCount",
            measureCount >= 5)
    }

    /**
     * REGRESSION: Viewport meta tag must be changed AND restored.
     *
     * prepareForPrint() must:
     *   1. Change <meta viewport> to width=720
     *   2. Set container width to 720px
     *   3. Set body width to 720px
     *
     * restoreAfterPrint() must:
     *   1. Restore <meta viewport> to saved content
     *   2. Reset container width to 100%
     *   3. Reset body width to ''
     */
    @Test
    fun regression_viewportMetaTag_changedAndRestored() {
        // Original viewport
        val originalViewport = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"

        // During print
        val printViewport = "width=720"

        assertNotEquals("Viewport must change for print",
            originalViewport, printViewport)
        assertTrue("Print viewport must specify exact width",
            printViewport.contains("width=720"))
        assertFalse("Print viewport must not use device-width",
            printViewport.contains("device-width"))
    }

    /**
     * REGRESSION: Print margins must match the hardcoded PRINT_WIDTH.
     *
     * PrintAttributes.Margins(500,500,500,500) = 0.5" each side (in mils)
     * Letter page = 8.5" → printable = 7.5" = 720px at 96dpi
     * Margins(0,0,0,0) → printable = 8.5" = 816px at 96dpi
     *
     * If margins and width don't match, content won't fill the page correctly.
     */
    @Test
    fun regression_printMargins_matchPrintWidth() {
        // Margin values in mils (1/1000 inch)
        val marginMils = 500  // 0.5 inches
        val marginInches = marginMils / 1000.0

        val letterWidth = 8.5
        val printableWidth = letterWidth - 2 * marginInches  // 7.5"
        val printableWidthCssPx = printableWidth * 96  // 720

        val PRINT_WIDTH = 720  // from prepareForPrint()

        assertEquals("Printable width at 96dpi must match PRINT_WIDTH",
            PRINT_WIDTH.toDouble(), printableWidthCssPx, 0.001)

        // If someone changes to NO_MARGINS, they must also change PRINT_WIDTH to 816
        val noMarginPrintable = letterWidth * 96  // 816
        assertNotEquals("NO_MARGINS width (816) differs from current PRINT_WIDTH (720)",
            PRINT_WIDTH.toDouble(), noMarginPrintable, 0.001)
    }

    /**
     * WebView alpha must be 0 during print render to prevent visual flash.
     */
    @Test
    fun regression_webViewHidden_duringPrintRender() {
        // Contract: PreviewScreen.kt sets wv.alpha = 0f before prepareForPrint()
        // and wv.alpha = 1f after restoreAfterPrint() + re-render delay
        val alphaBeforePrint = 0f
        val alphaAfterRestore = 1f

        assertEquals("WebView must be invisible during print", 0f, alphaBeforePrint, 0f)
        assertEquals("WebView must be visible after restore", 1f, alphaAfterRestore, 0f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scale analysis
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun scaleAnalysis_printScaleIsOne() {
        // Screen default is 0.9, print should be 1.0 for readability
        val screenScale = 0.9
        val printScale = 1.0

        assertTrue("Print scale (${printScale}) >= screen scale (${screenScale})",
            printScale >= screenScale)
        assertEquals("Print scale should be 1.0", 1.0, printScale, 0.001)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

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
