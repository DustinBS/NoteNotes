package com.notenotes.ui.components

import androidx.compose.ui.graphics.Color
import com.notenotes.model.MusicalNote
import com.notenotes.util.ColorUtils
import com.notenotes.util.GuitarUtils
import com.notenotes.utils.NoteTextUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end style unit tests covering the editor -> waveform caption flow.
 * These tests exercise the mapping between human/0-based string representations,
 * ensure labelMap keys align, and verify annotated caption colors.
 */
class WaveformEditorE2ETest {

    @Test
    fun overlays_handleHumanAndIndexTabPositions_and_useCorrectColors() {
        // Chord on high E and B (human strings 1 and 2), open strings
        val note = MusicalNote(
            pitches = listOf(64, 59),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0), Pair(2, 0)) // human 1-based
        )

        val overlays = computeNoteOverlays(listOf(note), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val labelMap = overlays[0].labelMap

        // Expect human keys 1 (high E) and 2 (B)
        val highHuman = 1
        val bHuman = 2

        
        assertTrue("Expected high E key present", labelMap.containsKey(highHuman))
        assertTrue("Expected B key present", labelMap.containsKey(bHuman))

        // Verify annotated text contains expected note names/frets and color spans
        val highAnnotated = labelMap[highHuman]!!
        val bAnnotated = labelMap[bHuman]!!
        

        assertTrue(highAnnotated.text.contains("E4"))
        assertTrue(bAnnotated.text.contains("B3"))
        assertTrue(highAnnotated.text.contains("0"))
        assertTrue(bAnnotated.text.contains("0"))

        // Inspect first color span if present (buildPitchFretAnnotatedFromPosition applies a SpanStyle)
        val highSpanColor = highAnnotated.spanStyles.firstOrNull { it.item.color != Color.Unspecified }?.item?.color ?: Color.Unspecified
        val expectedHigh = ColorUtils.lightenColor(Color(com.notenotes.util.GuitarUtils.stringForHuman(highHuman).colorArgb))
        assertEquals(expectedHigh.red, highSpanColor.red, 0.0001f)
        assertEquals(expectedHigh.green, highSpanColor.green, 0.0001f)
        assertEquals(expectedHigh.blue, highSpanColor.blue, 0.0001f)

        val bSpanColor = bAnnotated.spanStyles.firstOrNull { it.item.color != Color.Unspecified }?.item?.color ?: Color.Unspecified
        val expectedB = ColorUtils.lightenColor(Color(com.notenotes.util.GuitarUtils.stringForHuman(bHuman).colorArgb))
        assertEquals(expectedB.red, bSpanColor.red, 0.0001f)
        assertEquals(expectedB.green, bSpanColor.green, 0.0001f)
        assertEquals(expectedB.blue, bSpanColor.blue, 0.0001f)
    }

    @Test
    fun pendingSelectedNoteLines_alignWithOverlayKeys_and_logMappings() {
        // Original chord (primary high E open, secondary B open)
        val selectedNote = MusicalNote(
            pitches = listOf(64, 59),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 0), Pair(2, 0))
        )

        // Create editor state and modify the primary fret so pending changes exist
        val state = com.notenotes.ui.components.GuitarChordEditState(selectedNote)
        // simulate user nudging primary fret from 0 -> 1
        state.editedPrimaryFret = 1

        // Reuse the same mapping logic as NoteEditorPanel to build pending lines
        val oldMap = linkedMapOf<Int, Pair<Int, Int>>()
        selectedNote.pitches.forEachIndexed { idx, pitch ->
            // Use canonical human view and normalize to human 1-based key
            val rawPosHuman = selectedNote.safeTabPositionsAsHuman.getOrNull(idx)
                ?: com.notenotes.util.GuitarUtils.fromMidi(pitch)
                ?: Pair(GuitarUtils.STRINGS.size, 0)
            val human = when {
                rawPosHuman.first in 1..GuitarUtils.STRINGS.size -> rawPosHuman.first
                rawPosHuman.first in GuitarUtils.STRINGS.indices -> com.notenotes.util.GuitarUtils.indexToHuman(rawPosHuman.first)
                else -> rawPosHuman.first.coerceIn(1, GuitarUtils.STRINGS.size)
            }
            val rawPos = Pair(human, rawPosHuman.second)
            if (!oldMap.containsKey(human)) oldMap[human] = rawPos
        }

        val newPitches = listOf(state.editedPrimaryMidi) + state.editableChordPitches
        val newPositions = state.getFullPositions()

        val newMap = linkedMapOf<Int, Pair<Int, Int>>()
        newPitches.forEachIndexed { idx, pitch ->
            val rawPos = newPositions.getOrNull(idx)
                ?: com.notenotes.util.GuitarUtils.fromMidi(pitch)?.let { Pair(it.first, it.second) }
                ?: Pair(GuitarUtils.indexToHuman(0), 0)
            val rawFirst = rawPos.first
            // Prefer interpreting numeric values as human 1-based numbers when ambiguous (match NoteEditorPanel)
            val human = when {
                rawFirst in 1..GuitarUtils.STRINGS.size -> rawFirst
                rawFirst in GuitarUtils.STRINGS.indices -> com.notenotes.util.GuitarUtils.indexToHuman(rawFirst)
                else -> com.notenotes.util.GuitarUtils.fromMidi(pitch)?.first?.coerceIn(1, GuitarUtils.STRINGS.size) ?: 1
            }
            val pos = Pair(human, rawPos.second)
            if (!newMap.containsKey(human)) newMap[human] = pos
        }

        val unionStrings = (oldMap.keys + newMap.keys).toSortedSet()
        val pendingMap = linkedMapOf<Int, androidx.compose.ui.text.AnnotatedString>()
        unionStrings.forEach { strIdx ->
            val oldPos = oldMap[strIdx]
            val newPos = newMap[strIdx]

                if (oldPos != null && newPos != null && oldPos == newPos) {
                pendingMap[strIdx] = NoteTextUtils.buildPitchFretAnnotatedFromPosition(strIdx, oldPos.second, isStrikethrough = false)
            } else {
                val oldAnnotated = if (oldPos != null) NoteTextUtils.buildPitchFretAnnotatedFromPosition(strIdx, oldPos.second, isStrikethrough = true, isPendingSmall = true) else androidx.compose.ui.text.AnnotatedString("")
                val newAnnotated = if (newPos != null) NoteTextUtils.buildPitchFretAnnotatedFromPosition(strIdx, newPos.second, isStrikethrough = false) else androidx.compose.ui.text.AnnotatedString("")
                val combined = androidx.compose.ui.text.buildAnnotatedString {
                    if (oldAnnotated.text.isNotEmpty()) append(oldAnnotated)
                    append(" ")
                    if (newAnnotated.text.isNotEmpty()) append(newAnnotated)
                }
                pendingMap[strIdx] = combined
            }
        }

        // Compute overlays for the original note (what WaveformView would render)
        val overlays = computeNoteOverlays(listOf(selectedNote), tempoBpm = 120, durationMs = 1000, paddedDurationSec = 1f)
        assertEquals(1, overlays.size)
        val overlayMap = overlays[0].labelMap

        

        // Pending keys should be present in the overlay label map (string indices align)
        for (k in pendingMap.keys) {
            assertTrue("Overlay missing key $k", overlayMap.containsKey(k))
            val overlayText = overlayMap[k]!!.text
            val pendingText = pendingMap[k]!!.text
            // The overlay (current stored) should appear inside the pending combined string
            assertTrue("Pending text should include overlay for key $k: pending='$pendingText' overlay='$overlayText'", pendingText.contains(overlayText) || overlayText.contains(pendingText))
        }
    }
}
