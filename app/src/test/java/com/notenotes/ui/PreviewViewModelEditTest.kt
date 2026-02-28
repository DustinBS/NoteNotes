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
 * Regression tests for note editing state management.
 *
 * Key invariants:
 * - selectNote(index) opens editor, clears edit cursor
 * - setEditCursor(fraction) opens editor, clears note selection
 * - closeEditor() closes editor, clears BOTH selection and cursor
 * - isCursorInsideNote() returns true only when cursor fraction falls within a note boundary
 * - deleteSelectedNote() removes the note and clears selection
 * - clearAllNotes() empties note list and clears all edit state
 * - getPlaybackFractionInNote() returns 0..1 fraction within the current note
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewViewModelEditTest {

    private lateinit var vm: PreviewViewModel

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        vm = PreviewViewModel(app)
        setField("_audioDurationMs", 10000)
        setField("_windowSizeSec", 5f)
        setField("_windowStartFraction", 0f)
    }

    // ── selectNote ──────────────────────────────────────────────────────────

    @Test
    fun selectNote_opensEditor() {
        assertFalse(vm.isEditorOpen.value)
        vm.selectNote(0)
        assertTrue(vm.isEditorOpen.value)
    }

    @Test
    fun selectNote_setsIndex() {
        vm.selectNote(2)
        assertEquals(2, vm.selectedNoteIndex.value)
    }

    @Test
    fun selectNote_clearsEditCursor() {
        // First set a cursor, then select a note → cursor must clear
        vm.setEditCursor(0.5f)
        assertEquals(0.5f, vm.editCursorFraction.value!!, 0.001f)

        vm.selectNote(1)
        assertNull(vm.editCursorFraction.value)
        assertEquals(1, vm.selectedNoteIndex.value)
    }

    @Test
    fun selectNote_null_doesNotOpenEditor() {
        // Editor starts closed
        assertFalse(vm.isEditorOpen.value)
        vm.selectNote(null)
        // Selecting null should NOT open the editor
        assertFalse(vm.isEditorOpen.value)
        assertNull(vm.selectedNoteIndex.value)
    }

    // ── setEditCursor ───────────────────────────────────────────────────────

    @Test
    fun setEditCursor_opensEditor() {
        assertFalse(vm.isEditorOpen.value)
        vm.setEditCursor(0.3f)
        assertTrue(vm.isEditorOpen.value)
    }

    @Test
    fun setEditCursor_setsFraction() {
        vm.setEditCursor(0.75f)
        assertEquals(0.75f, vm.editCursorFraction.value!!, 0.001f)
    }

    @Test
    fun setEditCursor_clearsNoteSelection() {
        // First select a note, then set cursor → selection must clear
        vm.selectNote(3)
        assertEquals(3, vm.selectedNoteIndex.value)

        vm.setEditCursor(0.4f)
        assertNull(vm.selectedNoteIndex.value)
        assertEquals(0.4f, vm.editCursorFraction.value!!, 0.001f)
    }

    @Test
    fun setEditCursor_null_doesNotOpenEditor() {
        assertFalse(vm.isEditorOpen.value)
        vm.setEditCursor(null)
        assertFalse(vm.isEditorOpen.value)
        assertNull(vm.editCursorFraction.value)
    }

    // ── closeEditor ─────────────────────────────────────────────────────────

    @Test
    fun closeEditor_closesEditorPanel() {
        vm.selectNote(0)
        assertTrue(vm.isEditorOpen.value)
        vm.closeEditor()
        assertFalse(vm.isEditorOpen.value)
    }

    @Test
    fun closeEditor_clearsBothSelectionAndCursor() {
        vm.selectNote(2)
        vm.closeEditor()
        assertNull(vm.selectedNoteIndex.value)
        assertNull(vm.editCursorFraction.value)
    }

    @Test
    fun closeEditor_clearsCursorWhenCursorSet() {
        vm.setEditCursor(0.6f)
        vm.closeEditor()
        assertNull(vm.editCursorFraction.value)
        assertNull(vm.selectedNoteIndex.value)
        assertFalse(vm.isEditorOpen.value)
    }

    // ── isCursorInsideNote ──────────────────────────────────────────────────

    @Test
    fun isCursorInsideNote_returnsTrueWhenInside() {
        // 3 quarter notes at 120 BPM: each 500ms → 0–500, 500–1000, 1000–1500
        // Cursor at 750ms (0.075) → inside note 1 (500–1000ms)
        setNotesAndTempo(
            listOf(quarterNote(60), quarterNote(62), quarterNote(64)),
            tempoBpm = 120
        )
        vm.setEditCursor(0.075f) // 750ms
        assertTrue(vm.isCursorInsideNote())
    }

    @Test
    fun isCursorInsideNote_returnsTrueAtNoteStart() {
        setNotesAndTempo(
            listOf(quarterNote(60), quarterNote(62)),
            tempoBpm = 120
        )
        vm.setEditCursor(0f) // 0ms → note 0 starts here
        assertTrue(vm.isCursorInsideNote())
    }

    @Test
    fun isCursorInsideNote_returnsFalseWhenOutside() {
        // Two notes span 0–1000ms. Cursor at 5000ms (0.5) → way past notes
        setNotesAndTempo(
            listOf(quarterNote(60), quarterNote(62)),
            tempoBpm = 120
        )
        vm.setEditCursor(0.5f) // 5000ms → past all notes
        assertFalse(vm.isCursorInsideNote())
    }

    @Test
    fun isCursorInsideNote_returnsFalseWhenNoCursor() {
        setNotesAndTempo(
            listOf(quarterNote(60)),
            tempoBpm = 120
        )
        // No cursor set
        assertFalse(vm.isCursorInsideNote())
    }

    @Test
    fun isCursorInsideNote_returnsFalseWithEmptyNotes() {
        setField("_notesList", emptyList<MusicalNote>())
        vm.setEditCursor(0.5f)
        assertFalse(vm.isCursorInsideNote())
    }

    @Test
    fun isCursorInsideNote_manualNotes() {
        // Manual notes at explicit time positions
        setNotesAndTempo(
            listOf(
                quarterNote(60).copy(isManual = true, timePositionMs = 1000f),
                quarterNote(62).copy(isManual = true, timePositionMs = 3000f)
            ),
            tempoBpm = 120
        )
        // 1000–1500ms (500ms duration for quarter at 120bpm)
        // Cursor at 1200ms (0.12) → inside first manual note
        vm.setEditCursor(0.12f)
        assertTrue(vm.isCursorInsideNote())
    }

    // ── deleteSelectedNote ──────────────────────────────────────────────────

    @Test
    fun deleteSelectedNote_removesNote() {
        val notes = listOf(quarterNote(60), quarterNote(62), quarterNote(64))
        setField("_notesList", notes)
        vm.selectNote(1)

        vm.deleteSelectedNote()
        assertEquals(2, vm.notesList.value.size)
        // Remaining: note 0 (midi 60), note 2 (midi 64)
        assertEquals(60, vm.notesList.value[0].midiPitch)
        assertEquals(64, vm.notesList.value[1].midiPitch)
    }

    @Test
    fun deleteSelectedNote_clearsSelection() {
        val notes = listOf(quarterNote(60), quarterNote(62))
        setField("_notesList", notes)
        vm.selectNote(0)

        vm.deleteSelectedNote()
        assertNull(vm.selectedNoteIndex.value)
    }

    @Test
    fun deleteSelectedNote_noopWhenNoSelection() {
        val notes = listOf(quarterNote(60), quarterNote(62))
        setField("_notesList", notes)
        // No note selected
        vm.deleteSelectedNote()
        assertEquals(2, vm.notesList.value.size)
    }

    @Test
    fun deleteSelectedNote_noopWhenIndexOutOfBounds() {
        val notes = listOf(quarterNote(60))
        setField("_notesList", notes)
        setField("_selectedNoteIndex", 5) // out of bounds

        vm.deleteSelectedNote()
        assertEquals(1, vm.notesList.value.size)
    }

    // ── clearAllNotes ───────────────────────────────────────────────────────

    @Test
    fun clearAllNotes_emptiesList() {
        setField("_notesList", listOf(quarterNote(60), quarterNote(62), quarterNote(64)))
        vm.clearAllNotes()
        assertTrue(vm.notesList.value.isEmpty())
    }

    @Test
    fun clearAllNotes_clearsEditState() {
        setField("_notesList", listOf(quarterNote(60)))
        vm.selectNote(0)
        vm.clearAllNotes()
        assertNull(vm.selectedNoteIndex.value)
        assertNull(vm.editCursorFraction.value)
    }

    // ── getPlaybackFractionInNote ───────────────────────────────────────────

    @Test
    fun getPlaybackFractionInNote_returnsZeroForEmptyNotes() {
        setField("_notesList", emptyList<MusicalNote>())
        assertEquals(0f, vm.getPlaybackFractionInNote(0.5f), 0.001f)
    }

    @Test
    fun getPlaybackFractionInNote_returnsZeroAtNoteStart() {
        // At 120 BPM: quarter = 500ms. Note 0 spans 0–500ms.
        // Progress 0.0 = 0ms → fraction within note = 0
        setNotesAndTempo(
            listOf(quarterNote(60), quarterNote(62)),
            tempoBpm = 120
        )
        assertEquals(0f, vm.getPlaybackFractionInNote(0f), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_returnsHalfAtMidpoint() {
        // Note 0: 0–500ms. Progress at 250ms (0.025) → fraction = 0.5
        setNotesAndTempo(
            listOf(quarterNote(60), quarterNote(62)),
            tempoBpm = 120
        )
        assertEquals(0.5f, vm.getPlaybackFractionInNote(0.025f), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_returnsOneAtEnd() {
        // Past all notes → returns 1
        setNotesAndTempo(
            listOf(quarterNote(60)),
            tempoBpm = 120
        )
        assertEquals(1f, vm.getPlaybackFractionInNote(0.9f), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_secondNote() {
        // Note 1: 500–1000ms. Progress at 750ms (0.075) → fraction = 0.5
        setNotesAndTempo(
            listOf(quarterNote(60), quarterNote(62)),
            tempoBpm = 120
        )
        assertEquals(0.5f, vm.getPlaybackFractionInNote(0.075f), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_manualNotes() {
        setNotesAndTempo(
            listOf(
                quarterNote(60).copy(isManual = true, timePositionMs = 0f),
                quarterNote(62).copy(isManual = true, timePositionMs = 500f)
            ),
            tempoBpm = 120
        )
        // 250ms (0.025) → midpoint of note 0 → fraction 0.5
        assertEquals(0.5f, vm.getPlaybackFractionInNote(0.025f), 0.01f)
    }

    @Test
    fun getPlaybackFractionInNote_returnsZeroWhenDurationZero() {
        setField("_audioDurationMs", 0)
        assertEquals(0f, vm.getPlaybackFractionInNote(0.5f), 0.001f)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun quarterNote(midi: Int) = MusicalNote(
        midiPitch = midi,
        durationTicks = 4,
        type = "quarter"
    )

    private fun setNotesAndTempo(notes: List<MusicalNote>, tempoBpm: Int) {
        setField("_notesList", notes)
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
        val stateFlow = field.get(vm)
        if (stateFlow is MutableStateFlow<*>) {
            (stateFlow as MutableStateFlow<Any?>).value = value
        }
    }
}
