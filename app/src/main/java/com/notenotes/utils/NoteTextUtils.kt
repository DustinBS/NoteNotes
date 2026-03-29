package com.notenotes.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.notenotes.model.MusicalNote
import com.notenotes.util.GuitarUtils

object NoteTextUtils {
    private fun buildPitchEntries(note: MusicalNote): List<Triple<Int, Int, Int>> {
        val entries = mutableListOf<Triple<Int, Int, Int>>()
        note.pitches.forEachIndexed { i, pitch ->
            // Use the index-aligned view so callers receive 0-based string indices
            val sf = note.safeTabPositionsAsIndex.getOrNull(i) ?: Pair(0, 0)
            entries.add(Triple(pitch, sf.first, sf.second))
        }
        return entries.sortedBy { it.first }
    }

    private fun midiToDisplayName(midiNote: Int): String {
        if (midiNote !in 0..127) return "?"
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val noteIndex = midiNote % 12
        val octave = (midiNote / 12) - 1
        return "${names[noteIndex]}$octave"
    }

    fun buildPitchFretAnnotated(
        note: MusicalNote,
        isStrikethrough: Boolean = false,
        isPendingSmall: Boolean = false,
        saturated: Boolean = false
    ) = buildAnnotatedString {
        val entries = buildPitchEntries(note)
        entries.forEachIndexed { idx, (pitch, stringIndex, fret) ->
            // Lighten the base string color for the glyph fill so the outline remains visually dominant
            fun lightenColor(c: Color, amount: Float): Color {
                return Color(
                    red = (c.red + (1f - c.red) * amount).coerceIn(0f, 1f),
                    green = (c.green + (1f - c.green) * amount).coerceIn(0f, 1f),
                    blue = (c.blue + (1f - c.blue) * amount).coerceIn(0f, 1f),
                    alpha = c.alpha
                )
            }

            val LIGHTEN_AMOUNT = 0.36f
            val baseStringColor = if (stringIndex in GuitarUtils.STRINGS.indices) Color(GuitarUtils.STRINGS[stringIndex].colorArgb) else Color.Unspecified
            // When `saturated` is requested (Notes tab), prefer the base color so reds/oranges/yellows
            // remain vivid. Otherwise use the lighter variant used elsewhere for contrast.
            val stringColor = if (isStrikethrough) Color.Gray
            else if (baseStringColor != Color.Unspecified) {
                if (saturated) baseStringColor else lightenColor(baseStringColor, LIGHTEN_AMOUNT)
            } else Color.Unspecified

            val textDeco = if (isStrikethrough) androidx.compose.ui.text.style.TextDecoration.LineThrough else null

            val pitchFontSize = when {
                isPendingSmall -> 9.sp
                isStrikethrough -> 10.sp
                else -> androidx.compose.ui.unit.TextUnit.Unspecified
            }
            val fretFontSize = when {
                isPendingSmall -> 7.sp
                isStrikethrough -> 7.sp
                else -> 9.sp
            }

            

            // Use normal font weight; outlines handle emphasis now.
            withStyle(
                SpanStyle(
                    color = stringColor,
                    fontSize = pitchFontSize,
                    fontWeight = FontWeight.Normal,
                    textDecoration = textDeco
                )
            ) {
                append(midiToDisplayName(pitch))
            }
            if (!note.isRest && (note.isChord || note.hasTab)) {
                withStyle(SpanStyle(color = stringColor, fontSize = fretFontSize, baselineShift = BaselineShift.Superscript, textDecoration = textDeco, fontWeight = FontWeight.Normal)) {
                    append(fret.toString())
                }
            }
            if (idx != entries.lastIndex) append(" ")
        }
    }

