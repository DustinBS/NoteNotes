package com.notenotes.ui.screens

import android.app.Application
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notenotes.ui.components.SearchAndSortHeader
import com.notenotes.ui.components.DragSelectLazyColumn
import com.notenotes.ui.components.SortMode
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.notenotes.data.AppDatabase
import com.notenotes.model.MelodyIdea
import com.notenotes.model.MusicalNote
import com.notenotes.util.GuitarUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StatsUiState(
    val totalIdeas: Int = 0,
    val totalNotes: Int = 0,
    val totalDurationSec: Float = 0f,
    val averageTempoBpm: Int = 0,
    val averageNotesPerIdea: Float = 0f,
    val notesPerString: IntArray = IntArray(GuitarUtils.STRINGS.size),
    val durationPerStringSec: FloatArray = FloatArray(GuitarUtils.STRINGS.size),
    val mostUsedStringIndex: Int? = null,
    val mostCommonFret: Int? = null,
    val mostActiveDay: String = "-",
    val mostCommonChordSize: Int? = null
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).melodyDao()

    private val _ideas = MutableStateFlow<List<MelodyIdea>>(emptyList())
    val ideas: StateFlow<List<MelodyIdea>> = _ideas

    init {
        viewModelScope.launch {
            dao.getAllIdeas().collect { ideas ->
                _ideas.value = ideas
            }
        }
    }
}

