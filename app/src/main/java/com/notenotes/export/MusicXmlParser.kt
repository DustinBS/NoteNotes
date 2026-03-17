package com.notenotes.export

import com.notenotes.model.MusicalNote
import org.w3c.dom.Element

/**
 * Parses MusicXML into MusicalNotes, properly merging tie chains and
 * extracting metadata (instrument, key signature, time signature, tempo).
 */
class MusicXmlParser {

    /**
     * Result of parsing a MusicXML document.
     */
    data class ParseResult(
        val notes: List<MusicalNote>,
        val instrument: String? = null,     // e.g. "guitar", "piano"
        val keySignature: String? = null,   // e.g. "F minor", "C major"
        val timeSignature: String? = null,  // e.g. "4/4", "3/4"
        val tempoBpm: Int? = null,          // from <per-minute> in <metronome>
        val divisions: Int = 4
    )

    /**
     * Parse a MusicXML string, merging tie chains into single notes.
     *
     * @param xml       The MusicXML content
     * @param tempoBpm  Fallback tempo if none found in the XML
     */
    fun parse(xml: String, tempoBpm: Int = 120): ParseResult {
        val notes = mutableListOf<MusicalNote>()
        var parsedInstrument: String? = null
        var parsedKey: String? = null
        var parsedTime: String? = null
        var parsedTempo: Int? = null
        var divisions = 4

        try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xml.byteInputStream())
            doc.documentElement.normalize()

            // ── Extract metadata ──────────────────────────────────────────

            // Instrument name
            val instrNodes = doc.getElementsByTagName("instrument-name")
            if (instrNodes.length > 0) {
                parsedInstrument = instrNodes.item(0).textContent.trim().lowercase()
            }

            // Divisions
            val divNodes = doc.getElementsByTagName("divisions")
            if (divNodes.length > 0) {
                divisions = divNodes.item(0).textContent.trim().toIntOrNull() ?: 4
            }

            // Key signature
            val keyNodes = doc.getElementsByTagName("key")
            if (keyNodes.length > 0) {
                val keyEl = keyNodes.item(0) as Element
                val fifths = keyEl.getElementsByTagName("fifths").let {
                    if (it.length > 0) it.item(0).textContent.trim().toIntOrNull() ?: 0 else 0
                }
                val mode = keyEl.getElementsByTagName("mode").let {
                    if (it.length > 0) it.item(0).textContent.trim() else "major"
                }
                parsedKey = fifthsToKeyName(fifths, mode)
            }

            // Time signature
            val timeNodes = doc.getElementsByTagName("time")
            if (timeNodes.length > 0) {
                val timeEl = timeNodes.item(0) as Element
                val beats = timeEl.getElementsByTagName("beats").let {
                    if (it.length > 0) it.item(0).textContent.trim() else "4"
                }
                val beatType = timeEl.getElementsByTagName("beat-type").let {
                    if (it.length > 0) it.item(0).textContent.trim() else "4"
                }
                parsedTime = "$beats/$beatType"
            }

            // Tempo from <metronome>
            val tempoNodes = doc.getElementsByTagName("per-minute")
            if (tempoNodes.length > 0) {
                parsedTempo = tempoNodes.item(0).textContent.trim().toIntOrNull()
            }

            // ── Parse notes with tie merging ──────────────────────────────

            val effectiveTempo = parsedTempo ?: tempoBpm
            val msPerBeat = 60_000.0 / effectiveTempo
            var currentTimeMs = 0.0

            // Track active tie chains: midiPitch → index in notes list
            val activeTies = mutableMapOf<Int, Int>()

            val noteNodes = doc.getElementsByTagName("note")

