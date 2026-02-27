package com.notenotes.processing

import com.notenotes.model.PitchDetectionResult
import com.notenotes.util.PitchUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AlgorithmComparisonTest {

    data class TestCase(
        val file: String,
        val expectedMidi: Int,
        val expectedName: String,
        val description: String
    )

    companion object {
        val SINGLE_NOTE_TESTS = listOf(
            TestCase("low_e2.wav", 40, "E2", "Low E string"),
            TestCase("a2.wav", 45, "A2", "A string"),
            TestCase("d3.wav", 50, "D3", "D string"),
            TestCase("g3.wav", 55, "G3", "G string"),
            TestCase("b3.wav", 59, "B3", "B string"),
            TestCase("e4.wav", 64, "E4", "High E string")
        )
    }

    private fun loadWavSamples(resourceName: String): ShortArray {
        val stream: InputStream = javaClass.classLoader!!.getResourceAsStream(resourceName)
            ?: throw IllegalArgumentException("Resource not found: $resourceName")
        val bytes = stream.readBytes()
        stream.close()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4); bb.get(riff)
        bb.getInt()
        val wave = ByteArray(4); bb.get(wave)
        var dataSize = 0
        while (bb.hasRemaining()) {
            val chunkId = ByteArray(4); bb.get(chunkId)
            val chunkSize = bb.getInt()
            if (String(chunkId) == "data") { dataSize = chunkSize; break }
            else bb.position(bb.position() + chunkSize)
        }
        val numSamples = dataSize / 2
        return ShortArray(numSamples) { bb.getShort() }
    }

    private fun getDominantPitch(frames: List<PitchDetectionResult>): Triple<Int, Double, Int> {
        val pitched = frames.filter { it.isPitched }
        if (pitched.isEmpty()) return Triple(-1, 0.0, 0)
        val midiCounts = mutableMapOf<Int, Int>()
        val midiConfidences = mutableMapOf<Int, MutableList<Double>>()
        for (frame in pitched) {
            val midi = PitchUtils.frequencyToMidi(frame.frequencyHz)
            midiCounts[midi] = (midiCounts[midi] ?: 0) + 1
            midiConfidences.getOrPut(midi) { mutableListOf() }.add(frame.confidence)
        }
        val dominantMidi = midiCounts.maxByOrNull { it.value }?.key ?: -1
        val avgConfidence = midiConfidences[dominantMidi]?.average() ?: 0.0
        return Triple(dominantMidi, avgConfidence, pitched.size)
    }

    @Test
    fun compareRawPitchDetection_allAlgorithms() {
        println("\n" + "=".repeat(90))
        println("  RAW PITCH DETECTION COMPARISON")
        println("=".repeat(90))
        println(String.format("%-12s | %-8s | %-15s | %-15s | %-15s", "File", "Expected", "YIN", "MPM", "HPS"))
        println("-".repeat(90))

        val yin = YinPitchDetector()
        val mpm = McLeodPitchDetector()
        val hps = HpsPitchDetector()
        var yinCorrect = 0; var mpmCorrect = 0; var hpsCorrect = 0

        for (tc in SINGLE_NOTE_TESTS) {
            val samples = loadWavSamples(tc.file)
            val (yinMidi, yinConf, _) = getDominantPitch(yin.detectPitches(samples))
            val (mpmMidi, mpmConf, _) = getDominantPitch(mpm.detectPitches(samples))
            val (hpsMidi, hpsConf, _) = getDominantPitch(hps.detectPitches(samples))
            val yinName = if (yinMidi >= 0) PitchUtils.midiToNoteName(yinMidi) else "none"
            val mpmName = if (mpmMidi >= 0) PitchUtils.midiToNoteName(mpmMidi) else "none"
            val hpsName = if (hpsMidi >= 0) PitchUtils.midiToNoteName(hpsMidi) else "none"
            val yinOk = kotlin.math.abs(yinMidi - tc.expectedMidi) <= 1
            val mpmOk = kotlin.math.abs(mpmMidi - tc.expectedMidi) <= 1
            val hpsOk = kotlin.math.abs(hpsMidi - tc.expectedMidi) <= 1
            if (yinOk) yinCorrect++
            if (mpmOk) mpmCorrect++
            if (hpsOk) hpsCorrect++
            println(String.format("%-12s | %-8s | %s %-5s c=%.2f | %s %-5s c=%.2f | %s %-5s c=%.2f",
                tc.file.removeSuffix(".wav"), tc.expectedName,
                if (yinOk) "Y" else "N", yinName, yinConf,
                if (mpmOk) "Y" else "N", mpmName, mpmConf,
                if (hpsOk) "Y" else "N", hpsName, hpsConf))
        }
        println("-".repeat(90))
        println(String.format("%-12s | %-8s | %-15s | %-15s | %-15s",
            "SCORE", "${SINGLE_NOTE_TESTS.size} tests",
            "$yinCorrect/${SINGLE_NOTE_TESTS.size}", "$mpmCorrect/${SINGLE_NOTE_TESTS.size}", "$hpsCorrect/${SINGLE_NOTE_TESTS.size}"))
        println("=".repeat(90))
        val bestScore = maxOf(yinCorrect, mpmCorrect, hpsCorrect)
        assertTrue("All algorithms performed poorly. Best was $bestScore/${SINGLE_NOTE_TESTS.size}", bestScore >= 3)
    }

    @Test
    fun consensusPitchDetection_majorityVote() {
        println("\n" + "=".repeat(70))
        println("  CONSENSUS PITCH DETECTION (Majority Vote)")
        println("=".repeat(70))

        val yin = YinPitchDetector()
        val mpm = McLeodPitchDetector()
        val hps = HpsPitchDetector()
        var consensusCorrect = 0

        for (tc in SINGLE_NOTE_TESTS) {
            val samples = loadWavSamples(tc.file)
            val (yinMidi, _, _) = getDominantPitch(yin.detectPitches(samples))
            val (mpmMidi, _, _) = getDominantPitch(mpm.detectPitches(samples))
            val (hpsMidi, _, _) = getDominantPitch(hps.detectPitches(samples))
            val candidates = listOf(yinMidi, mpmMidi, hpsMidi).filter { it >= 0 }
            val consensusMidi = findConsensus(candidates)
            val consensusName = if (consensusMidi >= 0) PitchUtils.midiToNoteName(consensusMidi) else "none"
            val isCorrect = kotlin.math.abs(consensusMidi - tc.expectedMidi) <= 1
            if (isCorrect) consensusCorrect++
            println("  ${if (isCorrect) "Y" else "N"} ${tc.file.removeSuffix(".wav")}: expected=${tc.expectedName} consensus=$consensusName (YIN=${fmtMidi(yinMidi)}, MPM=${fmtMidi(mpmMidi)}, HPS=${fmtMidi(hpsMidi)})")
        }
        println("-".repeat(70))
        println("  CONSENSUS SCORE: $consensusCorrect/${SINGLE_NOTE_TESTS.size}")
        println("=".repeat(70))
        assertTrue("Consensus should get at least 3/6 correct but got $consensusCorrect", consensusCorrect >= 3)
    }

    private fun findConsensus(candidates: List<Int>): Int {
        if (candidates.isEmpty()) return -1
        if (candidates.size == 1) return candidates[0]
        val groups = mutableListOf<MutableList<Int>>()
        for (c in candidates) {
            val matchingGroup = groups.find { group -> group.any { kotlin.math.abs(it - c) <= 1 } }
            if (matchingGroup != null) matchingGroup.add(c)
            else groups.add(mutableListOf(c))
        }
        val largestGroup = groups.maxByOrNull { it.size } ?: return candidates[0]
        return largestGroup.sorted()[largestGroup.size / 2]
    }

    private fun fmtMidi(midi: Int): String = if (midi >= 0) PitchUtils.midiToNoteName(midi) else "none"

    @Test
    fun mpmDetector_lowE2_detectsPitch() {
        val samples = loadWavSamples("low_e2.wav")
        val mpm = McLeodPitchDetector()
        val frames = mpm.detectPitches(samples)
        val pitched = frames.filter { it.isPitched }
        assertTrue("MPM should detect pitched frames for low E2", pitched.isNotEmpty())
        val (midi, _, _) = getDominantPitch(frames)
        val name = if (midi >= 0) PitchUtils.midiToNoteName(midi) else "none"
        println("MPM low_e2.wav: dominant pitch = $name (MIDI $midi), ${pitched.size} pitched frames")
    }

    @Test
    fun hpsDetector_lowE2_detectsPitch() {
        val samples = loadWavSamples("low_e2.wav")
        val hps = HpsPitchDetector()
        val frames = hps.detectPitches(samples)
        val pitched = frames.filter { it.isPitched }
        assertTrue("HPS should detect pitched frames for low E2", pitched.isNotEmpty())
        val (midi, _, _) = getDominantPitch(frames)
        val name = if (midi >= 0) PitchUtils.midiToNoteName(midi) else "none"
        println("HPS low_e2.wav: dominant pitch = $name (MIDI $midi), ${pitched.size} pitched frames")
    }
}