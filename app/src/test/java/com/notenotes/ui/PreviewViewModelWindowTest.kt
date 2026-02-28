package com.notenotes.ui

import com.notenotes.model.MelodyIdea
import com.notenotes.model.MusicalNote
import com.notenotes.ui.screens.PreviewViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression tests for waveform window management.
 *
 * Key invariants:
 * - moveWindowToFraction() clamps window start so it never exceeds (1 - windowFractionSize)
 * - moveWindow() shifts by delta × windowFractionSize, clamped both ends
 * - toggleWindowLock() toggles _isWindowLocked
 * - setWindowSizeSec() clamps between 1f and 30f
 * - setWindowStartFraction() clamps between 0 and 1
 * - updateWindowForPlayback() auto-scrolls unless locked
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewViewModelWindowTest {

    private lateinit var vm: PreviewViewModel

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        vm = PreviewViewModel(app)
        // 10-second audio, 5-second window → windowFractionSize = 0.5
        setField("_audioDurationMs", 10000)
        setField("_windowSizeSec", 5f)
        setField("_windowStartFraction", 0f)
    }

    // ── moveWindowToFraction ─────────────────────────────────────────────────

    @Test
    fun moveWindowToFraction_setsWindowStart() {
        vm.moveWindowToFraction(0.3f)
        assertEquals(0.3f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindowToFraction_clampsAtEnd() {
        // windowFractionSize = 5000/10000 = 0.5 → max start = 0.5
        vm.moveWindowToFraction(0.8f)
        assertEquals(0.5f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindowToFraction_clampsAtStart() {
        vm.moveWindowToFraction(-0.5f)
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindowToFraction_noopWhenDurationZero() {
        setField("_audioDurationMs", 0)
        setField("_windowStartFraction", 0.2f)
        vm.moveWindowToFraction(0.5f)
        // Should not change because durationMs <= 0
        assertEquals(0.2f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindowToFraction_exactMaxStart() {
        // Exactly at the max start value (0.5 with a 0.5 window)
        vm.moveWindowToFraction(0.5f)
        assertEquals(0.5f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindowToFraction_smallWindow() {
        // 1-second window in a 10-second clip → windowFractionSize = 0.1, max = 0.9
        setField("_windowSizeSec", 1f)
        vm.moveWindowToFraction(0.85f)
        assertEquals(0.85f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindowToFraction_smallWindow_clamped() {
        setField("_windowSizeSec", 1f)
        vm.moveWindowToFraction(0.95f)
        assertEquals(0.9f, vm.windowStartFraction.value, 0.001f)
    }

    // ── moveWindow (delta) ──────────────────────────────────────────────────

    @Test
    fun moveWindow_forwardOneWindow() {
        // Start at 0, one window forward (delta=1) should move by 0.5
        vm.moveWindow(1f)
        assertEquals(0.5f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindow_backwardClampsAtZero() {
        setField("_windowStartFraction", 0.1f)
        vm.moveWindow(-1f) // 0.1 - 0.5 = -0.4 → clamp to 0
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindow_forwardClampsAtEnd() {
        setField("_windowStartFraction", 0.3f)
        vm.moveWindow(1f)  // 0.3 + 0.5 = 0.8 → clamp to 0.5
        assertEquals(0.5f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindow_halfWindowForward() {
        vm.moveWindow(0.5f) // 0 + 0.25 = 0.25
        assertEquals(0.25f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun moveWindow_noopWhenDurationZero() {
        setField("_audioDurationMs", 0)
        setField("_windowStartFraction", 0.2f)
        vm.moveWindow(1f)
        assertEquals(0.2f, vm.windowStartFraction.value, 0.001f)
    }

    // ── toggleWindowLock ────────────────────────────────────────────────────

    @Test
    fun toggleWindowLock_defaultUnlocked() {
        assertFalse(vm.isWindowLocked.value)
    }

    @Test
    fun toggleWindowLock_lockAndUnlock() {
        vm.toggleWindowLock()
        assertTrue(vm.isWindowLocked.value)
        vm.toggleWindowLock()
        assertFalse(vm.isWindowLocked.value)
    }

    // ── setWindowSizeSec ────────────────────────────────────────────────────

    @Test
    fun setWindowSizeSec_validValue() {
        vm.setWindowSizeSec(10f)
        assertEquals(10f, vm.windowSizeSec.value, 0.001f)
    }

    @Test
    fun setWindowSizeSec_clampedMin() {
        vm.setWindowSizeSec(0.1f) // below 1f
        assertEquals(1f, vm.windowSizeSec.value, 0.001f)
    }

    @Test
    fun setWindowSizeSec_clampedMax() {
        vm.setWindowSizeSec(99f) // above 30f
        assertEquals(30f, vm.windowSizeSec.value, 0.001f)
    }

    @Test
    fun setWindowSizeSec_exactBoundaries() {
        vm.setWindowSizeSec(1f)
        assertEquals(1f, vm.windowSizeSec.value, 0.001f)
        vm.setWindowSizeSec(30f)
        assertEquals(30f, vm.windowSizeSec.value, 0.001f)
    }

    // ── setWindowStartFraction ──────────────────────────────────────────────

    @Test
    fun setWindowStartFraction_validValue() {
        vm.setWindowStartFraction(0.4f)
        assertEquals(0.4f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun setWindowStartFraction_clampedBelow() {
        vm.setWindowStartFraction(-1f)
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun setWindowStartFraction_clampedAbove() {
        vm.setWindowStartFraction(2f)
        assertEquals(1f, vm.windowStartFraction.value, 0.001f)
    }

    // ── updateWindowForPlayback: auto-scroll ────────────────────────────────

    @Test
    fun updateWindowForPlayback_movesWindow_whenPlaybackPastWindowEnd() {
        // Window at 0–0.5; playback at 0.7 is past the end → window should move
        setField("_windowStartFraction", 0f)
        vm.updateWindowForPlayback(0.7f)
        // New start should bring 0.7 into view (roughly 0.7 - 0.5*0.1 = 0.65)
        assertTrue(vm.windowStartFraction.value > 0f)
        // Verify playback fraction is now inside the window
        val start = vm.windowStartFraction.value
        val end = start + 0.5f // windowFractionSize
        assertTrue(0.7f in start..end)
    }

    @Test
    fun updateWindowForPlayback_movesWindow_whenPlaybackBeforeWindowStart() {
        // Window at 0.4–0.9; playback at 0.1 is before start → window should move back
        setField("_windowStartFraction", 0.4f)
        vm.updateWindowForPlayback(0.1f)
        assertTrue(vm.windowStartFraction.value < 0.4f)
        // Verify playback is now in view
        val start = vm.windowStartFraction.value
        val end = start + 0.5f
        assertTrue(0.1f in start..end)
    }

    @Test
    fun updateWindowForPlayback_doesNotMove_whenPlaybackInView() {
        // Window at 0–0.5; playback at 0.3 is inside → no change
        setField("_windowStartFraction", 0f)
        vm.updateWindowForPlayback(0.3f)
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun updateWindowForPlayback_doesNotMove_whenLocked() {
        // Lock window, then playback goes out of view → window stays locked
        setField("_windowStartFraction", 0f)
        vm.toggleWindowLock()
        assertTrue(vm.isWindowLocked.value)
        vm.updateWindowForPlayback(0.9f) // out of view
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun updateWindowForPlayback_noopWhenDurationZero() {
        setField("_audioDurationMs", 0)
        setField("_windowStartFraction", 0.2f)
        vm.updateWindowForPlayback(0.5f)
        assertEquals(0.2f, vm.windowStartFraction.value, 0.001f)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun setField(name: String, value: Any?) {
        val field = PreviewViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        val stateFlow = field.get(vm)
        if (stateFlow is MutableStateFlow<*>) {
            (stateFlow as MutableStateFlow<Any?>).value = value
        }
    }
}
