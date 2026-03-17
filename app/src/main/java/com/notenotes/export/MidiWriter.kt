package com.notenotes.export

import com.notenotes.model.KeySignature
import com.notenotes.model.MusicalNote
import com.notenotes.model.TranscriptionResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes Standard MIDI File (SMF) Format 0 (single track).
 * 
 * MIDI format reference: https://www.midi.org/specifications
 * 
 * <200 lines of code. No external dependencies.
 */
class MidiWriter {

    companion object {
        // MIDI constants
        private const val HEADER_CHUNK = "MThd"
        private const val TRACK_CHUNK = "MTrk"
        private const val NOTE_ON: Byte = 0x90.toByte()
        private const val NOTE_OFF: Byte = 0x80.toByte()
        private const val META_EVENT: Byte = 0xFF.toByte()
        private const val META_TEMPO: Byte = 0x51
        private const val META_TIME_SIG: Byte = 0x58
        private const val META_KEY_SIG: Byte = 0x59
        private const val META_END_TRACK: Byte = 0x2F
        private const val TICKS_PER_QUARTER = 480  // Standard resolution
    }

    /**
     * Generate MIDI file bytes from a TranscriptionResult.
     */
    fun generateMidi(result: TranscriptionResult): ByteArray {
        val output = ByteArrayOutputStream()

        // Header chunk
        writeHeaderChunk(output)

        // Track chunk
        val trackData = buildTrackData(result)
        writeTrackChunk(output, trackData)

        return output.toByteArray()
    }

    /**
     * Write MIDI to a file.
     */
    fun writeToFile(result: TranscriptionResult, file: File) {
        val bytes = generateMidi(result)
        FileOutputStream(file).use { it.write(bytes) }
    }

    /**
     * Write the MIDI header chunk.
     * Format 0, 1 track, TICKS_PER_QUARTER ticks per quarter note.
     */
    private fun writeHeaderChunk(output: ByteArrayOutputStream) {
        // "MThd"
        output.write(HEADER_CHUNK.toByteArray())
        // Chunk length: 6 bytes
        writeInt32(output, 6)
        // Format: 0 (single track)
        writeInt16(output, 0)
        // Number of tracks: 1
        writeInt16(output, 1)
        // Ticks per quarter note
        writeInt16(output, TICKS_PER_QUARTER)
    }

    /**
     * Build the track data (events) from the transcription result.
     */
    private fun buildTrackData(result: TranscriptionResult): ByteArray {
        val track = ByteArrayOutputStream()

        // Meta events at the start (delta time = 0)

        // 1. Tempo meta event
        writeTempoEvent(track, result.tempoBpm)

        // 2. Time signature meta event
        writeTimeSignatureEvent(track, result.timeSignature.beats, result.timeSignature.beatType)

        // 3. Key signature meta event
        writeKeySignatureEvent(track, result.keySignature)

        // 4. Note events
        val ticksPerDivision = TICKS_PER_QUARTER / result.divisions
        var pendingDelta = 0

        for (note in result.notes) {
            val durationTicks = note.durationTicks * ticksPerDivision

            if (note.isRest) {
                // Rest: accumulate delta time for next note
                pendingDelta += durationTicks
                continue
            }

            val velocity = note.velocity.coerceIn(0, 127)

            if (note.pitches.isEmpty()) {
                pendingDelta += durationTicks
                continue
            }

            val primaryPitch = note.pitches[0].coerceIn(0, 127)

            // Note on (delta time includes any accumulated rests)
            writeVarLen(track, pendingDelta)
            track.write(NOTE_ON.toInt())
            track.write(primaryPitch)
            track.write(velocity)

            // Chord notes: additional note-on events with delta = 0
            if (note.pitches.size > 1) {
                for (i in 1 until note.pitches.size) {
                    writeVarLen(track, 0) // simultaneous
                    track.write(NOTE_ON.toInt())
                    track.write(note.pitches[i].coerceIn(0, 127))
                    track.write(velocity)
                }
            }

            // Note off after duration (primary note)
            writeVarLen(track, durationTicks)
            track.write(NOTE_OFF.toInt())
            track.write(primaryPitch)
            track.write(0)  // release velocity

            // Chord note-off events with delta = 0
            if (note.pitches.size > 1) {
                for (i in 1 until note.pitches.size) {
                    writeVarLen(track, 0)
                    track.write(NOTE_OFF.toInt())
                    track.write(note.pitches[i].coerceIn(0, 127))
                    track.write(0)
                }
            }

            pendingDelta = 0
        }

        // End of track
        writeVarLen(track, 0)
        track.write(META_EVENT.toInt())
        track.write(META_END_TRACK.toInt())
        track.write(0)  // length = 0

        return track.toByteArray()
    }

