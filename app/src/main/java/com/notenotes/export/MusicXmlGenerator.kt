package com.notenotes.export

import com.notenotes.model.MusicalNote
import com.notenotes.model.TranscriptionResult
import com.notenotes.util.PitchUtils
import java.io.File

/**
 * Generates MusicXML 4.0 partwise format from a TranscriptionResult.
 * 
 * MusicXML is a well-documented XML format for music notation.
 * This is a custom builder — no heavy library needed.
 */
class MusicXmlGenerator {

    /**
     * Generate MusicXML string from a TranscriptionResult.
     */
    fun generateMusicXml(result: TranscriptionResult, partName: String = "Melody"): String {
        val sb = StringBuilder()
        
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">""")
        sb.appendLine("""<score-partwise version="4.0">""")
        
        // Part list
        sb.appendLine("""  <part-list>""")
        sb.appendLine("""    <score-part id="P1">""")
        sb.appendLine("""      <part-name>$partName</part-name>""")
        sb.appendLine("""      <score-instrument id="P1-I1">""")
        sb.appendLine("""        <instrument-name>Guitar</instrument-name>""")
        sb.appendLine("""      </score-instrument>""")
        sb.appendLine("""      <midi-instrument id="P1-I1">""")
        sb.appendLine("""        <midi-channel>1</midi-channel>""")
        sb.appendLine("""        <midi-program>26</midi-program>""")
        sb.appendLine("""      </midi-instrument>""")
        sb.appendLine("""    </score-part>""")
        sb.appendLine("""  </part-list>""")
        
        // Part with measures
        sb.appendLine("""  <part id="P1">""")
        
        if (result.notes.isEmpty()) {
            // Empty measure
            sb.appendLine("""    <measure number="1">""")
            appendAttributes(sb, result)
            appendTempoDirection(sb, result.tempoBpm)
            appendWholeRest(sb, result)
            sb.appendLine("""    </measure>""")
        } else {
            appendMeasures(sb, result)
        }
        
        sb.appendLine("""  </part>""")
        sb.appendLine("""</score-partwise>""")
        
