package com.notenotes.ui

import android.app.Application
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
 * Regression tests for waveform scrub behavior and cursor note-index tracking.
 *
 * Key invariants:
 * - seekAudioOnly() must NEVER move the waveform window
 * - seekTo() MUST move the waveform window (for transport controls)
 * - getCurrentNoteIndex() must walk notes using the same timing as the Notes tab
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewViewModelSeekTest {

    private lateinit var viewModel: PreviewViewModel

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        viewModel = PreviewViewModel(app)

        // Set up internal state via reflection so seek methods work correctly
        setField("_audioDurationMs", 10000)  // 10 seconds
        setField("_windowSizeSec", 5f)       // 5 second window
        setField("_windowStartFraction", 0f) // window starts at beginning
    }

    // ── seekAudioOnly: window must NOT move ──────────────────────────────────

    @Test
    fun seekAudioOnly_doesNotMoveWindow_fromZero() {
        // Window starts at 0
        assertEquals(0f, viewModel.windowStartFraction.value, 0.001f)

        viewModel.seekAudioOnly(0.5f)

        // Window must stay at 0
        assertEquals(0f, viewModel.windowStartFraction.value, 0.001f)
    }

    @Test
    fun seekAudioOnly_doesNotMoveWindow_fromMiddle() {
        // Set window to 0.3
        setField("_windowStartFraction", 0.3f)
        assertEquals(0.3f, viewModel.windowStartFraction.value, 0.001f)

        viewModel.seekAudioOnly(0.8f)

        // Window must stay at 0.3
        assertEquals(0.3f, viewModel.windowStartFraction.value, 0.001f)
    }

    @Test
    fun seekAudioOnly_doesNotMoveWindow_multipleSeeks() {
        setField("_windowStartFraction", 0.2f)

        viewModel.seekAudioOnly(0.1f)
        viewModel.seekAudioOnly(0.5f)
        viewModel.seekAudioOnly(0.9f)

        // Window must stay at 0.2 through all seeks
        assertEquals(0.2f, viewModel.windowStartFraction.value, 0.001f)
    }

    // ── seekTo: window MUST move ─────────────────────────────────────────────

    @Test
    fun seekTo_movesWindow() {
        assertEquals(0f, viewModel.windowStartFraction.value, 0.001f)

        viewModel.seekTo(0.5f)

        // Window should move to approximately 0.5 (the seek target becomes window start)
        assertEquals(0.5f, viewModel.windowStartFraction.value, 0.001f)
    }

    @Test
    fun seekTo_movesWindow_clampedAtEnd() {
        viewModel.seekTo(0.9f)

        // Window should move near end but be clamped (can't extend past 1.0)
        // Window size is 5s/10s = 0.5 fraction, so max start = 0.5
        assertEquals(0.5f, viewModel.windowStartFraction.value, 0.001f)
    }

    // ── getCurrentNoteIndex: cursor sync with notes tab ──────────────────────

    @Test
    fun getCurrentNoteIndex_returnsZero_forEmptyNotes() {
        setField("_notesList", emptyList<MusicalNote>())
        assertEquals(0, viewModel.getCurrentNoteIndex(0.5f))
    }

    @Test
    fun getCurrentNoteIndex_returnsZero_atStartOfPlayback() {
        setNotesAndTempo(
            listOf(
                quarterNote(60),
                quarterNote(62),
                quarterNote(64)
            ),
            tempoBpm = 120
        )
        assertEquals(0, viewModel.getCurrentNoteIndex(0f))
    }

    @Test
    fun getCurrentNoteIndex_returnsCorrectIndex_midPlayback() {
        // At 120 BPM: quarter note = 500ms, tick = 125ms
        // Each note is 4 ticks = 500ms
        // Duration = 10000ms
        // Note 0: 0-500ms = progress 0.0–0.05
        // Note 1: 500-1000ms = progress 0.05–0.1
        // Note 2: 1000-1500ms = progress 0.1–0.15
        setNotesAndTempo(
            listOf(
                quarterNote(60),
                quarterNote(62),
                quarterNote(64)
            ),
            tempoBpm = 120
        )

        // Progress at 750ms = 0.075 → should be note index 1
        assertEquals(1, viewModel.getCurrentNoteIndex(0.075f))
    }

    @Test
    fun getCurrentNoteIndex_returnsLastNote_pastEnd() {
        setNotesAndTempo(
            listOf(
                quarterNote(60),
                quarterNote(62)
            ),
            tempoBpm = 120
        )
        // Progress at 5000ms — well past all notes (2 notes × 500ms = 1000ms total)
        assertEquals(1, viewModel.getCurrentNoteIndex(0.5f))
    }

    @Test
    fun getCurrentNoteIndex_handlesManualNoteTiming() {
        // Manual notes with explicit time positions
        // At 120 BPM: quarter note = 500ms (4 ticks × 125ms/tick)
        // Note 0: 0–500ms, Note 1: 500–1000ms, Note 2: 1000–1500ms (contiguous)
        setNotesAndTempo(
            listOf(
                quarterNote(60).copy(isManual = true, timePositionMs = 0f),
                quarterNote(62).copy(isManual = true, timePositionMs = 500f),
                quarterNote(64).copy(isManual = true, timePositionMs = 1000f)
            ),
            tempoBpm = 120
        )

        // Progress at 250ms (0.025) → inside note 0 (0–500ms)
        assertEquals(0, viewModel.getCurrentNoteIndex(0.025f))

        // Progress at 750ms (0.075) → inside note 1 (500–1000ms)
        assertEquals(1, viewModel.getCurrentNoteIndex(0.075f))

        // Progress at 1250ms (0.125) → inside note 2 (1000–1500ms)
        assertEquals(2, viewModel.getCurrentNoteIndex(0.125f))
    }

    @Test
    fun getCurrentNoteIndex_firstNoteAtZeroProgress() {
        // This is the regression test for "cursor misses first note"
        // At progress=0, getCurrentNoteIndex must return 0 (not -1 or 1)
        setNotesAndTempo(
            listOf(
                quarterNote(60),
                quarterNote(62),
                quarterNote(64)
            ),
            tempoBpm = 120
        )
        assertEquals(0, viewModel.getCurrentNoteIndex(0f))
    }

    @Test
    fun getCurrentNoteIndex_tinyProgressStillFirstNote() {
        // Even at very small non-zero progress, first note should be returned
        setNotesAndTempo(
            listOf(
                quarterNote(60),
                quarterNote(62)
            ),
            tempoBpm = 120
        )
        assertEquals(0, viewModel.getCurrentNoteIndex(0.001f))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun quarterNote(midi: Int) = MusicalNote(
        midiPitch = midi,
        durationTicks = 4,
        type = "quarter"
    )

    private fun setNotesAndTempo(notes: List<MusicalNote>, tempoBpm: Int) {
        setField("_notesList", notes)
        // Create a minimal MelodyIdea with the given tempo
        val idea = MelodyIdea(
            id = 1,
            title = "Test",
            createdAt = System.currentTimeMillis(),
            audioFilePath = "/test.wav",
            tempoBpm = tempoBpm
        )
        setField("_idea", idea)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setField(name: String, value: Any?) {
        val field = PreviewViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        val stateFlow = field.get(viewModel)
        if (stateFlow is MutableStateFlow<*>) {
            (stateFlow as MutableStateFlow<Any?>).value = value
        }
    }
}
