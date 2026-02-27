package com.notenotes.export

import com.notenotes.model.KeySignature
import com.notenotes.model.MusicalNote
import com.notenotes.model.TimeSignature
import com.notenotes.model.TranscriptionResult
import org.junit.Assert.*
import org.junit.Test

class MidiWriterTest {

    private val writer = MidiWriter()

    private fun makeNote(
        midi: Int,
        type: String = "quarter",
        ticks: Int = 480,
        velocity: Int = 80,
        isRest: Boolean = false
    ): MusicalNote {
        return MusicalNote(
            midiPitch = midi,
            durationTicks = ticks,
            type = type,
            dotted = false,
            isRest = isRest,
            tiedToNext = false,
            velocity = velocity
        )
    }

    private fun makeResult(
        notes: List<MusicalNote>,
        key: KeySignature = KeySignature.C_MAJOR,
        time: TimeSignature = TimeSignature.FOUR_FOUR,
        tempo: Int = 120
    ): TranscriptionResult {
        return TranscriptionResult(
            notes = notes,
            keySignature = key,
            timeSignature = time,
            tempoBpm = tempo,
            divisions = 480
        )
    }

    /** T6.1: Single C4 quarter note → byte array starts with "MThd", length > 14 */
    @Test
    fun singleC4QuarterNote_producesMidiWithHeader() {
        val result = makeResult(notes = listOf(makeNote(midi = 60)))
        val midi = writer.generateMidi(result)

        assertTrue("MIDI output should be longer than 14 bytes", midi.size > 14)
        val header = String(midi.sliceArray(0..3), Charsets.US_ASCII)
        assertEquals("MThd", header)
    }

    /** T6.2: C major scale (8 notes, MIDI 60-72) → valid MIDI, contains note-on events (0x90) */
    @Test
    fun cMajorScale_containsNoteOnEvents() {
        val pitches = listOf(60, 62, 64, 65, 67, 69, 71, 72)
        val notes = pitches.map { makeNote(midi = it) }
        val result = makeResult(notes = notes)
        val midi = writer.generateMidi(result)

        val header = String(midi.sliceArray(0..3), Charsets.US_ASCII)
        assertEquals("MThd", header)

        // 0x90 is note-on for channel 0
        val containsNoteOn = midi.any { it == 0x90.toByte() }
        assertTrue("MIDI should contain note-on events (0x90)", containsNoteOn)
    }

    /** T6.3: Note with rest → result is non-empty and valid MIDI header */
    @Test
    fun noteWithRest_producesValidMidi() {
        val notes = listOf(
            makeNote(midi = 60),
            makeNote(midi = 0, isRest = true),
            makeNote(midi = 64)
        )
        val result = makeResult(notes = notes)
        val midi = writer.generateMidi(result)

        assertTrue("MIDI output should not be empty", midi.isNotEmpty())
        val header = String(midi.sliceArray(0..3), Charsets.US_ASCII)
        assertEquals("MThd", header)
    }

    /** T6.4: Different velocities (64 and 127) → valid MIDI */
    @Test
    fun differentVelocities_producesValidMidi() {
        val notes = listOf(
            makeNote(midi = 60, velocity = 64),
            makeNote(midi = 62, velocity = 127)
        )
        val result = makeResult(notes = notes)
        val midi = writer.generateMidi(result)

        val header = String(midi.sliceArray(0..3), Charsets.US_ASCII)
        assertEquals("MThd", header)
        assertTrue("MIDI output should be longer than 14 bytes", midi.size > 14)
    }

    /** T6.5: Tempo 90 BPM → MIDI file contains tempo meta event (0xFF 0x51 0x03) */
    @Test
    fun tempo90Bpm_containsTempoMetaEvent() {
        val result = makeResult(
            notes = listOf(makeNote(midi = 60)),
            tempo = 90
        )
        val midi = writer.generateMidi(result)

        // Search for tempo meta event: FF 51 03
        var found = false
        for (i in 0 until midi.size - 2) {
            if (midi[i] == 0xFF.toByte() &&
                midi[i + 1] == 0x51.toByte() &&
                midi[i + 2] == 0x03.toByte()
            ) {
                found = true
                break
            }
        }
        assertTrue("MIDI should contain tempo meta event (FF 51 03)", found)
    }

    /** T6.6: Key signature G major → contains key sig meta event (0xFF 0x59) */
    @Test
    fun keySignatureGMajor_containsKeySignatureMetaEvent() {
        val result = makeResult(
            notes = listOf(makeNote(midi = 67)),
            key = KeySignature.G_MAJOR
        )
        val midi = writer.generateMidi(result)

        // Search for key signature meta event: FF 59
        var found = false
        for (i in 0 until midi.size - 1) {
            if (midi[i] == 0xFF.toByte() && midi[i + 1] == 0x59.toByte()) {
                found = true
                break
            }
        }
        assertTrue("MIDI should contain key signature meta event (FF 59)", found)
    }

    /** T6.7: Round-trip: generate MIDI, check header bytes "MThd" and track header "MTrk" */
    @Test
    fun roundTrip_containsHeaderAndTrackChunks() {
        val notes = listOf(
            makeNote(midi = 60),
            makeNote(midi = 64),
            makeNote(midi = 67)
        )
        val result = makeResult(notes = notes)
        val midi = writer.generateMidi(result)

        val header = String(midi.sliceArray(0..3), Charsets.US_ASCII)
        assertEquals("MThd", header)

        // Find "MTrk" in the byte array
        val midiString = String(midi, Charsets.US_ASCII)
        assertTrue("MIDI should contain track header 'MTrk'", midiString.contains("MTrk"))
    }
}