internal fun parseIdeaNotes(notesJson: String?): List<MusicalNote> {
    if (notesJson.isNullOrBlank()) return emptyList()
    return try {
        val gson = Gson()
        val notesType = object : TypeToken<List<MusicalNote>>() {}.type
        val parsed = gson.fromJson<List<MusicalNote>>(notesJson, notesType) ?: emptyList()
        parsed.map { it.sanitized() }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun computeStatsForIdeas(ideas: List<MelodyIdea>): StatsUiState {
    val notesPerString = IntArray(GuitarUtils.STRINGS.size)
    val durationPerStringSec = FloatArray(GuitarUtils.STRINGS.size)
    val fretCounts = mutableMapOf<Int, Int>()
    val dayCounts = mutableMapOf<String, Int>()
    val chordSizeCounts = mutableMapOf<Int, Int>()

    var tempoSum = 0
    var tempoCount = 0
    var totalNotes = 0
    var totalDurationSec = 0f

    fun addStringUsage(stringIndex: Int?, fret: Int?, durationSec: Float) {
        if (stringIndex == null || stringIndex !in GuitarUtils.STRINGS.indices) return
        notesPerString[stringIndex] += 1
        durationPerStringSec[stringIndex] += durationSec
        totalNotes += 1
        totalDurationSec += durationSec
        if (fret != null) {
            fretCounts[fret] = (fretCounts[fret] ?: 0) + 1
        }
    }

    ideas.forEach { idea ->
        tempoSum += idea.tempoBpm
        tempoCount += 1

        val dayLabel = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(idea.createdAt))
        dayCounts[dayLabel] = (dayCounts[dayLabel] ?: 0) + 1

        val tickDurationSec = 60f / idea.tempoBpm.coerceAtLeast(1) / 4f
        for (note in parseIdeaNotes(idea.notes)) {
            if (note.isRest) continue
            val durationSec = note.durationTicks.coerceAtLeast(0) * tickDurationSec
            val chordSize = note.pitches.size
            chordSizeCounts[chordSize] = (chordSizeCounts[chordSize] ?: 0) + 1

            val primaryPos = note.safeTabPositions.firstOrNull() ?: GuitarUtils.fromMidi(note.pitches.first())
            addStringUsage(primaryPos?.first, primaryPos?.second, durationSec)

            if (note.isChord) {
                note.pitches.indices.forEach { chordIndex ->
                    if (chordIndex == 0) return@forEach // skip primary pitch
                    val pitch = note.pitches[chordIndex]
                    val stringFret = note.safeTabPositions.getOrNull(chordIndex) ?: GuitarUtils.fromMidi(pitch)
                    addStringUsage(stringFret?.first, stringFret?.second, durationSec)
                }
            }
        }
    }

    val mostUsedStringIndex = notesPerString.indices
        .maxByOrNull { notesPerString[it] }
        ?.takeIf { notesPerString[it] > 0 }

    val mostCommonFret = fretCounts.maxByOrNull { it.value }?.key
    val mostActiveDay = dayCounts.maxByOrNull { it.value }?.key ?: "-"
    val mostCommonChordSize = chordSizeCounts.maxByOrNull { it.value }?.key

    return StatsUiState(
        totalIdeas = ideas.size,
        totalNotes = totalNotes,
        totalDurationSec = totalDurationSec,
        averageTempoBpm = if (tempoCount > 0) tempoSum / tempoCount else 0,
        averageNotesPerIdea = if (ideas.isNotEmpty()) totalNotes.toFloat() / ideas.size else 0f,
        notesPerString = notesPerString,
        durationPerStringSec = durationPerStringSec,
        mostUsedStringIndex = mostUsedStringIndex,
        mostCommonFret = mostCommonFret,
        mostActiveDay = mostActiveDay,
        mostCommonChordSize = mostCommonChordSize
    )
}

private data class GroupScope(
    val id: String,
    val label: String,
    val ideas: List<MelodyIdea>
)

private sealed interface ScopeRow {
    data class Group(val group: GroupScope) : ScopeRow
    data class Idea(val idea: MelodyIdea) : ScopeRow
}



private const val GROUP_UNGROUPED = "__ungrouped__"

private val STATS_GROUP_COLORS = listOf(
    Color(0xFF4285F4),
    Color(0xFF34A853),
    Color(0xFFFBBC04),
    Color(0xFFEA4335),
    Color(0xFF8E24AA),
    Color(0xFF00ACC1),
    Color(0xFFFF7043),
    Color(0xFF5C6BC0),
    Color(0xFF43A047),
    Color(0xFFE91E63),
    Color(0xFF00897B),
    Color(0xFFFF6F00)
)

private fun statsGroupColor(groupId: String, overrides: Map<String, Int>): Color {
    if (groupId == GROUP_UNGROUPED) {
        return Color(0xFF9E9E9E)
    }
    val stored = overrides[groupId]
    if (stored != null) {
        return if (stored in STATS_GROUP_COLORS.indices) STATS_GROUP_COLORS[stored] else Color(stored)
    }
    return STATS_GROUP_COLORS[kotlin.math.abs(groupId.hashCode()) % STATS_GROUP_COLORS.size]
}

private fun compactEdgeLabel(label: String): String {
    val trimmed = label.trim()
    return if (trimmed.length <= 13) trimmed else trimmed.take(10) + "..."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatsViewModel = viewModel()
) {
    val context = LocalContext.current
    val ideas by viewModel.ideas.collectAsState()
    var selectedIdeaIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showScopeSelector by remember { mutableStateOf(false) }

    val groupColorOverrides = remember(ideas) {
        val prefs = context.getSharedPreferences("notenotes_group_colors", Context.MODE_PRIVATE)
        prefs.all.mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap()
    }

    // Keep only valid selections when data updates.
    LaunchedEffect(ideas) {
        selectedIdeaIds = selectedIdeaIds.filter { selectedId ->
            ideas.any { it.id == selectedId }
        }.toSet()
    }

    val scopedIdeas = remember(ideas, selectedIdeaIds) {
        if (selectedIdeaIds.isEmpty()) ideas
        else ideas.filter { it.id in selectedIdeaIds }
    }
    val stats = remember(scopedIdeas) { computeStatsForIdeas(scopedIdeas) }

    val selectedGroupsCount = remember(ideas, selectedIdeaIds) {
        if (selectedIdeaIds.isEmpty()) 0
        else ideas
            .filter { it.id in selectedIdeaIds }
            .map { it.groupId ?: GROUP_UNGROUPED }
            .toSet()
            .size
    }

    val selectionSummary = remember(ideas, selectedIdeaIds, selectedGroupsCount) {
        when {
            selectedIdeaIds.isEmpty() -> "All Ideas (${ideas.size})"
            else -> "${selectedIdeaIds.size} ideas across $selectedGroupsCount groups"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ideas.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No ideas yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                DrillDownCard(
                    selectionSummary = selectionSummary,
                    onOpenSelector = { showScopeSelector = true }
                )

                StatsSummaryCard(stats = stats)
                StringBarSection(
                    title = "String Usage",
                    subtitle = "Total notes per guitar string",
                    values = stats.notesPerString.map { it.toFloat() },
                    valueLabel = { value -> value.toInt().toString() }
                )
                StringBarSection(
                    title = "Duration by String",
                    subtitle = "Accumulated note time (seconds)",
                    values = stats.durationPerStringSec.toList(),
                    valueLabel = { value -> String.format("%.1fs", value) }
                )
                FunFactsCard(stats = stats)
            }
        }
    }

    if (showScopeSelector) {
        StatsScopeSelectorDialog(
            ideas = ideas,
            groupColorOverrides = groupColorOverrides,
            initiallySelectedIdeaIds = selectedIdeaIds,
            onDismiss = { showScopeSelector = false },
            onApply = { selected ->
                selectedIdeaIds = selected
                showScopeSelector = false
            }
        )
    }
}

