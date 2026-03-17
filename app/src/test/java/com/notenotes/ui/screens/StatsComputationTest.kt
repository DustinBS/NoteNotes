package com.notenotes.ui.screens

import com.google.gson.Gson
import com.notenotes.model.MelodyIdea
import com.notenotes.model.MusicalNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsComputationTest {

    private val gson = Gson()

    @Test
    fun parseIdeaNotes_invalidJson_returnsEmptyList() {
        val parsed = parseIdeaNotes("{not_json")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun computeStatsForIdeas_countsStringUsage_withMidiFallback() {
        // Primary note has no tab info: MIDI fallback should still attribute it to some guitar string.
        // Chord note has explicit tab info on string index 5 fret 0.
        val notes = listOf(
            MusicalNote(
                midiPitch = 59,
                durationTicks = 4,
                type = "quarter",
                chordPitches = listOf(64),
                chordStringFrets = listOf(Pair(5, 0)),
                guitarString = null,
                guitarFret = null,
                isManual = true,
                timePositionMs = 1000f
            )
        )
        val idea = idea(
            id = 1,
            groupId = "g1",
            groupName = "Group 1",
            tempo = 120,
            notes = notes
        )

        val stats = computeStatsForIdeas(listOf(idea))

        assertEquals(1, stats.totalIdeas)
        assertEquals(2, stats.totalNotes)
        assertEquals(2, stats.notesPerString.sum())
        assertTrue(stats.notesPerString[5] >= 1) // explicit high E chord tone
        assertTrue(stats.totalDurationSec > 0f)
    }

    @Test
    fun computeStatsForIdeas_drilldownFilteringByGroupAndIdea_changesScopeTotals() {
        val ideaA = idea(
            id = 1,
            groupId = "g1",
            groupName = "Group 1",
            tempo = 100,
            notes = listOf(MusicalNote(60, 4, "quarter", guitarString = 3, guitarFret = 2, isManual = true, timePositionMs = 0f))
        )
        val ideaB = idea(
            id = 2,
            groupId = "g2",
            groupName = "Group 2",
            tempo = 140,
            notes = listOf(MusicalNote(64, 4, "quarter", guitarString = 5, guitarFret = 0, isManual = true, timePositionMs = 0f))
        )

        val allStats = computeStatsForIdeas(listOf(ideaA, ideaB))
        val g1Stats = computeStatsForIdeas(listOf(ideaA, ideaB).filter { it.groupId == "g1" })
        val ideaBStats = computeStatsForIdeas(listOf(ideaA, ideaB).filter { it.id == 2L })

        assertEquals(2, allStats.totalIdeas)
        assertEquals(1, g1Stats.totalIdeas)
        assertEquals(1, ideaBStats.totalIdeas)
        assertNotEquals(allStats.averageTempoBpm, g1Stats.averageTempoBpm)
        assertEquals(140, ideaBStats.averageTempoBpm)
    }

    @Test
    fun computeStatsForIdeas_funFactsArePopulated() {
        val notes = listOf(
            MusicalNote(57, 4, "quarter", guitarString = 3, guitarFret = 2, isManual = true, timePositionMs = 0f),
            MusicalNote(59, 4, "quarter", chordPitches = listOf(62), chordStringFrets = listOf(Pair(4, 3)), guitarString = 4, guitarFret = 0, isManual = true, timePositionMs = 1000f)
        )
        val stats = computeStatsForIdeas(
            listOf(
                idea(id = 1, groupId = null, groupName = null, tempo = 120, notes = notes),
                idea(id = 2, groupId = null, groupName = null, tempo = 90, notes = notes)
            )
        )

        assertTrue(stats.averageNotesPerIdea > 0f)
        assertNotEquals("-", stats.mostActiveDay)
        assertTrue(stats.mostCommonChordSize != null)
        assertTrue(stats.totalDurationSec > 0f)
    }

    private fun idea(
        id: Long,
        groupId: String?,
        groupName: String?,
        tempo: Int,
        notes: List<MusicalNote>
    ): MelodyIdea {
        return MelodyIdea(
            id = id,
            title = "Idea $id",
            createdAt = 1_700_000_000_000L + id * 86_400_000L,
            audioFilePath = "/tmp/$id.wav",
            tempoBpm = tempo,
            notes = gson.toJson(notes),
            groupId = groupId,
            groupName = groupName
        )
    }
}
