package com.notenotes.audio

/**
 * Small wrappers around PlaybackRegistry to centralize safe calls
 * and reduce repeated try/catch boilerplate across the codebase.
 */
object PlaybackUtils {
    fun register(player: AudioPlayer) {
        try { PlaybackRegistry.register(player) } catch (_: Exception) {}
    }

    fun pauseOthers(player: AudioPlayer?) {
        try { PlaybackRegistry.pauseAllExcept(player) } catch (_: Exception) {}
    }

    fun pauseAll() {
        pauseOthers(null)
    }

    fun stopOthers(player: AudioPlayer?) {
        try { PlaybackRegistry.stopAllExcept(player) } catch (_: Exception) {}
    }

    fun stopAll() {
        stopOthers(null)
    }

    fun unregister(player: AudioPlayer) {
        try { PlaybackRegistry.unregister(player) } catch (_: Exception) {}
    }
}