            for (i in 0 until noteNodes.length) {
                val noteEl = noteNodes.item(i) as Element

                val isChord = noteEl.getElementsByTagName("chord").length > 0
                val isRest = noteEl.getElementsByTagName("rest").length > 0

                // Duration in divisions
                val durationEl = noteEl.getElementsByTagName("duration")
                val durationTicks = if (durationEl.length > 0)
                    durationEl.item(0).textContent.trim().toIntOrNull() ?: divisions
                else divisions

                // Note type
                val typeEl = noteEl.getElementsByTagName("type")
                val noteType = if (typeEl.length > 0) typeEl.item(0).textContent.trim() else "quarter"

                val dotted = noteEl.getElementsByTagName("dot").length > 0

                // Tie analysis
                val tieNodes = noteEl.getElementsByTagName("tie")
                var hasTieStart = false
                var hasTieStop = false
                for (t in 0 until tieNodes.length) {
                    val tieEl = tieNodes.item(t) as Element
                    when (tieEl.getAttribute("type")) {
                        "start" -> hasTieStart = true
                        "stop" -> hasTieStop = true
                    }
                }

                // Parse MIDI pitch
                var midiPitch = 60
                if (!isRest) {
                    val pitchNodes = noteEl.getElementsByTagName("pitch")
                    if (pitchNodes.length > 0) {
                        val pitchEl = pitchNodes.item(0) as Element
                        val step = pitchEl.getElementsByTagName("step").let {
                            if (it.length > 0) it.item(0).textContent.trim() else "C"
                        }
                        val octave = pitchEl.getElementsByTagName("octave").let {
                            if (it.length > 0) it.item(0).textContent.trim().toIntOrNull() ?: 4 else 4
                        }
                        val alter = pitchEl.getElementsByTagName("alter").let {
                            if (it.length > 0) it.item(0).textContent.trim().toIntOrNull() ?: 0 else 0
                        }
                        val stepValue = when (step) {
                            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5
                            "G" -> 7; "A" -> 9; "B" -> 11; else -> 0
                        }
                        midiPitch = (octave + 1) * 12 + stepValue + alter
                    }
                }

                // Guitar string/fret from <technical>
                var guitarString: Int? = null
                var guitarFret: Int? = null
                val techNodes = noteEl.getElementsByTagName("technical")
                if (techNodes.length > 0) {
                    val techEl = techNodes.item(0) as Element
                    val stringNodes = techEl.getElementsByTagName("string")
                    val fretNodes = techEl.getElementsByTagName("fret")
                    if (stringNodes.length > 0) {
                        // MusicXML <string> 1=High E, 6=Low E; app uses 0-based (0=Low E)
                        guitarString = stringNodes.item(0).textContent.trim().toIntOrNull()?.let { 6 - it }
                    }
                    if (fretNodes.length > 0) {
                        guitarFret = fretNodes.item(0).textContent.trim().toIntOrNull()
                    }
                }

                val durationMs = (durationTicks.toDouble() / divisions) * msPerBeat

                // ── Chord member handling ──
                if (isChord) {
                    // All chord tie continuations (stop or stop+start) are skipped —
                    // the primary note's tie chain handles duration accumulation,
                    // and chord members were added on the first appearance.
                    if (hasTieStop) continue

                    // First appearance of a chord member → merge into primary note
                    if (notes.isNotEmpty()) {
                        val primary = notes.last()
                        if (!primary.isRest) {
                            val updatedPitches = primary.pitches.toMutableList()
                            updatedPitches.add(midiPitch)
                            val updatedTabPositions = primary.tabPositions.toMutableList()

                            if (updatedTabPositions.isNotEmpty() || (guitarString != null && guitarFret != null)) {
                                while (updatedTabPositions.size < updatedPitches.size - 1) {
                                    updatedTabPositions.add(Pair(0, 0)) // Pad missing preceding tab info
                                }
                                updatedTabPositions.add(Pair(guitarString ?: 0, guitarFret ?: 0))
                            }

                            notes[notes.lastIndex] = primary.copy(
                                pitches = updatedPitches,
                                tabPositions = updatedTabPositions
                            )
                        }
                    }
                    continue
                }

                // ── Non-chord (primary voice) handling ──

                // Tie continuation: merge duration into the chain head
                if (hasTieStop) {
                    val tieIdx = activeTies[midiPitch]
                    if (tieIdx != null) {
                        val existing = notes[tieIdx]
                        val newDuration = existing.durationTicks + durationTicks
                        notes[tieIdx] = existing.copy(
                            durationTicks = newDuration,
                            type = ticksToType(newDuration, divisions),
                            dotted = ticksToDotted(newDuration, divisions)
                        )
                        if (hasTieStart) {
                            // Relay: chain continues
                        } else {
                            // Terminal: chain ends
                            activeTies.remove(midiPitch)
                        }
                        currentTimeMs += durationMs
                        continue
                    }
                    // If no active tie found (shouldn't happen with well-formed XML),
                    // fall through to create a new note
                }

                // New note event
                val tabPositions = if (guitarString != null && guitarFret != null) listOf(Pair(guitarString, guitarFret)) else emptyList()
                val note = MusicalNote(
                    pitches = if (isRest) emptyList() else listOf(midiPitch),
                    durationTicks = durationTicks,
                    type = noteType,
                    dotted = dotted,
                    isRest = isRest,
                    tiedToNext = false, // ties are internal to XML; merged notes are untied
                    tabPositions = tabPositions,
                    timePositionMs = currentTimeMs.toFloat()
                )
                notes.add(note)

                if (hasTieStart && !isRest) {
                    activeTies[midiPitch] = notes.lastIndex
                }

                currentTimeMs += durationMs
            }
        } catch (e: Exception) {
            // Log will be handled by caller
            throw e
        }

        // Strip trailing rests (auto-generated measure padding, not musical content)
        val trimmedNotes = notes.toMutableList()
        while (trimmedNotes.isNotEmpty() && trimmedNotes.last().isRest) {
            trimmedNotes.removeAt(trimmedNotes.lastIndex)
        }

        // Strip leading rests (synthetic rests inserted by MusicXmlGenerator
        // to preserve starting silence).  The first real note already has
        // timePositionMs set by the cumulative currentTimeMs, so the timing
        // survives without the rest elements.
        while (trimmedNotes.isNotEmpty() && trimmedNotes.first().isRest) {
            trimmedNotes.removeAt(0)
        }

        // Only the first note needs timePositionMs to preserve the offset.
        // Clear it from the rest so they flow dynamically with edits instead
        // of freezing their timings to this exact parse time.
        if (trimmedNotes.isNotEmpty()) {
            for (i in 1 until trimmedNotes.size) {
                if (trimmedNotes[i].timePositionMs != null) {
                    trimmedNotes[i] = trimmedNotes[i].copy(timePositionMs = null)
                }
            }
        }

        return ParseResult(
            notes = trimmedNotes,
            instrument = parsedInstrument,
            keySignature = parsedKey,
            timeSignature = parsedTime,
            tempoBpm = parsedTempo,
            divisions = divisions
        )
    }

    companion object {
        /**
         * Convert MusicXML fifths + mode to a human-readable key name.
         * fifths: -7..+7 (flats negative, sharps positive)
         */
        fun fifthsToKeyName(fifths: Int, mode: String): String {
            // Use ASCII-friendly names matching KeySignature.toString() output
            val majorKeys = arrayOf(
                "Cb major", "Gb major", "Db major", "Ab major", "Eb major", "Bb major", "F major",
                "C major",
                "G major", "D major", "A major", "E major", "B major", "F# major", "C# major"
            )
            val minorKeys = arrayOf(
                "Ab minor", "Eb minor", "Bb minor", "F minor", "C minor", "G minor", "D minor",
                "A minor",
                "E minor", "B minor", "F# minor", "C# minor", "G# minor", "D# minor", "A# minor"
            )
            val idx = fifths + 7 // shift so -7 → 0, 0 → 7, +7 → 14
            return if (mode == "minor") {
                minorKeys.getOrElse(idx) { "A minor" }
            } else {
                majorKeys.getOrElse(idx) { "C major" }
            }
        }

        /**
         * Derive the best note type name from a tick count at the given divisions.
         */
        fun ticksToType(ticks: Int, divisions: Int): String {
            return when (ticks) {
                divisions * 4 -> "whole"
                divisions * 3 -> "half"    // dotted half, but type is "half"
                divisions * 2 -> "half"
                divisions * 3 / 2 -> "quarter" // dotted quarter
                divisions -> "quarter"
                divisions * 3 / 4 -> "eighth"  // dotted eighth
                divisions / 2 -> "eighth"
                divisions / 4 -> "16th"
                else -> {
                    // Non-standard: pick the closest standard type
                    when {
                        ticks >= divisions * 4 -> "whole"
                        ticks >= divisions * 2 -> "half"
                        ticks >= divisions -> "quarter"
                        ticks >= divisions / 2 -> "eighth"
                        else -> "16th"
                    }
                }
            }
        }

        /**
         * Determine if a tick duration represents a dotted note.
         */
        fun ticksToDotted(ticks: Int, divisions: Int): Boolean {
            return ticks == divisions * 3 ||            // dotted half
                   ticks == divisions * 3 / 2 ||        // dotted quarter
                   (divisions * 3 % 4 == 0 && ticks == divisions * 3 / 4)  // dotted eighth
        }
    }
}
