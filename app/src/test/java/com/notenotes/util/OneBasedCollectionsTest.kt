package com.notenotes.util

import org.junit.Assert.*
import org.junit.Test

class OneBasedCollectionsTest {

    @Test
    fun oneBasedList_basic_get_set() {
        val ob = OneBasedList.from(listOf("E", "A", "D", "G", "B", "E"))
        assertEquals(6, ob.sizeHuman)
        assertEquals("E", ob.get(1))
        assertEquals("E", ob.get(6))
        ob.set(3, "D-mod")
        assertEquals("D-mod", ob.get(3))
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun oneBasedList_outOfRange_get() {
        val ob = OneBasedList.from(listOf(1, 2, 3, 4, 5, 6))
        ob.get(0)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun oneBasedIntArray_outOfRange_set() {
        val a = OneBasedIntArray.ofSize(6)
        a[1] = 10
        assertEquals(10, a[1])
        a[0] = 1 // should throw
    }

    @Test
    fun oneBasedIntArray_iteration_and_toArray() {
        val a = OneBasedIntArray.from(intArrayOf(10, 20, 30, 40, 50, 60))
        assertEquals(6, a.sizeHuman)
        assertEquals(10, a[1])
        assertEquals(60, a[6])
        val arr = a.toIntArray()
        assertArrayEquals(intArrayOf(10, 20, 30, 40, 50, 60), arr)
    }
}
