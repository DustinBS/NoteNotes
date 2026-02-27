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

    /**
     * Distribute notes into measures based on time signature.
     */
    private fun appendMeasures(sb: StringBuilder, result: TranscriptionResult) {
        val divisions = result.divisions
        val beatsPerMeasure = result.timeSignature.beats
        val beatType = result.timeSignature.beatType
        
        // Ticks per measure: for 4/4 with divisions=4, that's 4*4=16 ticks
        // For 3/4 with divisions=4, that's 3*4=12 ticks
        // For 6/8 with divisions=4, divisions is per quarter, 
        //   so 6/8 = 6 eighth notes = 3 quarter notes worth = 12 ticks
        val ticksPerMeasure = when (beatType) {
            8 -> beatsPerMeasure * divisions / 2   // eighth note = half a quarter
            2 -> beatsPerMeasure * divisions * 2   // half note = two quarters
            else -> beatsPerMeasure * divisions     // quarter note = 1 division unit
        }
        
        var measureNumber = 1
        var ticksInCurrentMeasure = 0
        var noteIndex = 0
        var pendingTieStop = false  // true when the next note is a tied continuation
        // Use mutable list so cross-barline splits can replace the current note with remainder
        val notes = result.notes.toMutableList()
        
        while (noteIndex < notes.size) {
            sb.appendLine("""    <measure number="$measureNumber">""")
            
            // Attributes on first measure
            if (measureNumber == 1) {
                appendAttributes(sb, result)
                appendTempoDirection(sb, result.tempoBpm)
            }
            
            ticksInCurrentMeasure = 0
            
            while (noteIndex < notes.size && ticksInCurrentMeasure < ticksPerMeasure) {
                val note = notes[noteIndex]
                val remainingTicks = ticksPerMeasure - ticksInCurrentMeasure
                
                if (note.durationTicks <= remainingTicks) {
                    // Note fits in current measure
                    appendNote(sb, note, divisions, result.keySignature.fifths, tieStop = pendingTieStop)
                    pendingTieStop = false
                    ticksInCurrentMeasure += note.durationTicks
                    noteIndex++
                } else {
                    // Note needs to be split across measures
                    val remainingDuration = note.durationTicks - remainingTicks
                    
                    if (!note.isRest) {
                        // First part: fills remaining space in this measure (tie start)
                        val firstPart = note.copy(
                            durationTicks = remainingTicks,
                            type = ticksToType(remainingTicks, divisions),
                            dotted = false,
                            tiedToNext = true
                        )
                        appendNote(sb, firstPart, divisions, result.keySignature.fifths, tieStart = true, tieStop = pendingTieStop)
                        pendingTieStop = false
                        
                        // Replace current note with remainder for next measure (tie stop)
                        notes[noteIndex] = note.copy(
                            durationTicks = remainingDuration,
                            type = ticksToType(remainingDuration, divisions),
                            dotted = false,
                            tiedToNext = false
                        )
                        pendingTieStop = true  // next iteration picks up the tie stop
                    } else {
                        // Rest: fill remaining and carry over the rest
                        val restPart = note.copy(
                            durationTicks = remainingTicks,
                            type = ticksToType(remainingTicks, divisions)
                        )
                        appendNote(sb, restPart, divisions, result.keySignature.fifths)
                        
                        // Replace current note with remainder rest for next measure
                        notes[noteIndex] = note.copy(
                            durationTicks = remainingDuration,
                            type = ticksToType(remainingDuration, divisions)
                        )
                    }
                    // Do NOT increment noteIndex — the remainder stays at current index
                    // for the next measure iteration
                    ticksInCurrentMeasure = ticksPerMeasure
                    break
                }
            }
            
            // Fill remaining space with rests if needed
            if (ticksInCurrentMeasure < ticksPerMeasure && noteIndex >= notes.size) {
                val restTicks = ticksPerMeasure - ticksInCurrentMeasure
                val restNote = MusicalNote(
                    midiPitch = 0,
                    durationTicks = restTicks,
                    type = ticksToType(restTicks, divisions),
                    isRest = true
                )
                appendNote(sb, restNote, divisions, result.keySignature.fifths)
            }
            
            sb.appendLine("""    </measure>""")
            measureNumber++
        }
    }

    /**
     * Append the attributes element (divisions, key, time, clef).
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
        sb.appendLine("""        <clef>""")
        sb.appendLine("""          <sign>G</sign>""")
        sb.appendLine("""          <line>2</line>""")
        sb.appendLine("""        </clef>""")
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
     * Append a single note or rest element.
     */
    private fun appendNote(
        sb: StringBuilder,
        note: MusicalNote,
        divisions: Int,
        keyFifths: Int,
        tieStart: Boolean = false,
        tieStop: Boolean = false
    ) {
        sb.appendLine("""      <note>""")
        
        if (note.isRest) {
            sb.appendLine("""        <rest/>""")
        } else {
            val (step, alter, octave) = PitchUtils.midiToMusicXmlPitch(note.midiPitch, keyFifths)
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
        
        sb.appendLine("""        <type>${note.type}</type>""")
        
        if (note.dotted) {
            sb.appendLine("""        <dot/>""")
        }
        
        // Notations for ties
        if (tieStart || tieStop || note.tiedToNext) {
            sb.appendLine("""        <notations>""")
            if (tieStart || note.tiedToNext) {
                sb.appendLine("""          <tied type="start"/>""")
            }
            if (tieStop) {
                sb.appendLine("""          <tied type="stop"/>""")
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
        sb.appendLine("""      <note>""")
        sb.appendLine("""        <rest measure="yes"/>""")
        sb.appendLine("""        <duration>$totalTicks</duration>""")
        // For measure="yes" rests, the type should match the actual time signature
        val restType = ticksToType(totalTicks, result.divisions)
        sb.appendLine("""        <type>$restType</type>""")
        sb.appendLine("""      </note>""")
    }

    /**
     * Convert duration ticks to note type name.
     */
    private fun ticksToType(ticks: Int, divisions: Int): String {
        // divisions = ticks per quarter note
        val ratio = ticks.toDouble() / divisions
        return when {
            ratio >= 4.0 -> "whole"
            ratio >= 2.0 -> "half"
            ratio >= 1.0 -> "quarter"
            ratio >= 0.5 -> "eighth"
            else -> "16th"
        }
    }
}
