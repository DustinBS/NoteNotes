package com.notenotes.processing

import com.notenotes.model.MusicalNote
import com.notenotes.model.DetectedNote
import com.notenotes.model.TimeSignature
import com.notenotes.util.PitchUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quantizes raw detected note durations to musical note values.
 * 
 * Takes raw durations in seconds at a given tempo and snaps them to the 
 * nearest standard musical duration (whole, half, quarter, eighth, 16th)
 * including dotted variants.
 */
class RhythmQuantizer(
    private val tolerance: Double = 0.3  // 30% tolerance for snapping
) {

    /**
     * Represents a candidate musical duration for quantization.
     */
    data class DurationCandidate(
        val name: String,            // "whole", "half", "quarter", "eighth", "16th"
        val divisions: Int,          // duration in divisions (quarter = base division)
        val dotted: Boolean,
        val beatsMultiplier: Double  // multiplier relative to one beat (quarter note)
    )

    companion object {
        /**
         * All candidate durations, expressed as multiples of a quarter note.
         * divisions assumes 4 divisions per quarter.
         */
        val DURATION_CANDIDATES = listOf(
            DurationCandidate("whole", 16, false, 4.0),
            DurationCandidate("half", 12, true, 3.0),       // dotted half
            DurationCandidate("half", 8, false, 2.0),
            DurationCandidate("quarter", 6, true, 1.5),     // dotted quarter
            DurationCandidate("quarter", 4, false, 1.0),
            DurationCandidate("eighth", 3, true, 0.75),     // dotted eighth
            DurationCandidate("eighth", 2, false, 0.5),
            DurationCandidate("16th", 1, false, 0.25)
        )
    }

    /**
     * Quantize a list of detected notes into musical notes.
     * 
     * @param detectedNotes notes with onset times and durations in seconds
     * @param tempoBpm tempo in beats per minute
     * @param timeSignature time signature for measure/barline placement
     * @return list of MusicalNote with quantized durations
     */
    fun quantize(
        detectedNotes: List<DetectedNote>,
        tempoBpm: Int,
        timeSignature: TimeSignature
    ): List<MusicalNote> {
        if (detectedNotes.isEmpty()) return emptyList()

        val beatDurationSec = 60.0 / tempoBpm  // seconds per beat
        val result = mutableListOf<MusicalNote>()
        val divisionsPerQuarter = 4

        for (i in detectedNotes.indices) {
            val note = detectedNotes[i]

            // Check if there's a rest before this note
            if (i > 0) {
                val prevNote = detectedNotes[i - 1]
                val prevEnd = prevNote.onsetSeconds + prevNote.durationSeconds
                val gap = note.onsetSeconds - prevEnd

                if (gap > beatDurationSec * 0.125) {  // gap > 1/8 of a beat = rest
                    val restBeats = gap / beatDurationSec
                    val restNotes = quantizeDuration(restBeats, isRest = true)
                    result.addAll(restNotes)
                }
            }

            // Quantize the note duration
            val durationBeats = note.durationSeconds / beatDurationSec
            val quantizedNotes = quantizeDuration(durationBeats, isRest = false, midiPitch = note.midiNote)
            result.addAll(quantizedNotes)
        }

        return result
    }

    /**
     * Quantize a single duration in beats to one or more musical notes.
     * If the duration is very long, it may be split into multiple tied notes.
     */
    fun quantizeDuration(
        durationBeats: Double,
        isRest: Boolean = false,
        midiPitch: Int = 60
    ): List<MusicalNote> {
        if (durationBeats <= 0) return emptyList()

        // Find the best matching candidate
        val best = findBestDuration(durationBeats)
            ?: return listOf(createNote("quarter", 4, false, isRest, midiPitch))

        // If the duration is significantly longer than any single note value (>4 beats),
        // split into tied notes
        if (durationBeats > 4.5) {
            return splitLongDuration(durationBeats, isRest, midiPitch)
        }

        return listOf(createNote(best.name, best.divisions, best.dotted, isRest, midiPitch))
    }

    /**
     * Find the best matching duration candidate for a given number of beats.
     */
    fun findBestDuration(durationBeats: Double): DurationCandidate? {
        if (durationBeats <= 0) return null

        var bestCandidate: DurationCandidate? = null
        var bestError = Double.MAX_VALUE

        for (candidate in DURATION_CANDIDATES) {
            val error = abs(durationBeats - candidate.beatsMultiplier)
            val relativeError = error / candidate.beatsMultiplier

            if (relativeError < tolerance && error < bestError) {
                bestError = error
                bestCandidate = candidate
            }
        }

        // If no match within tolerance, find closest anyway
        if (bestCandidate == null) {
            bestCandidate = DURATION_CANDIDATES.minByOrNull { 
                abs(durationBeats - it.beatsMultiplier) 
            }
        }

        return bestCandidate
    }

    /**
     * Split a long duration into multiple tied notes.
     */
    private fun splitLongDuration(
        durationBeats: Double,
        isRest: Boolean,
        midiPitch: Int
    ): List<MusicalNote> {
        val notes = mutableListOf<MusicalNote>()
        var remaining = durationBeats

        while (remaining > 0.2) {
            // Find the largest note value that fits
            val best = DURATION_CANDIDATES
                .filter { it.beatsMultiplier <= remaining + remaining * tolerance }
                .maxByOrNull { it.beatsMultiplier }
                ?: break

            val needsTie = remaining - best.beatsMultiplier > 0.2 && !isRest
            notes.add(createNote(best.name, best.divisions, best.dotted, isRest, midiPitch, tiedToNext = needsTie))
            remaining -= best.beatsMultiplier
        }

        if (notes.isEmpty()) {
            notes.add(createNote("quarter", 4, false, isRest, midiPitch))
        }

        return notes
    }

    /**
     * Create a MusicalNote with the given properties.
     */
    private fun createNote(
        type: String,
        divisions: Int,
        dotted: Boolean,
        isRest: Boolean,
        midiPitch: Int,
        tiedToNext: Boolean = false
    ): MusicalNote {
        return MusicalNote(
            midiPitch = if (isRest) 0 else midiPitch,
            durationTicks = divisions,
            type = type,
            dotted = dotted,
            isRest = isRest,
            tiedToNext = tiedToNext
        )
    }
}
