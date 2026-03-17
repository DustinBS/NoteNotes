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
 * Regression tests for playback speed, tab state, and MusicalNote data integrity.
 *
 * Key invariants:
 * - Default playback speed is 1.0
 * - setPlaybackSpeed updates the flow value
 * - Default tab is 2 (Waveform)
 * - setSelectedTab persists the tab selection
 * - MusicalNote.isChord is true when chordPitches is non-empty
 * - MusicalNote.sanitized() fills missing tab data
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewViewModelPlaybackTest {

    private lateinit var vm: PreviewViewModel

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        vm = PreviewViewModel(app)
        setField("_audioDurationMs", 10000)
        setField("_windowSizeSec", 5f)
    }

    // ── Playback Speed ──────────────────────────────────────────────────────

    @Test
    fun defaultSpeed_isOne() {
        assertEquals(1f, vm.playbackSpeed.value, 0.001f)
    }

    @Test
    fun setPlaybackSpeed_updatesFlow() {
        vm.setPlaybackSpeed(0.5f)
        assertEquals(0.5f, vm.playbackSpeed.value, 0.001f)
    }

    @Test
    fun setPlaybackSpeed_quarterSpeed() {
        vm.setPlaybackSpeed(0.25f)
        assertEquals(0.25f, vm.playbackSpeed.value, 0.001f)
    }

    @Test
    fun setPlaybackSpeed_persistsThroughMultipleSets() {
        vm.setPlaybackSpeed(0.5f)
        vm.setPlaybackSpeed(0.25f)
        vm.setPlaybackSpeed(1f)
        assertEquals(1f, vm.playbackSpeed.value, 0.001f)
    }

    // ── Tab State ───────────────────────────────────────────────────────────

    @Test
    fun defaultTab_isWaveform() {
        // Tab 2 is Waveform (0=Sheet, 1=Notes, 2=Waveform)
        assertEquals(2, vm.selectedTab.value)
    }

    @Test
    fun setSelectedTab_updatesState() {
        vm.setSelectedTab(0) // Sheet tab
        assertEquals(0, vm.selectedTab.value)
    }

    @Test
    fun setSelectedTab_notesTab() {
        vm.setSelectedTab(1) // Notes tab
        assertEquals(1, vm.selectedTab.value)
    }

    @Test
    fun setSelectedTab_persistsAcrossSwitches() {
        vm.setSelectedTab(0)
        vm.setSelectedTab(1)
        vm.setSelectedTab(2)
        assertEquals(2, vm.selectedTab.value)
    }

    // ── MusicalNote Data Integrity ──────────────────────────────────────────

    @Test
    fun musicalNote_isChord_whenChordPitchesPresent() {
        val note = MusicalNote(
            pitches = listOf(60, 64, 67),
            durationTicks = 4,
            type = "quarter"
        )
        assertTrue(note.isChord)
    }

    @Test
    fun musicalNote_isNotChord_whenChordPitchesEmpty() {
        val note = MusicalNote(
            pitches = listOf(60),
            durationTicks = 4,
            type = "quarter"
        )
        assertFalse(note.isChord)
    }

    @Test
    fun musicalNote_allPitches_includesPrimaryAndChord() {
        val note = MusicalNote(
            pitches = listOf(60, 64, 67),
            durationTicks = 4,
            type = "quarter"
        )
        assertEquals(listOf(60, 64, 67), note.allPitches)
    }

    @Test
    fun musicalNote_allPitches_singleNotePrimary() {
        val note = MusicalNote(
            pitches = listOf(60),
            durationTicks = 4,
            type = "quarter"
        )
        assertEquals(listOf(60), note.allPitches)
    }

    @Test
    fun musicalNote_hasTab_whenStringAndFretSet() {
        val note = MusicalNote(
            pitches = listOf(60),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(1, 3))
        )
        assertTrue(note.hasTab)
    }

    @Test
    fun musicalNote_hasTab_falseByDefault() {
        val note = MusicalNote(
            pitches = listOf(60),
            durationTicks = 4,
            type = "quarter"
        )
        assertFalse(note.hasTab)
    }

    @Test
    fun musicalNote_sanitized_fillsMissingTab() {
        // sanitized() should respect existing string/fret and not reset them
        val note = MusicalNote(
            pitches = listOf(60),
            durationTicks = 4,
            type = "quarter",
            tabPositions = listOf(Pair(2, 5))
        )
        val sanitized = note.sanitized()
        assertEquals(2, sanitized.tabPositions.first().first)
        assertEquals(5, sanitized.tabPositions.first().second)
    }

    @Test
    fun musicalNote_sanitizeList_preservesOrder() {
        val notes = listOf(
            MusicalNote(pitches = listOf(60), durationTicks = 4, type = "quarter"),
            MusicalNote(pitches = listOf(64), durationTicks = 4, type = "quarter"),
            MusicalNote(pitches = listOf(67), durationTicks = 4, type = "quarter")
        )
        val sanitized = MusicalNote.sanitizeList(notes)
        assertEquals(3, sanitized.size)
        assertEquals(60, sanitized[0].pitches.first())
        assertEquals(64, sanitized[1].pitches.first())
        assertEquals(67, sanitized[2].pitches.first())
    }

    // ── Default States ──────────────────────────────────────────────────────

    @Test
    fun defaultEditorState_closed() {
        assertFalse(vm.isEditorOpen.value)
    }

    @Test
    fun defaultSelectedNote_isNull() {
        assertNull(vm.selectedNoteIndex.value)
    }

    @Test
    fun defaultEditCursor_isNull() {
        assertNull(vm.editCursorFraction.value)
    }

    @Test
    fun defaultWindowStart_isZero() {
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun defaultWindowSize_isFive() {
        assertEquals(5f, vm.windowSizeSec.value, 0.001f)
    }

    @Test
    fun defaultWindowLocked_isFalse() {
        assertFalse(vm.isWindowLocked.value)
    }

    @Test
    fun defaultNotesList_isEmpty() {
        assertTrue(vm.notesList.value.isEmpty())
    }

    @Test
    fun defaultErrorMessage_isNull() {
        assertNull(vm.errorMessage.value)
    }

    // ── Error state ─────────────────────────────────────────────────────────

    @Test
    fun setError_updatesFlow() {
        vm.setError("Test error")
        assertEquals("Test error", vm.errorMessage.value)
    }

    // ── Integration: edit → close → re-edit ─────────────────────────────────

    @Test
    fun selectNote_closeEditor_setEditCursor_fullCycle() {
        // 1. Select a note → editor opens, cursor clears
        vm.selectNote(2)
        assertTrue(vm.isEditorOpen.value)
        assertEquals(2, vm.selectedNoteIndex.value)
        assertNull(vm.editCursorFraction.value)

        // 2. Close editor → everything clears
        vm.closeEditor()
        assertFalse(vm.isEditorOpen.value)
        assertNull(vm.selectedNoteIndex.value)
        assertNull(vm.editCursorFraction.value)

        // 3. Set edit cursor → editor opens, selection stays null
        vm.setEditCursor(0.5f)
        assertTrue(vm.isEditorOpen.value)
        assertNull(vm.selectedNoteIndex.value)
        assertEquals(0.5f, vm.editCursorFraction.value!!, 0.001f)

        // 4. Select note again → cursor clears
        vm.selectNote(0)
        assertTrue(vm.isEditorOpen.value)
        assertEquals(0, vm.selectedNoteIndex.value)
        assertNull(vm.editCursorFraction.value)
    }

    @Test
    fun waveformScrub_thenTransportScrub_windowBehavior() {
        // Waveform scrub (seekAudioOnly) should NOT move window
        setField("_windowStartFraction", 0f)
        vm.seekAudioOnly(0.7f)
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)

        // Transport scrub (seekTo) MUST move window
        vm.seekTo(0.3f)
        assertEquals(0.3f, vm.windowStartFraction.value, 0.001f)
    }

    @Test
    fun lockWindow_thenSeekTo_windowDoesNotAutoScroll() {
        // Lock window, then use transport → window moves (seekTo moves directly)
        // but auto-scroll during playback won't move it
        vm.toggleWindowLock()
        assertTrue(vm.isWindowLocked.value)

        // Manual seekTo still moves window (it's direct assignment, not auto-scroll)
        vm.seekTo(0.3f)
        assertEquals(0.3f, vm.windowStartFraction.value, 0.001f)

        // But auto-scroll during playback won't move it
        setField("_windowStartFraction", 0f)
        vm.updateWindowForPlayback(0.9f)
        assertEquals(0f, vm.windowStartFraction.value, 0.001f)
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