    /**
     * Build an annotated string for a specific guitar string within a note/chord.
     * Returns null if the note does not contain an entry for that string index.
     */
    fun buildPitchFretAnnotatedForString(
        note: MusicalNote,
        targetStringIndex: Int,
        isStrikethrough: Boolean = false,
        isPendingSmall: Boolean = false,
        saturated: Boolean = false
    ) = buildAnnotatedString {
        val entries = buildPitchEntries(note)
        // Find all entries that map to the requested string index (usually 0 or 1 match)
        val matches = entries.filter { it.second == targetStringIndex }
        if (matches.isEmpty()) return@buildAnnotatedString

        fun lightenColor(c: Color, amount: Float): Color {
            return Color(
                red = (c.red + (1f - c.red) * amount).coerceIn(0f, 1f),
                green = (c.green + (1f - c.green) * amount).coerceIn(0f, 1f),
                blue = (c.blue + (1f - c.blue) * amount).coerceIn(0f, 1f),
                alpha = c.alpha
            )
        }

        val LIGHTEN_AMOUNT = 0.36f

        matches.forEachIndexed { mi, (pitch, stringIndex, fret) ->
            val baseStringColor = if (stringIndex in GuitarUtils.STRINGS.indices) Color(GuitarUtils.STRINGS[stringIndex].colorArgb) else Color.Unspecified
            val stringColor = if (isStrikethrough) Color.Gray
            else if (baseStringColor != Color.Unspecified) {
                if (saturated) baseStringColor else lightenColor(baseStringColor, LIGHTEN_AMOUNT)
            } else Color.Unspecified

            val textDeco = if (isStrikethrough) androidx.compose.ui.text.style.TextDecoration.LineThrough else null

            val pitchFontSize = when {
                isPendingSmall -> 9.sp
                isStrikethrough -> 10.sp
                else -> androidx.compose.ui.unit.TextUnit.Unspecified
            }
            val fretFontSize = when {
                isPendingSmall -> 7.sp
                isStrikethrough -> 7.sp
                else -> 9.sp
            }

            withStyle(
                SpanStyle(
                    color = stringColor,
                    fontSize = pitchFontSize,
                    fontWeight = FontWeight.Normal,
                    textDecoration = textDeco
                )
            ) {
                append(midiToDisplayName(pitch))
            }
            if (!note.isRest && (note.isChord || note.hasTab)) {
                withStyle(SpanStyle(color = stringColor, fontSize = fretFontSize, baselineShift = BaselineShift.Superscript, textDecoration = textDeco, fontWeight = FontWeight.Normal)) {
                    append(fret.toString())
                }
            }
            if (mi != matches.lastIndex) append(" ")
        }
    }

    /**
     * Build an annotated string directly from a guitar string index and fret,
     * avoiding the need to construct a temporary MusicalNote at callsites.
     */
    fun buildPitchFretAnnotatedFromPosition(
        stringIndex: Int,
        fret: Int,
        isStrikethrough: Boolean = false,
        isPendingSmall: Boolean = false,
        saturated: Boolean = false
    ) = buildAnnotatedString {
        fun lightenColor(c: Color, amount: Float): Color {
            return Color(
                red = (c.red + (1f - c.red) * amount).coerceIn(0f, 1f),
                green = (c.green + (1f - c.green) * amount).coerceIn(0f, 1f),
                blue = (c.blue + (1f - c.blue) * amount).coerceIn(0f, 1f),
                alpha = c.alpha
            )
        }

        val LIGHTEN_AMOUNT = 0.36f
        val baseStringColor = if (stringIndex in GuitarUtils.STRINGS.indices) Color(GuitarUtils.STRINGS[stringIndex].colorArgb) else Color.Unspecified
        val stringColor = if (isStrikethrough) Color.Gray
        else if (baseStringColor != Color.Unspecified) {
            if (saturated) baseStringColor else lightenColor(baseStringColor, LIGHTEN_AMOUNT)
        } else Color.Unspecified

        val textDeco = if (isStrikethrough) androidx.compose.ui.text.style.TextDecoration.LineThrough else null

        val pitchFontSize = when {
            isPendingSmall -> 9.sp
            isStrikethrough -> 10.sp
            else -> androidx.compose.ui.unit.TextUnit.Unspecified
        }
        val fretFontSize = when {
            isPendingSmall -> 7.sp
            isStrikethrough -> 7.sp
            else -> 9.sp
        }

        val midi = GuitarUtils.toMidi(GuitarUtils.indexToHuman(stringIndex), fret)

        withStyle(
            SpanStyle(
                color = stringColor,
                fontSize = pitchFontSize,
                fontWeight = FontWeight.Normal,
                textDecoration = textDeco
            )
        ) {
            append(midiToDisplayName(midi))
        }
        withStyle(SpanStyle(color = stringColor, fontSize = fretFontSize, baselineShift = BaselineShift.Superscript, textDecoration = textDeco, fontWeight = FontWeight.Normal)) {
            append(fret.toString())
        }
    }
}
