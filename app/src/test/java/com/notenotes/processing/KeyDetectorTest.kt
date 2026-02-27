package com.notenotes.processing

import com.notenotes.model.KeySignature
import org.junit.Assert.*
import org.junit.Test

class KeyDetectorTest {

    private val detector = KeyDetector()

    @Test
    fun `T3_1 C major scale detected as C major`() {
        val notes = listOf(60, 62, 64, 65, 67, 69, 71)
        val result = detector.detectKey(notes)
        assertEquals("C", result.root)
        assertEquals("major", result.mode)
        assertEquals(0, result.fifths)
    }

    @Test
    fun `T3_2 G major scale detected as G major`() {
        val notes = listOf(67, 69, 71, 72, 74, 76, 78)
        val result = detector.detectKey(notes)
        assertEquals("G", result.root)
        assertEquals("major", result.mode)
        assertEquals(1, result.fifths)
    }

    @Test
    fun `T3_3 F major scale detected as F major`() {
        val notes = listOf(65, 67, 69, 70, 72, 74, 76)
        val result = detector.detectKey(notes)
        assertEquals("F", result.root)
        assertEquals("major", result.mode)
        assertEquals(-1, result.fifths)
    }

    @Test
    fun `T3_4 A minor scale with repeated A detected as A minor`() {
        // A B C D E F G with extra A notes to bias toward A minor
        val notes = listOf(
            69, 71, 60, 62, 64, 65, 67,  // A B C D E F G
            69, 69, 69, 69,               // extra A's
            57, 57,                        // lower A's
            81                             // higher A
        )
        val result = detector.detectKey(notes)
        assertEquals("A", result.root)
        assertEquals("minor", result.mode)
        assertEquals(0, result.fifths)
    }

    @Test
    fun `T3_5 D major scale detected as D major`() {
        val notes = listOf(62, 64, 66, 67, 69, 71, 73)
        val result = detector.detectKey(notes)
        assertEquals("D", result.root)
        assertEquals("major", result.mode)
        assertEquals(2, result.fifths)
    }

    @Test
    fun `T3_6 Eb major scale detected as Eb major`() {
        val notes = listOf(63, 65, 67, 68, 70, 72, 74)
        val result = detector.detectKey(notes)
        assertEquals("Eb", result.root)
        assertEquals("major", result.mode)
        assertEquals(-3, result.fifths)
    }

    @Test
    fun `T3_7 sparse input C E G detected as C major`() {
        val notes = listOf(60, 64, 67)
        val result = detector.detectKey(notes)
        assertEquals("C", result.root)
        assertEquals("major", result.mode)
        assertEquals(0, result.fifths)
    }

    @Test
    fun `T3_8 chromatic 12 notes returns valid key without crashing`() {
        val notes = (60..71).toList()
        val result = detector.detectKey(notes)
        assertNotNull(result)
        assertNotNull(result.root)
        assertTrue(result.mode == "major" || result.mode == "minor")
    }

    @Test
    fun `T3_9 Bb C D F G detected as Bb major or F major`() {
        val notes = listOf(70, 72, 74, 77, 79)
        val result = detector.detectKey(notes)
        assertTrue(
            "Expected fifths to be -2 (Bb major) or -1 (F major), but was ${result.fifths}",
            result.fifths == -2 || result.fifths == -1
        )
    }

    @Test
    fun `T3_10 G major scale repeated 3 times detected as G major`() {
        val gMajorScale = listOf(67, 69, 71, 72, 74, 76, 78)
        val notes = gMajorScale + gMajorScale + gMajorScale
        val result = detector.detectKey(notes)
        assertEquals("G", result.root)
        assertEquals("major", result.mode)
        assertEquals(1, result.fifths)
    }
}
