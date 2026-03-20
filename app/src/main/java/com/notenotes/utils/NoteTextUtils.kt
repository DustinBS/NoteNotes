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
            val sf = note.tabPositions.getOrNull(i) ?: Pair(0, 0)
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

    fun buildPitchFretAnnotated(note: MusicalNote) = buildAnnotatedString {
        val entries = buildPitchEntries(note)
        entries.forEachIndexed { idx, (pitch, stringIndex, fret) ->
            val stringColor = if (stringIndex in GuitarUtils.STRINGS.indices)
                Color(GuitarUtils.STRINGS[stringIndex].colorArgb)
            else Color.Unspecified

            withStyle(SpanStyle(color = stringColor, fontWeight = FontWeight.SemiBold)) {
                append(midiToDisplayName(pitch))
            }
            if (!note.isRest && (note.isChord || note.hasTab)) {
                withStyle(SpanStyle(color = stringColor, fontSize = 8.sp, baselineShift = BaselineShift.Superscript)) {
                    append(fret.toString())
                }
            }
            if (idx != entries.lastIndex) append(" ")
        }
    }
}