    /**
     * Write the track chunk with header.
     */
    private fun writeTrackChunk(output: ByteArrayOutputStream, trackData: ByteArray) {
        output.write(TRACK_CHUNK.toByteArray())
        writeInt32(output, trackData.size)
        output.write(trackData)
    }

    /**
     * Write tempo meta event. Tempo = microseconds per quarter note.
     */
    private fun writeTempoEvent(track: ByteArrayOutputStream, bpm: Int) {
        val microsecondsPerBeat = 60_000_000 / bpm

        writeVarLen(track, 0)  // delta time
        track.write(META_EVENT.toInt())
        track.write(META_TEMPO.toInt())
        track.write(3)  // data length
        track.write((microsecondsPerBeat shr 16) and 0xFF)
        track.write((microsecondsPerBeat shr 8) and 0xFF)
        track.write(microsecondsPerBeat and 0xFF)
    }

    /**
     * Write time signature meta event.
     */
    private fun writeTimeSignatureEvent(track: ByteArrayOutputStream, numerator: Int, denominator: Int) {
        // Denominator is stored as power of 2 (4 = 2^2, so store 2)
        val denomPower = when (denominator) {
            1 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            16 -> 4
            else -> 2  // default to quarter
        }

        writeVarLen(track, 0)  // delta time
        track.write(META_EVENT.toInt())
        track.write(META_TIME_SIG.toInt())
        track.write(4)  // data length
        track.write(numerator)
        track.write(denomPower)
        track.write(24)  // MIDI clocks per metronome click
        track.write(8)   // 32nd notes per MIDI quarter note
    }

    /**
     * Write key signature meta event.
     */
    private fun writeKeySignatureEvent(track: ByteArrayOutputStream, key: KeySignature) {
        val sf = key.fifths.toByte()  // sharps/flats: negative = flats, positive = sharps
        val mi: Byte = if (key.mode == "minor") 1 else 0  // 0 = major, 1 = minor

        writeVarLen(track, 0)  // delta time
        track.write(META_EVENT.toInt())
        track.write(META_KEY_SIG.toInt())
        track.write(2)  // data length
        track.write(sf.toInt())
        track.write(mi.toInt())
    }

    /**
     * Write a variable-length quantity (VLQ) used in MIDI format.
     */
    private fun writeVarLen(output: ByteArrayOutputStream, value: Int) {
        var v = value
        if (v < 0) v = 0

        if (v < 0x80) {
            output.write(v)
            return
        }

        val bytes = mutableListOf<Int>()
        bytes.add(v and 0x7F)
        v = v shr 7

        while (v > 0) {
            bytes.add((v and 0x7F) or 0x80)
            v = v shr 7
        }

        // Write in reverse order (most significant byte first)
        for (b in bytes.reversed()) {
            output.write(b)
        }
    }

    /**
     * Write a 32-bit big-endian integer.
     */
    private fun writeInt32(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    /**
     * Write a 16-bit big-endian integer.
     */
    private fun writeInt16(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }
}
