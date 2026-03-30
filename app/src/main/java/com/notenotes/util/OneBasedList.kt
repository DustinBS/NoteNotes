package com.notenotes.util

/**
 * A simple 1-based list wrapper for human-facing indexes (1..size).
 * Use `get(humanIndex)` and `set(humanIndex, value)` where `humanIndex` is 1..size.
 */
class OneBasedList<T> private constructor(private val backing: MutableList<T>) {

    companion object {
        fun <T> from(list: List<T>): OneBasedList<T> = OneBasedList(list.toMutableList())

        fun <T> ofSize(size: Int, init: (Int) -> T): OneBasedList<T> {
            val backing = MutableList(size) { i -> init(i + 1) }
            return OneBasedList(backing)
        }
    }

    fun get(humanIndex: Int): T {
        val i = humanIndex - 1
        if (i !in backing.indices) throw IndexOutOfBoundsException("Index out of range: $humanIndex")
        return backing[i]
    }

    fun set(humanIndex: Int, value: T) {
        val i = humanIndex - 1
        if (i !in backing.indices) throw IndexOutOfBoundsException("Index out of range: $humanIndex")
        backing[i] = value
    }

    val sizeHuman: Int
        get() = backing.size

    fun toList(): List<T> = backing.toList()

    fun asMutableList(): MutableList<T> = backing
}

fun <T> List<T>.toOneBased(): OneBasedList<T> = OneBasedList.from(this)
