package com.notenotes.util

/**
 * A small 1-based wrapper around an `IntArray` for human-facing indexes (1..size).
 */
class OneBasedIntArray private constructor(private val backing: IntArray) {

    companion object {
        fun ofSize(size: Int): OneBasedIntArray = OneBasedIntArray(IntArray(size))
        fun from(array: IntArray): OneBasedIntArray = OneBasedIntArray(array.copyOf())
    }

    operator fun get(humanIndex: Int): Int {
        val i = humanIndex - 1
        if (i !in backing.indices) throw IndexOutOfBoundsException("Index out of range: $humanIndex")
        return backing[i]
    }

    operator fun set(humanIndex: Int, value: Int) {
        val i = humanIndex - 1
        if (i !in backing.indices) throw IndexOutOfBoundsException("Index out of range: $humanIndex")
        backing[i] = value
    }

    val sizeHuman: Int
        get() = backing.size

    fun toIntArray(): IntArray = backing.copyOf()
}
