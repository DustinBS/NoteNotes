package com.notenotes.audio

import java.lang.ref.WeakReference

/**
 * Lightweight registry of active AudioPlayer instances so we can coordinate
 * pausing/stopping across different screens/viewmodels when necessary.
 */
object PlaybackRegistry {
    private val players = mutableListOf<WeakReference<AudioPlayer>>()

    @Synchronized
    fun register(player: AudioPlayer) {
        // Remove cleared refs and any existing ref to this player
        players.removeAll { it.get() == null || it.get() === player }
        players.add(WeakReference(player))
    }

    /**
     * Remove any references to the specified player from the registry.
     * This is safe to call multiple times and helps keep the weak-ref list tidy.
     */
    @Synchronized
    fun unregister(player: AudioPlayer) {
        players.removeAll { it.get() == null || it.get() === player }
    }

    @Synchronized
    fun pauseAllExcept(except: AudioPlayer? = null) {
        val iter = players.iterator()
        while (iter.hasNext()) {
            val ref = iter.next()
            val p = ref.get()
            if (p == null) {
                iter.remove()
            } else if (p !== except) {
                try { p.pause() } catch (_: Exception) {}
            }
        }
    }

    @Synchronized
    fun stopAllExcept(except: AudioPlayer? = null) {
        val iter = players.iterator()
        while (iter.hasNext()) {
            val ref = iter.next()
            val p = ref.get()
            if (p == null) {
                iter.remove()
            } else if (p !== except) {
                try { p.stop() } catch (_: Exception) {}
            }
        }
    }
}