@Composable
private fun DrillDownCard(
    selectionSummary: String,
    onOpenSelector: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Drill Down", style = MaterialTheme.typography.titleMedium)
            Text(
                "Select whole groups and refine with individual ideas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onOpenSelector,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scope: $selectionSummary", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Text(
                "Tip: long-press and drag to quickly multi-select.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun StatsScopeSelectorDialog(
    ideas: List<MelodyIdea>,
    groupColorOverrides: Map<String, Int>,
    initiallySelectedIdeaIds: Set<Long>,
    onDismiss: () -> Unit,
    onApply: (Set<Long>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.DATE_DESC) }
        var localSelection by remember(initiallySelectedIdeaIds) { mutableStateOf(initiallySelectedIdeaIds) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var previousSortMode by remember { mutableStateOf(sortMode) }
    LaunchedEffect(sortMode) {
        if (sortMode != previousSortMode) {
            previousSortMode = sortMode
            listState.animateScrollToItem(0)
        }
    }

fun sortedIdeas(input: List<MelodyIdea>): List<MelodyIdea> {
        val filtered = input.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                (it.groupName?.contains(query, ignoreCase = true) == true) ||
                formatStatsDate(it.createdAt).contains(query, ignoreCase = true)
        }
        return when (sortMode) {
            SortMode.DATE_DESC -> filtered.sortedByDescending { it.createdAt }
            SortMode.DATE_ASC -> filtered.sortedBy { it.createdAt }
            SortMode.TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
            SortMode.TITLE_ZA -> filtered.sortedByDescending { it.title.lowercase() }
            SortMode.RECENT -> filtered.sortedByDescending { it.lastOpenedAt ?: 0L }
        }
    }

    val groups = remember(ideas) {
        ideas
            .groupBy { it.groupId ?: GROUP_UNGROUPED }
            .map { (groupId, groupIdeas) ->
                val label = if (groupId == GROUP_UNGROUPED) "Ungrouped"
                else groupIdeas.firstOrNull()?.groupName?.takeIf { it.isNotBlank() } ?: "Group"
                GroupScope(groupId, label, groupIdeas)
            }
            .sortedBy { it.label.lowercase() }
    }

    val shownGroups = remember(groups, query, sortMode) {
        groups.mapNotNull { group ->
            val groupMatches = query.isBlank() || group.label.contains(query, ignoreCase = true)
            val ideasForGroup = if (groupMatches) sortedIdeas(group.ideas) else sortedIdeas(group.ideas)
            val visibleIdeas = if (groupMatches) ideasForGroup else ideasForGroup.filter {
                it.title.contains(query, ignoreCase = true) ||
                    formatStatsDate(it.createdAt).contains(query, ignoreCase = true)
            }
            if (visibleIdeas.isEmpty()) null else group.copy(ideas = visibleIdeas)
        }
    }

    val sortedVisibleIdeas = remember(ideas, query, sortMode) {
        sortedIdeas(ideas)
    }

    val rows = remember(shownGroups, sortedVisibleIdeas, sortMode) {
        if (sortMode == SortMode.RECENT) {
            sortedVisibleIdeas.map<MelodyIdea, ScopeRow> { idea -> ScopeRow.Idea(idea) }
        } else {
            buildList<ScopeRow> {
                shownGroups.forEach { group ->
                    add(ScopeRow.Group(group))
                    group.ideas.forEach { idea ->
                        add(ScopeRow.Idea(idea))
                    }
                }
            }
        }
    }

    val shownGroupsById = remember(shownGroups) { shownGroups.associateBy { it.id } }

    fun toggleIdea(id: Long) {
        val updated = localSelection.toMutableSet()
        if (!updated.add(id)) updated.remove(id)
        localSelection = updated
    }

    fun toggleGroup(groupId: String) {
        val group = shownGroupsById[groupId] ?: return
        val ids = group.ideas.map { it.id }
        val updated = localSelection.toMutableSet()
        val fullySelected = ids.isNotEmpty() && ids.all { it in updated }
        if (fullySelected) updated.removeAll(ids.toSet()) else updated.addAll(ids)
        localSelection = updated
    }

    fun rowKey(row: ScopeRow): String {
        return when (row) {
            is ScopeRow.Group -> "group:${row.group.id}"
            is ScopeRow.Idea -> "idea:${row.idea.id}"
        }
    }

    fun toggleByRowKey(key: String) {
        when {
            key.startsWith("group:") -> toggleGroup(key.removePrefix("group:"))
            key.startsWith("idea:") -> key.removePrefix("idea:").toLongOrNull()?.let { toggleIdea(it) }
        }
    }

AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Groups & Ideas") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchAndSortHeader(
                    searchQuery = query,
                    onSearchQueryChange = { query = it },
                    isSearchExpanded = true,
                    onSearchExpandedChange = { },
                    sortMode = sortMode,
                    onSortModeChange = { sortMode = it },
                    horizontalPadding = 0.dp,
                    searchPlaceholder = "Search groups or ideas"
                )

                DragSelectLazyColumn(
                    listState = listState,
                    haptic = androidx.compose.ui.platform.LocalHapticFeedback.current,
                    onKeySelected = { key -> (key as? String)?.let { toggleByRowKey(it) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    items(rows, key = { row -> rowKey(row) }) { row ->
                        when (row) {
                            is ScopeRow.Group -> {
                                val ideaIds = row.group.ideas.map { it.id }
                                val selectedCount = ideaIds.count { it in localSelection }
                                val fullySelected = selectedCount == ideaIds.size && ideaIds.isNotEmpty()
                                val groupColor = statsGroupColor(row.group.id, groupColorOverrides)

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggleGroup(row.group.id) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (fullySelected) groupColor.copy(alpha = 0.24f)
                                    else groupColor.copy(alpha = 0.12f),
                                    tonalElevation = 0.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(IntrinsicSize.Min)
                                            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(groupColor)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = groupColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Row(
                                            modifier = Modifier
                                                .weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = fullySelected,
                                                onCheckedChange = { toggleGroup(row.group.id) }
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = row.group.label,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "$selectedCount / ${row.group.ideas.size} selected",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is ScopeRow.Idea -> {
                                val selected = row.idea.id in localSelection
                                val ideaGroupId = row.idea.groupId ?: GROUP_UNGROUPED
                                val ideaGroupLabel = row.idea.groupName?.takeIf { it.isNotBlank() } ?: "Ungrouped"
                                val groupColor = statsGroupColor(ideaGroupId, groupColorOverrides)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = if (sortMode == SortMode.RECENT) 0.dp else 18.dp)
                                        .clickable { toggleIdea(row.idea.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else groupColor.copy(alpha = 0.14f),
                                    tonalElevation = if (selected) 2.dp else 0.dp
                                ) {
                                    if (sortMode == SortMode.RECENT) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(IntrinsicSize.Min),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(14.dp)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(groupColor.copy(alpha = 0.75f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = compactEdgeLabel(ideaGroupLabel),
                                                    color = Color.White,
                                                    fontSize = 8.sp,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .wrapContentSize(unbounded = true)
                                                        .graphicsLayer {
                                                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                                                            rotationZ = -90f
                                                            clip = false
                                                        }
                                                )
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = selected,
                                                    onCheckedChange = { toggleIdea(row.idea.id) }
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        row.idea.title,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        buildString {
                                                            append(formatStatsDate(row.idea.createdAt))
                                                            row.idea.groupName?.takeIf { it.isNotBlank() }?.let {
                                                                append(" | ")
                                                                append(it)
                                                            }
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = selected,
                                                onCheckedChange = { toggleIdea(row.idea.id) }
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    row.idea.title,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    buildString {
                                                        append(formatStatsDate(row.idea.createdAt))
                                                        row.idea.groupName?.takeIf { it.isNotBlank() }?.let {
                                                            append(" | ")
                                                            append(it)
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(localSelection) }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { localSelection = emptySet() }) { Text("All") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun formatStatsDate(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

@Composable
private fun StatsSummaryCard(stats: StatsUiState) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ideas", style = MaterialTheme.typography.labelSmall)
                Text(stats.totalIdeas.toString(), style = MaterialTheme.typography.titleLarge)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Total Notes", style = MaterialTheme.typography.labelSmall)
                Text(stats.totalNotes.toString(), style = MaterialTheme.typography.titleMedium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Avg Tempo", style = MaterialTheme.typography.labelSmall)
                Text("${stats.averageTempoBpm} BPM", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StringBarSection(
    title: String,
    subtitle: String,
    values: List<Float>,
    valueLabel: (Float) -> String
) {
    val maxValue = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            GuitarUtils.STRINGS.forEachIndexed { index, guitarString ->
                val value = values.getOrNull(index) ?: 0f
                val fraction = (value / maxValue).coerceIn(0f, 1f)
                val stringColor = Color(guitarString.colorArgb)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = guitarString.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(36.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .clip(RoundedCornerShape(99.dp))
                                .background(stringColor)
                        )
                    }
                    Text(
                        text = valueLabel(value),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(56.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FunFactsCard(stats: StatsUiState) {
    val mostUsedString = stats.mostUsedStringIndex
        ?.takeIf { it in GuitarUtils.STRINGS.indices }
        ?.let { GuitarUtils.STRINGS[it].name }
        ?: "-"

    val mostCommonFret = stats.mostCommonFret?.toString() ?: "-"
    val chordSize = stats.mostCommonChordSize?.toString() ?: "-"

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Fun Facts", style = MaterialTheme.typography.titleMedium)
            Text("Most used string: $mostUsedString", style = MaterialTheme.typography.bodyMedium)
            Text("Most common fret: $mostCommonFret", style = MaterialTheme.typography.bodyMedium)
            Text("Most active day: ${stats.mostActiveDay}", style = MaterialTheme.typography.bodyMedium)
            Text("Avg notes per idea: ${String.format("%.1f", stats.averageNotesPerIdea)}", style = MaterialTheme.typography.bodyMedium)
            Text("Most common chord size: $chordSize", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Total played time: ${String.format("%.1fs", stats.totalDurationSec)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