        return sb.toString()
    }

    /**
     * Write MusicXML to a file.
     */
    fun writeToFile(result: TranscriptionResult, file: File, partName: String = "Melody") {
        val xml = generateMusicXml(result, partName)
        file.writeText(xml)
    }

    // ── Standard-duration helpers (same logic as MusicXmlSanitizer) ─────────

    private data class StdDur(val ticks: Int, val typeName: String, val dotted: Boolean)

    private fun standardDurations(div: Int): List<StdDur> = listOfNotNull(
        StdDur(div * 4, "whole", false),
        StdDur(div * 3, "half", true),
        StdDur(div * 2, "half", false),
        if (div * 3 % 2 == 0) StdDur(div * 3 / 2, "quarter", true) else null,
        if (div % 1 == 0) StdDur(div, "quarter", false) else null,
        if (div * 3 % 4 == 0) StdDur(div * 3 / 4, "eighth", true) else null,
        if (div % 2 == 0) StdDur(div / 2, "eighth", false) else null,
        if (div % 4 == 0) StdDur(div / 4, "16th", false) else null
    ).filter { it.ticks > 0 }.sortedByDescending { it.ticks }

    /** Greedy decomposition of [ticks] into standard durations. */
    private fun decomposeToStandard(ticks: Int, stds: List<StdDur>): List<StdDur> {
        val result = mutableListOf<StdDur>()
        var rem = ticks
        while (rem > 0) {
            val sd = stds.firstOrNull { it.ticks <= rem }
            if (sd != null) { result.add(sd); rem -= sd.ticks }
            else { result.add(StdDur(rem, "16th", false)); break }
        }
        return result
    }

    /**
     * Distribute notes into measures based on time signature.
     * Single staff with guitar technical notation (<string>/<fret>).
     * alphaTab handles Score+Tab rendering via staveProfile.
     *
     * Non-standard durations (e.g. 14, 13 ticks with divisions=4) are
     * decomposed into tied standard-duration notes at generation time
     * so the output is always valid for alphaTab.
     */
    private fun appendMeasures(sb: StringBuilder, result: TranscriptionResult) {
        val divisions = result.divisions
        val beatsPerMeasure = result.timeSignature.beats
        val beatType = result.timeSignature.beatType
        val stds = standardDurations(divisions)
        val stdSet = stds.map { it.ticks }.toSet()
        
        val ticksPerMeasure = when (beatType) {
            8 -> beatsPerMeasure * divisions / 2
            2 -> beatsPerMeasure * divisions * 2
            else -> beatsPerMeasure * divisions
        }
        
        var measureNumber = 1
        var ticksInCurrentMeasure = 0
        var noteIndex = 0
        var pendingTieStop = false
        val notes = result.notes.toMutableList()

        // Preserve starting silence: if the first note has a positive
        // timePositionMs, insert leading rest(s) so the offset survives
        // a MusicXML round-trip.
        if (notes.isNotEmpty()) {
            val firstNote = notes.first()
            val firstTimeMs = firstNote.timePositionMs
            if (firstTimeMs != null && firstTimeMs > 0f) {
                val tickMs = 60000.0 / result.tempoBpm / divisions
                val gapTicks = Math.round(firstTimeMs / tickMs).toInt()
                if (gapTicks > 0) {
                    notes.add(0, MusicalNote(
                        pitches = emptyList(),
                        durationTicks = gapTicks,
                        type = ticksToType(gapTicks, divisions),
                        dotted = false,
                        isRest = true,
                        timePositionMs = 0f
                    ))
                }
            }
        }
        
        while (noteIndex < notes.size) {
            sb.appendLine("""    <measure number="$measureNumber">""")
            
            if (measureNumber == 1) {
                appendAttributes(sb, result)
                appendTempoDirection(sb, result.tempoBpm)
            }
            
            ticksInCurrentMeasure = 0
            
            while (noteIndex < notes.size && ticksInCurrentMeasure < ticksPerMeasure) {
                val note = notes[noteIndex]
                val remainingTicks = ticksPerMeasure - ticksInCurrentMeasure
                
                if (note.durationTicks <= remainingTicks) {
                    // Note fits in current measure — decompose if non-standard
                    if (note.durationTicks in stdSet) {
                        // Standard duration — emit directly
                        val std = stds.first { it.ticks == note.durationTicks }
                        val fixedNote = note.copy(type = std.typeName, dotted = std.dotted)
                        appendNote(sb, fixedNote, 0, divisions, result.keySignature.fifths,
                            tieStart = false, tieStop = pendingTieStop, isChordNote = false)
                        if (note.isChord) {
                            for (i in 1 until note.pitches.size) {
                                appendNote(sb, fixedNote, i, divisions, result.keySignature.fifths,
                                    tieStart = false, tieStop = pendingTieStop, isChordNote = true)
                            }
                        }
                    } else {
                        // Non-standard duration — decompose into tied standard parts
                        val parts = decomposeToStandard(note.durationTicks, stds)
                        for ((pi, sd) in parts.withIndex()) {
                            val isFirst = pi == 0
                            val isLast = pi == parts.size - 1
                            val partTieStart = if (note.isRest) false else !isLast
                            val partTieStop = if (note.isRest) false
                                              else if (isFirst) pendingTieStop else true
                            val partNote = note.copy(
                                durationTicks = sd.ticks,
                                type = sd.typeName,
                                dotted = sd.dotted,
                                tiedToNext = partTieStart
                            )
                            appendNote(sb, partNote, 0, divisions, result.keySignature.fifths,
                                tieStart = partTieStart, tieStop = partTieStop,
                                isChordNote = false)
                            if (note.isChord) {
                                for (i in 1 until note.pitches.size) {
                                    appendNote(sb, partNote, i, divisions, result.keySignature.fifths,
                                        tieStart = partTieStart, tieStop = partTieStop,
                                        isChordNote = true)
                                }
                            }
                        }
                    }
                    pendingTieStop = false
                    ticksInCurrentMeasure += note.durationTicks
                    noteIndex++
                } else {
                    // Note needs to be split across measures — remaining piece also
                    // gets decomposed if non-standard (handled on next iteration)
                    val remainingDuration = note.durationTicks - remainingTicks
                    
                    if (!note.isRest) {
                        // Decompose the part that fits this measure
                        val fitTicks = if (remainingTicks in stdSet) remainingTicks else remainingTicks
                        val fitParts = decomposeToStandard(fitTicks, stds)
                        for ((pi, sd) in fitParts.withIndex()) {
                            val isFirst = pi == 0
                            val isLast = pi == fitParts.size - 1
                            val partTieStop = if (isFirst) pendingTieStop else true
                            val firstPart = note.copy(
                                durationTicks = sd.ticks,
                                type = sd.typeName,
                                dotted = sd.dotted,
                                tiedToNext = true
                            )
                            appendNote(sb, firstPart, 0, divisions, result.keySignature.fifths,
                                tieStart = true, tieStop = partTieStop, isChordNote = false)
                            if (note.isChord) {
                                for (i in 1 until note.pitches.size) {
                                    appendNote(sb, firstPart, i, divisions, result.keySignature.fifths,
                                        tieStart = true, tieStop = partTieStop, isChordNote = true)
                                }
                            }
                        }
                        pendingTieStop = false
                        
                        notes[noteIndex] = note.copy(
                            durationTicks = remainingDuration,
                            type = ticksToType(remainingDuration, divisions),
                            dotted = false,
                            tiedToNext = false
                        )
                        pendingTieStop = true
                    } else {
                        val fitParts = decomposeToStandard(remainingTicks, stds)
                        for ((_, sd) in fitParts.withIndex()) {
                            val restPart = note.copy(
                                durationTicks = sd.ticks,
                                type = sd.typeName,
                                dotted = sd.dotted
                            )
                            appendNote(sb, restPart, 0, divisions, result.keySignature.fifths,
                                tieStart = false, tieStop = false, isChordNote = false)
                        }
                        
                        notes[noteIndex] = note.copy(
                            durationTicks = remainingDuration,
                            type = ticksToType(remainingDuration, divisions),
                            dotted = false
                        )
                    }
                    ticksInCurrentMeasure = ticksPerMeasure
                    break
                }
            }
            
            // Fill remaining space with rests if needed
            if (ticksInCurrentMeasure < ticksPerMeasure && noteIndex >= notes.size) {
                val restTicks = ticksPerMeasure - ticksInCurrentMeasure
                val restParts = decomposeToStandard(restTicks, stds)
                for (sd in restParts) {
                    val restNote = MusicalNote(
                        pitches = emptyList(),
                        durationTicks = sd.ticks,
                        type = sd.typeName,
                        dotted = sd.dotted,
                        isRest = true
                    )
                    appendNote(sb, restNote, 0, divisions, result.keySignature.fifths,
                        tieStart = false, tieStop = false, isChordNote = false)
                }
                ticksInCurrentMeasure = ticksPerMeasure
            }
            
            sb.appendLine("""    </measure>""")
            measureNumber++
        }
    }

    /**
     * Append the attributes element (divisions, key, time, clef).
     * Single staff — alphaTab handles Score+Tab rendering via staveProfile.
     */
    private fun appendAttributes(sb: StringBuilder, result: TranscriptionResult) {
        sb.appendLine("""      <attributes>""")
        sb.appendLine("""        <divisions>${result.divisions}</divisions>""")
        sb.appendLine("""        <key>""")
        sb.appendLine("""          <fifths>${result.keySignature.fifths}</fifths>""")
        if (result.keySignature.mode == "minor") {
            sb.appendLine("""          <mode>minor</mode>""")
        } else {
            sb.appendLine("""          <mode>major</mode>""")
        }
        sb.appendLine("""        </key>""")
        sb.appendLine("""        <time>""")
        sb.appendLine("""          <beats>${result.timeSignature.beats}</beats>""")
        sb.appendLine("""          <beat-type>${result.timeSignature.beatType}</beat-type>""")
        sb.appendLine("""        </time>""")
        val clefSign = result.instrument?.clefSign ?: "G"
        val clefLine = result.instrument?.clefLine ?: 2
        val clefOctaveChange = result.instrument?.clefOctaveChange ?: 0
        sb.appendLine("""        <clef>""")
        sb.appendLine("""          <sign>$clefSign</sign>""")
        sb.appendLine("""          <line>$clefLine</line>""")
        if (clefOctaveChange != 0) {
            sb.appendLine("""          <clef-octave-change>$clefOctaveChange</clef-octave-change>""")
        }
        sb.appendLine("""        </clef>""")
        // Guitar tab: <staff-lines>6 + <staff-tuning> tells alphaTab to show
        // the tablature staff.  The scoreLoaded handler in preview.html then
        // re-enables standard notation and resets standardNotationLineCount=5
        // so both staves render correctly (ScoreTab staveProfile).
        sb.appendLine("""        <staff-details>""")
        sb.appendLine("""          <staff-lines>6</staff-lines>""")
        // Standard guitar tuning low→high: E2 A2 D3 G3 B3 E4
        sb.appendLine("""          <staff-tuning line="1">""")
        sb.appendLine("""            <tuning-step>E</tuning-step>""")
        sb.appendLine("""            <tuning-octave>2</tuning-octave>""")
        sb.appendLine("""          </staff-tuning>""")
        sb.appendLine("""          <staff-tuning line="2">""")
        sb.appendLine("""            <tuning-step>A</tuning-step>""")
        sb.appendLine("""            <tuning-octave>2</tuning-octave>""")
        sb.appendLine("""          </staff-tuning>""")
        sb.appendLine("""          <staff-tuning line="3">""")
        sb.appendLine("""            <tuning-step>D</tuning-step>""")
        sb.appendLine("""            <tuning-octave>3</tuning-octave>""")
        sb.appendLine("""          </staff-tuning>""")
        sb.appendLine("""          <staff-tuning line="4">""")
        sb.appendLine("""            <tuning-step>G</tuning-step>""")
        sb.appendLine("""            <tuning-octave>3</tuning-octave>""")
        sb.appendLine("""          </staff-tuning>""")
        sb.appendLine("""          <staff-tuning line="5">""")
        sb.appendLine("""            <tuning-step>B</tuning-step>""")
        sb.appendLine("""            <tuning-octave>3</tuning-octave>""")
        sb.appendLine("""          </staff-tuning>""")
        sb.appendLine("""          <staff-tuning line="6">""")
        sb.appendLine("""            <tuning-step>E</tuning-step>""")
        sb.appendLine("""            <tuning-octave>4</tuning-octave>""")
        sb.appendLine("""          </staff-tuning>""")
        sb.appendLine("""        </staff-details>""")
        sb.appendLine("""      </attributes>""")
    }

    /**
     * Append tempo direction (metronome marking).
     */
    private fun appendTempoDirection(sb: StringBuilder, tempoBpm: Int) {
        sb.appendLine("""      <direction placement="above">""")
        sb.appendLine("""        <direction-type>""")
        sb.appendLine("""          <metronome>""")
        sb.appendLine("""            <beat-unit>quarter</beat-unit>""")
        sb.appendLine("""            <per-minute>$tempoBpm</per-minute>""")
        sb.appendLine("""          </metronome>""")
        sb.appendLine("""        </direction-type>""")
        sb.appendLine("""      </direction>""")
    }

    /**
     * Append a single note or rest element with guitar technical notation.
     * alphaTab uses string/fret data to render the TAB staff correctly.
     */
    private fun appendNote(
        sb: StringBuilder,
        note: MusicalNote,
        pitchIndex: Int,
        divisions: Int,
        keyFifths: Int,
        tieStart: Boolean = false,
        tieStop: Boolean = false,
        isChordNote: Boolean = false
    ) {
        sb.appendLine("""      <note>""")
        
        // Chord indicator (must come before <pitch>)
        if (isChordNote) {
            sb.appendLine("""        <chord/>""")
        }
        
        val pitch = note.pitches.getOrElse(pitchIndex) { 0 }
        
        if (note.isRest) {
            sb.appendLine("""        <rest/>""")
        } else {
            val (step, alter, octave) = PitchUtils.midiToMusicXmlPitch(pitch, keyFifths)
            sb.appendLine("""        <pitch>""")
            sb.appendLine("""          <step>$step</step>""")
            if (alter != 0) {
                sb.appendLine("""          <alter>$alter</alter>""")
            }
            sb.appendLine("""          <octave>$octave</octave>""")
            sb.appendLine("""        </pitch>""")
        }
        
        sb.appendLine("""        <duration>${note.durationTicks}</duration>""")
        
        // Tie elements
        if (tieStart || note.tiedToNext) {
            sb.appendLine("""        <tie type="start"/>""")
        }
        if (tieStop) {
            sb.appendLine("""        <tie type="stop"/>""")
        }
        
        // Voice — required by alphaTab's MusicXML parser to initialise voice arrays
        sb.appendLine("""        <voice>1</voice>""")
        
        sb.appendLine("""        <type>${note.type}</type>""")
        
        if (note.dotted) {
            sb.appendLine("""        <dot/>""")
        }
        
        // Notations for ties and guitar tablature
        val tabPos = note.tabPositions.getOrNull(pitchIndex)
        val hasTabSingle = tabPos != null && !note.isRest
        val hasTie = tieStart || tieStop || note.tiedToNext

        if (hasTie || hasTabSingle) {
            sb.appendLine("""        <notations>""")
            if (tieStart || note.tiedToNext) {
                sb.appendLine("""          <tied type="start"/>""")
            }
            if (tieStop) {
                sb.appendLine("""          <tied type="stop"/>""")
            }
            // Guitar tablature: <string> and <fret> in <technical>
            if (hasTabSingle) {
                sb.appendLine("""          <technical>""")
                // guitarString is 0-based (0=Low E), MusicXML <string> is 1=High E, 6=Low E
                val stringNum = 6 - tabPos!!.first
                sb.appendLine("""            <string>$stringNum</string>""")
                sb.appendLine("""            <fret>${tabPos.second}</fret>""")
                sb.appendLine("""          </technical>""")
            }
            sb.appendLine("""        </notations>""")
        }
        
        sb.appendLine("""      </note>""")
    }

    /**
     * Append a whole rest for an empty measure.
     */
    private fun appendWholeRest(sb: StringBuilder, result: TranscriptionResult) {
        val totalTicks = result.timeSignature.beats * result.divisions
        val restType = ticksToType(totalTicks, result.divisions)
        sb.appendLine("""      <note>""")
        sb.appendLine("""        <rest measure="yes"/>""")
        sb.appendLine("""        <duration>$totalTicks</duration>""")
        sb.appendLine("""        <type>$restType</type>""")
        sb.appendLine("""      </note>""")
    }

    /**
     * Convert duration ticks to note type name.
     * Returns the largest standard type whose base duration fits within [ticks].
     */
    private fun ticksToType(ticks: Int, divisions: Int): String {
        val ratio = ticks.toDouble() / divisions
        return when {
            ratio >= 4.0 -> "whole"
            ratio >= 3.0 -> "half"      // dotted half = 3.0
            ratio >= 2.0 -> "half"
            ratio >= 1.5 -> "quarter"   // dotted quarter = 1.5
            ratio >= 1.0 -> "quarter"
            ratio >= 0.75 -> "eighth"   // dotted eighth = 0.75
            ratio >= 0.5 -> "eighth"
            else -> "16th"
        }
    }
}
