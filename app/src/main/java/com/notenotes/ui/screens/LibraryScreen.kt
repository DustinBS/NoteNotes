package com.notenotes.ui.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notenotes.data.AppDatabase
import com.notenotes.export.NntFile
import com.notenotes.model.MelodyIdea
import com.notenotes.model.NntTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ──────────────────────── Stable Group Colors ────────────────────────

private val GROUP_COLORS = listOf(
    Color(0xFF4285F4), // Blue
    Color(0xFF34A853), // Green
    Color(0xFFFBBC04), // Yellow
    Color(0xFFEA4335), // Red
    Color(0xFF8E24AA), // Purple
    Color(0xFF00ACC1), // Cyan
    Color(0xFFFF7043), // Deep Orange
    Color(0xFF5C6BC0), // Indigo
    Color(0xFF43A047), // Green 600
    Color(0xFFE91E63), // Pink
    Color(0xFF00897B), // Teal
    Color(0xFFFF6F00), // Amber
)

/** Resolve the colour for a group, checking SharedPrefs override first, then falling back to hash. */
private fun groupColor(groupId: String, overrides: Map<String, Int>): Color {
    val stored = overrides[groupId]
    if (stored != null) {
        // Legacy values 0..11 are palette indices; everything else is an ARGB int
        return if (stored in GROUP_COLORS.indices) GROUP_COLORS[stored] else Color(stored)
    }
    return GROUP_COLORS[abs(groupId.hashCode()) % GROUP_COLORS.size]
}

/** Generate a random vibrant colour whose hue is far from any existing group. */
private fun pickRandomColor(existingArgbs: Collection<Int>): Color {
    val rng = Random()
    val existingHues = existingArgbs.map { v ->
        val argb = if (v in GROUP_COLORS.indices) GROUP_COLORS[v].toArgb() else v
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        hsv[0]
    }
    var bestHue = rng.nextFloat() * 360f
    if (existingHues.isNotEmpty()) {
        var bestDist = 0f
        repeat(20) {
            val h = rng.nextFloat() * 360f
            val minDist = existingHues.minOf { eh -> val d = abs(h - eh); minOf(d, 360f - d) }
            if (minDist > bestDist) { bestDist = minDist; bestHue = h }
        }
    }
    return Color.hsv(bestHue, 0.65f + rng.nextFloat() * 0.25f, 0.75f + rng.nextFloat() * 0.15f)
}

// ──────────────────────── Sort Modes ────────────────────────

enum class SortMode(val label: String) {
    DATE_DESC("Newest"),
    DATE_ASC("Oldest"),
    TITLE_AZ("A → Z"),
    TITLE_ZA("Z → A")
}

// ──────────────────────────── ViewModel ────────────────────────────

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).melodyDao()
    private val prefs = application.getSharedPreferences("notenotes_group_colors", Context.MODE_PRIVATE)
    val ideas = dao.getAllIdeas()
    val trashIdeas = dao.getTrashIdeas()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /** Currently multi-selected idea IDs (for grouping). */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds

    /** Collapsed group IDs. */
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    val collapsedGroups: StateFlow<Set<String>> = _collapsedGroups

    private val _sortMode = MutableStateFlow(SortMode.DATE_DESC)
    val sortMode: StateFlow<SortMode> = _sortMode

    /** groupId → color index overrides (persisted to SharedPrefs). */
    private val _groupColorOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    val groupColorOverrides: StateFlow<Map<String, Int>> = _groupColorOverrides

    init {
        // Purge trash items older than 30 days on every launch
        viewModelScope.launch {
            val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            dao.purgeOldTrash(thirtyDaysAgo)
        }
        // Load persisted group colour overrides
        _groupColorOverrides.value = prefs.all.mapNotNull { (key, value) ->
            (value as? Int)?.let { key to it }
        }.toMap()
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSortMode(mode: SortMode) { _sortMode.value = mode }

    // ── Selection ──

    fun toggleSelection(id: Long) {
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (contains(id)) remove(id) else add(id)
        }
    }

    fun clearSelection() { _selectedIds.value = emptySet() }

    fun selectAll(ideas: List<MelodyIdea>) {
        _selectedIds.value = ideas.map { it.id }.toSet()
    }

    // ── Grouping ──

    fun groupSelected(name: String) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        val groupId = UUID.randomUUID().toString()
        val color = pickRandomColor(_groupColorOverrides.value.values)
        setGroupColor(groupId, color)
        viewModelScope.launch {
            dao.setGroup(ids, groupId, name.ifBlank { "Group" })
            _selectedIds.value = emptySet()
        }
    }

    fun moveToGroup(ideaIds: List<Long>, groupId: String, groupName: String) {
        viewModelScope.launch {
            dao.setGroup(ideaIds, groupId, groupName)
            _selectedIds.value = emptySet()
        }
    }

    /** Move items into a brand-new group (from bottom sheet "New Group" flow). */
    fun moveToNewGroup(ideaIds: List<Long>, groupName: String): String {
        val groupId = UUID.randomUUID().toString()
        val color = pickRandomColor(_groupColorOverrides.value.values)
        setGroupColor(groupId, color)
        viewModelScope.launch {
            dao.setGroup(ideaIds, groupId, groupName.ifBlank { "Group" })
            _selectedIds.value = emptySet()
        }
        return groupId
    }

    /** Persist a colour override for the given group. */
    fun setGroupColor(groupId: String, color: Color) {
        val argb = color.toArgb()
        _groupColorOverrides.value = _groupColorOverrides.value + (groupId to argb)
        prefs.edit().putInt(groupId, argb).apply()
    }

    fun removeFromGroup(ideaId: Long) {
        viewModelScope.launch { dao.ungroup(listOf(ideaId)) }
    }

    fun ungroupIdeas(groupId: String, ideas: List<MelodyIdea>) {
        val ids = ideas.filter { it.groupId == groupId }.map { it.id }
        viewModelScope.launch { dao.ungroup(ids) }
    }

    fun renameGroup(groupId: String, newName: String, ideas: List<MelodyIdea>) {
        val ids = ideas.filter { it.groupId == groupId }.map { it.id }
        viewModelScope.launch { dao.setGroup(ids, groupId, newName.ifBlank { "Group" }) }
    }

    fun toggleGroupExpanded(groupId: String) {
        _collapsedGroups.value = _collapsedGroups.value.toMutableSet().apply {
            if (contains(groupId)) remove(groupId) else add(groupId)
        }
    }

    // ── Rename ──

    fun renameIdea(id: Long, newTitle: String) {
        viewModelScope.launch { dao.rename(id, newTitle) }
    }

    // ── Soft-delete / Trash ──

    fun softDeleteIdea(idea: MelodyIdea) {
        viewModelScope.launch {
            dao.softDelete(idea.id, System.currentTimeMillis())
        }
    }

    fun softDeleteSelected() {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            _selectedIds.value.forEach { dao.softDelete(it, now) }
            _selectedIds.value = emptySet()
        }
    }

    fun restoreIdea(idea: MelodyIdea) {
        viewModelScope.launch { dao.restore(idea.id) }
    }

    fun permanentlyDeleteIdea(idea: MelodyIdea) {
        viewModelScope.launch {
            dao.delete(idea)
            idea.audioFilePath.let { java.io.File(it).delete() }
            idea.midiFilePath?.let { java.io.File(it).delete() }
            idea.musicXmlFilePath?.let { java.io.File(it).delete() }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch { dao.emptyTrash() }
    }

    // ── Import helpers ──

    /** Determine if a URI points to a ZIP file using MIME, display name, path, and magic bytes. */
    private fun isZipUri(context: Context, uri: Uri): Boolean {
        // 1. Check MIME type
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: ""
        if (mime in listOf("application/zip", "application/x-zip-compressed", "application/x-zip",
                           "application/octet-stream")) {
            // application/octet-stream: could be ZIP, fall through to name check
            if (mime != "application/octet-stream") return true
        }

        // 2. Check display name from ContentResolver
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0) ?: ""
                    if (displayName.endsWith(".zip", ignoreCase = true)) return true
                }
            }
        } catch (_: Exception) {}

        // 3. Check URI path segments
        val pathName = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        if (pathName.endsWith(".zip", ignoreCase = true)) return true

        // 4. Check ZIP magic bytes (PK\x03\x04)
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(4)
                if (stream.read(header) == 4) {
                    if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /** Get display name for a URI, for logging/error messages. */
    private fun getDisplayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: uri.toString()
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    /** Public version for composable callers. */
    fun getDisplayNamePublic(context: Context, uri: Uri): String = getDisplayName(context, uri)

    // ── Duplicate ──

    /** Duplicate an idea: copies files and DB row, keeps same group. */
    fun duplicateIdea(idea: MelodyIdea) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ts = System.currentTimeMillis()
                val audioDir = java.io.File(idea.audioFilePath).parentFile ?: return@launch

                // Copy audio
                val srcAudio = java.io.File(idea.audioFilePath)
                val ext = srcAudio.extension.ifEmpty { "wav" }
                val destAudio = java.io.File(audioDir, "dup_${ts}.$ext")
                if (srcAudio.exists()) srcAudio.copyTo(destAudio)
                else destAudio.createNewFile()

                // Copy MIDI
                var destMidiPath: String? = null
                idea.midiFilePath?.let { path ->
                    val src = java.io.File(path)
                    if (src.exists()) {
                        val dest = java.io.File(audioDir, "dup_${ts}.mid")
                        src.copyTo(dest)
                        destMidiPath = dest.absolutePath
                    }
                }

                // Copy MusicXML
                var destXmlPath: String? = null
                idea.musicXmlFilePath?.let { path ->
                    val src = java.io.File(path)
                    if (src.exists()) {
                        val dest = java.io.File(audioDir, "dup_${ts}.musicxml")
                        src.copyTo(dest)
                        destXmlPath = dest.absolutePath
                    }
                }

                val copy = MelodyIdea(
                    title = idea.title,
                    createdAt = ts,
                    audioFilePath = destAudio.absolutePath,
                    midiFilePath = destMidiPath,
                    musicXmlFilePath = destXmlPath,
                    instrument = idea.instrument,
                    tempoBpm = idea.tempoBpm,
                    keySignature = idea.keySignature,
                    timeSignature = idea.timeSignature,
                    notes = idea.notes,
                    groupId = idea.groupId,
                    groupName = idea.groupName
                )
                dao.insert(copy)
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "Duplicate failed", e)
            }
        }
    }

    /** Replace the audio and/or MusicXML files of an existing idea. */
    fun replaceIdeaFiles(context: Context, ideaId: Long, audioUri: Uri?, xmlUri: Uri?) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val idea = dao.getIdeaById(ideaId) ?: return@launch
                val audioDir = java.io.File(context.filesDir, "audio")
                audioDir.mkdirs()
                val ts = System.currentTimeMillis()

                var newAudioPath = idea.audioFilePath
                var newXmlPath = idea.musicXmlFilePath
                var newNotes = idea.notes
                var newInstrument = idea.instrument
                var newTempo = idea.tempoBpm
                var newKey = idea.keySignature
                var newTime = idea.timeSignature

                if (audioUri != null) {
                    val name = getDisplayName(context, audioUri)
                    val ext = name.substringAfterLast('.', "wav").lowercase()
                    val dest = java.io.File(audioDir, "replace_${ts}.$ext")
                    context.contentResolver.openInputStream(audioUri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    newAudioPath = dest.absolutePath
                }

                if (xmlUri != null) {
                    val name = getDisplayName(context, xmlUri)
                    val ext = name.substringAfterLast('.', "").lowercase()

                    if (ext == NntTranscription.FILE_EXTENSION) {
                        // NNT file — apply notes + metadata directly
                        val nnt = context.contentResolver.openInputStream(xmlUri)?.use { input ->
                            NntFile.read(input)
                        }
                        if (nnt != null) {
                            val gson = com.google.gson.Gson()
                            newNotes = gson.toJson(nnt.notes)
                            newInstrument = nnt.instrument
                            newTempo = nnt.tempoBpm
                            newKey = nnt.keySignature
                            newTime = nnt.timeSignature
                            // No XML file needed — notes are stored directly
                        }
                    } else {
                        // MusicXML file — copy and clear notes so loadIdea() parses it
                        val dest = java.io.File(audioDir, "replace_${ts}.musicxml")
                        context.contentResolver.openInputStream(xmlUri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        newXmlPath = dest.absolutePath
                        newNotes = null  // force re-parse from XML on next load
                    }
                }

                val updated = idea.copy(
                    audioFilePath = newAudioPath,
                    musicXmlFilePath = newXmlPath,
                    notes = newNotes,
                    instrument = newInstrument,
                    tempoBpm = newTempo,
                    keySignature = newKey,
                    timeSignature = newTime
                )
                dao.update(updated)
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "Replace files failed", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Replace failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Build a new idea from user-provided audio (required) and optional transcription file. */
    fun buildIdea(context: Context, audioUri: Uri, xmlUri: Uri?, onDone: (Long) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val audioDir = java.io.File(context.filesDir, "audio")
                audioDir.mkdirs()
                val ts = System.currentTimeMillis()

                val audioName = getDisplayName(context, audioUri)
                val audioExt = audioName.substringAfterLast('.', "wav").lowercase()
                val audioDest = java.io.File(audioDir, "build_${ts}.$audioExt")
                context.contentResolver.openInputStream(audioUri)?.use { input ->
                    audioDest.outputStream().use { output -> input.copyTo(output) }
                }

                val title = audioName.substringAfterLast(':').substringBeforeLast('.')

                var xmlPath: String? = null
                var notes: String? = null
                var instrument = "piano"
                var tempoBpm = 120
                var keySignature: String? = null
                var timeSignature: String? = null

                if (xmlUri != null) {
                    val xmlName = getDisplayName(context, xmlUri)
                    val xmlExt = xmlName.substringAfterLast('.', "").lowercase()

                    if (xmlExt == NntTranscription.FILE_EXTENSION) {
                        // NNT file — apply notes + metadata directly
                        val nnt = context.contentResolver.openInputStream(xmlUri)?.use { input ->
                            NntFile.read(input)
                        }
                        if (nnt != null) {
                            val gson = com.google.gson.Gson()
                            notes = gson.toJson(nnt.notes)
                            instrument = nnt.instrument
                            tempoBpm = nnt.tempoBpm
                            keySignature = nnt.keySignature
                            timeSignature = nnt.timeSignature
                        }
                    } else {
                        // MusicXML file — copy so loadIdea() can parse it
                        val xmlDest = java.io.File(audioDir, "build_${ts}.musicxml")
                        context.contentResolver.openInputStream(xmlUri)?.use { input ->
                            xmlDest.outputStream().use { output -> input.copyTo(output) }
                        }
                        xmlPath = xmlDest.absolutePath
                    }
                }

                val idea = MelodyIdea(
                    title = title,
                    createdAt = ts,
                    audioFilePath = audioDest.absolutePath,
                    musicXmlFilePath = xmlPath,
                    notes = notes,
                    instrument = instrument,
                    tempoBpm = tempoBpm,
                    keySignature = keySignature,
                    timeSignature = timeSignature
                )
                val newId = dao.insert(idea)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onDone(newId)
                }
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "Build idea failed", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Build failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Import an idea from picked files (audio, musicxml, midi, or .notenotes.zip snapshot). */
    fun importIdea(context: Context, uris: List<Uri>, onDone: (Long) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Check if any URI is a snapshot ZIP — use multiple detection strategies
                val zipUri = uris.firstOrNull { uri -> isZipUri(context, uri) }

                if (zipUri != null) {
                    android.util.Log.d("LibraryVM", "Detected snapshot ZIP: $zipUri")
                    importSnapshot(context, zipUri, onDone)
                    return@launch
                }

                val audioDir = java.io.File(context.filesDir, "audio")
                audioDir.mkdirs()
                val ts = System.currentTimeMillis()

                var audioPath: String? = null
                var xmlPath: String? = null
                var midiPath: String? = null
                var nntNotes: String? = null
                var nntInstrument: String? = null
                var nntTempo: Int? = null
                var nntKey: String? = null
                var nntTime: String? = null
                var title = "Imported Idea"

                for (uri in uris) {
                    val name = getDisplayName(context, uri)
                    val cleanName = name.substringAfterLast(':')  // strip SAF prefix
                    val ext = cleanName.substringAfterLast('.', "").lowercase()

                    when (ext) {
                        "wav", "mp3", "m4a", "ogg", "aac", "flac" -> {
                            val dest = java.io.File(audioDir, "import_${ts}.$ext")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                            audioPath = dest.absolutePath
                            title = cleanName.substringBeforeLast('.')
                        }
                        "musicxml", "xml" -> {
                            val dest = java.io.File(audioDir, "import_${ts}.musicxml")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                            xmlPath = dest.absolutePath
                            if (title == "Imported Idea") title = cleanName.substringBeforeLast('.')
                        }
                        "mid", "midi" -> {
                            val dest = java.io.File(audioDir, "import_${ts}.mid")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                            midiPath = dest.absolutePath
                            if (title == "Imported Idea") title = cleanName.substringBeforeLast('.')
                        }
                        NntTranscription.FILE_EXTENSION -> {
                            // NNT transcription — parse notes + metadata directly
                            val nnt = context.contentResolver.openInputStream(uri)?.use { input ->
                                NntFile.read(input)
                            }
                            if (nnt != null) {
                                val gson = com.google.gson.Gson()
                                nntNotes = gson.toJson(nnt.notes)
                                nntInstrument = nnt.instrument
                                nntTempo = nnt.tempoBpm
                                nntKey = nnt.keySignature
                                nntTime = nnt.timeSignature
                            }
                            if (title == "Imported Idea") title = cleanName.substringBeforeLast('.')
                        }
                    }
                }

                if (audioPath == null && xmlPath == null && midiPath == null && nntNotes == null) return@launch

                // If no audio file, create a placeholder path
                val finalAudioPath = audioPath ?: java.io.File(audioDir, "import_${ts}_placeholder.wav").also {
                    it.createNewFile()
                }.absolutePath

                val idea = MelodyIdea(
                    title = title,
                    createdAt = ts,
                    audioFilePath = finalAudioPath,
                    musicXmlFilePath = xmlPath,
                    midiFilePath = midiPath,
                    notes = nntNotes,
                    instrument = nntInstrument ?: "piano",
                    tempoBpm = nntTempo ?: 120,
                    keySignature = nntKey,
                    timeSignature = nntTime
                )
                val newId = dao.insert(idea)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onDone(newId)
                }
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "Import failed", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Import a .notenotes.zip snapshot — extracts audio, musicxml, midi and metadata. */
    private suspend fun importSnapshot(context: Context, zipUri: Uri, onDone: (Long) -> Unit) {
        val audioDir = java.io.File(context.filesDir, "audio")
        audioDir.mkdirs()
        val ts = System.currentTimeMillis()

        var audioPath: String? = null
        var xmlPath: String? = null
        var midiPath: String? = null
        var metaJson: String? = null
        var entriesRead = 0

        val inputStream = context.contentResolver.openInputStream(zipUri)
        if (inputStream == null) {
            android.util.Log.e("LibraryVM", "Failed to open input stream for ZIP: $zipUri")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Cannot read file", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        inputStream.use { input ->
            java.util.zip.ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    android.util.Log.d("LibraryVM", "ZIP entry: $entryName (size=${entry.size})")
                    entriesRead++
                    when {
                        entryName == "metadata.json" -> {
                            metaJson = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        entryName.startsWith("audio.") -> {
                            val ext = entryName.substringAfterLast('.', "wav")
                            val dest = java.io.File(audioDir, "import_${ts}.$ext")
                            dest.outputStream().use { out -> zis.copyTo(out) }
                            audioPath = dest.absolutePath
                        }
                        entryName.endsWith(".${NntTranscription.FILE_EXTENSION}") -> {
                            // NNT transcription in ZIP — notes will be applied from
                            // metadata.json which already has the full notes JSON
                            // (this entry exists for standalone extraction)
                        }
                        entryName.endsWith(".musicxml") -> {
                            val dest = java.io.File(audioDir, "import_${ts}.musicxml")
                            dest.outputStream().use { out -> zis.copyTo(out) }
                            xmlPath = dest.absolutePath
                        }
                        entryName.endsWith(".mid") -> {
                            val dest = java.io.File(audioDir, "import_${ts}.mid")
                            dest.outputStream().use { out -> zis.copyTo(out) }
                            midiPath = dest.absolutePath
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        android.util.Log.d("LibraryVM", "ZIP import: entries=$entriesRead, meta=${metaJson != null}, audio=$audioPath, xml=$xmlPath, midi=$midiPath")

        if (entriesRead == 0) {
            android.util.Log.e("LibraryVM", "ZIP contained no entries")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "ZIP file appears empty", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Parse metadata
        val gson = com.google.gson.Gson()
        val meta: Map<String, Any?> = if (metaJson != null) {
            gson.fromJson(metaJson, object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type)
        } else emptyMap()

        val title = (meta["title"] as? String) ?: "Imported Snapshot"
        val finalAudioPath = audioPath ?: java.io.File(audioDir, "import_${ts}_placeholder.wav").also {
            it.createNewFile()
        }.absolutePath

        val idea = com.notenotes.model.MelodyIdea(
            title = title,
            createdAt = ts,
            audioFilePath = finalAudioPath,
            musicXmlFilePath = xmlPath,
            midiFilePath = midiPath,
            instrument = (meta["instrument"] as? String) ?: "piano",
            tempoBpm = (meta["tempoBpm"] as? Number)?.toInt() ?: 120,
            keySignature = meta["keySignature"] as? String,
            timeSignature = meta["timeSignature"] as? String,
            notes = meta["notes"] as? String,
            groupId = meta["groupId"] as? String,
            groupName = meta["groupName"] as? String
        )
        val newId = dao.insert(idea)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            onDone(newId)
        }
    }
}

// ──────────────────────────── Screen ────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onNavigateToPreview: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToRecord: () -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val ideas by viewModel.ideas.collectAsState(initial = emptyList())
    val trashIdeas by viewModel.trashIdeas.collectAsState(initial = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val collapsedGroups by viewModel.collapsedGroups.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val colorOverrides by viewModel.groupColorOverrides.collectAsState()
    val haptic = LocalHapticFeedback.current

    // ── Import file picker ──
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importIdea(context = viewModel.getApplication<Application>(), uris = uris) { newId ->
                onNavigateToPreview(newId)
            }
        }
    }
    val context = viewModel.getApplication<Application>() as Context

    var showingTrash by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showMoveToGroupSheet by remember { mutableStateOf(false) }
    // Track which single idea is pending a "Move to Group" action
    var moveToGroupTargetId by remember { mutableStateOf<Long?>(null) }

    // ── Edit Files state ──
    var editFilesTargetId by remember { mutableStateOf<Long?>(null) }
    var editAudioUri by remember { mutableStateOf<Uri?>(null) }
    var editAudioName by remember { mutableStateOf<String?>(null) }
    var editXmlUri by remember { mutableStateOf<Uri?>(null) }
    var editXmlName by remember { mutableStateOf<String?>(null) }

    val editAudioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            editAudioUri = uri
            editAudioName = viewModel.getDisplayNamePublic(context, uri)
        }
    }
    val editXmlPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            editXmlUri = uri
            editXmlName = viewModel.getDisplayNamePublic(context, uri)
        }
    }

    // ── Build Idea state ──
    var showBuildDialog by remember { mutableStateOf(false) }
    var buildAudioUri by remember { mutableStateOf<Uri?>(null) }
    var buildAudioName by remember { mutableStateOf<String?>(null) }
    var buildXmlUri by remember { mutableStateOf<Uri?>(null) }
    var buildXmlName by remember { mutableStateOf<String?>(null) }

    val buildAudioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            buildAudioUri = uri
            buildAudioName = viewModel.getDisplayNamePublic(context, uri)
        }
    }
    val buildXmlPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            buildXmlUri = uri
            buildXmlName = viewModel.getDisplayNamePublic(context, uri)
        }
    }

    // Back button: selection mode → clear selection, trash view → library, else → navigate back
    BackHandler(enabled = selectedIds.isNotEmpty() || showingTrash) {
        when {
            selectedIds.isNotEmpty() -> viewModel.clearSelection()
            showingTrash -> showingTrash = false
        }
    }

    // Apply sort + filter
    val filteredIdeas = remember(ideas, searchQuery, sortMode) {
        val base = if (searchQuery.isBlank()) ideas
        else ideas.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.groupName?.contains(searchQuery, ignoreCase = true) == true
        }
        when (sortMode) {
            SortMode.DATE_DESC -> base.sortedByDescending { it.createdAt }
            SortMode.DATE_ASC -> base.sortedBy { it.createdAt }
            SortMode.TITLE_AZ -> base.sortedBy { it.title.lowercase() }
            SortMode.TITLE_ZA -> base.sortedByDescending { it.title.lowercase() }
        }
    }

    val grouped = filteredIdeas.filter { it.groupId != null }.groupBy { it.groupId!! }
    val ungrouped = filteredIdeas.filter { it.groupId == null }

    // Available groups for "Move to Group" picker
    val availableGroups: Map<String, String> = remember(ideas) {
        ideas.filter { it.groupId != null }
            .groupBy { it.groupId!! }
            .mapValues { (_, items) -> items.firstOrNull()?.groupName ?: "Group" }
    }

    // ── Group dialog (create new) ──
    if (showGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text("Name this group") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = { Text("e.g. Song Ideas, Riffs...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (moveToGroupTargetId != null) {
                        // Single item "New Group" from bottom sheet
                        viewModel.moveToNewGroup(listOf(moveToGroupTargetId!!), groupName)
                        moveToGroupTargetId = null
                    } else {
                        viewModel.groupSelected(groupName)
                    }
                    showGroupDialog = false
                }) { Text("Create Group") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGroupDialog = false
                    moveToGroupTargetId = null
                }) { Text("Cancel") }
            }
        )
    }

    // ── Empty trash dialog ──
    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = { Text("Empty Trash?") },
            text = { Text("This will permanently delete all ${trashIdeas.size} items and their files. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.emptyTrash()
                    showEmptyTrashDialog = false
                }) { Text("Empty", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Edit Files dialog ──
    if (editFilesTargetId != null) {
        AlertDialog(
            onDismissRequest = {
                editFilesTargetId = null; editAudioUri = null; editAudioName = null
                editXmlUri = null; editXmlName = null
            },
            title = { Text("Edit Files") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Replace the audio or transcription files for this idea.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Audio file picker
                    OutlinedButton(
                        onClick = { editAudioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AudioFile, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(editAudioName ?: "Select Audio File (optional)")
                    }
                    // XML file picker
                    OutlinedButton(
                        onClick = { editXmlPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(editXmlName ?: "Select Transcription File (optional)")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editFilesTargetId?.let { id ->
                            viewModel.replaceIdeaFiles(context, id, editAudioUri, editXmlUri)
                        }
                        editFilesTargetId = null; editAudioUri = null; editAudioName = null
                        editXmlUri = null; editXmlName = null
                    },
                    enabled = editAudioUri != null || editXmlUri != null
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editFilesTargetId = null; editAudioUri = null; editAudioName = null
                    editXmlUri = null; editXmlName = null
                }) { Text("Cancel") }
            }
        )
    }

    // ── Build Idea dialog ──
    if (showBuildDialog) {
        AlertDialog(
            onDismissRequest = {
                showBuildDialog = false; buildAudioUri = null; buildAudioName = null
                buildXmlUri = null; buildXmlName = null
            },
            title = { Text("Build Idea") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Create a new idea from an audio recording. Optionally attach a transcription file (.nnt or .musicxml).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Audio file picker (required)
                    OutlinedButton(
                        onClick = { buildAudioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AudioFile, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(buildAudioName ?: "Select Audio File *")
                    }
                    if (buildAudioName != null) {
                        Text(
                            "\u2713 ${buildAudioName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // XML file picker (optional)
                    OutlinedButton(
                        onClick = { buildXmlPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(buildXmlName ?: "Select Transcription (optional)")
                    }
                    if (buildXmlName != null) {
                        Text(
                            "\u2713 ${buildXmlName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        buildAudioUri?.let { audioUri ->
                            viewModel.buildIdea(context, audioUri, buildXmlUri) { newId ->
                                onNavigateToPreview(newId)
                            }
                        }
                        showBuildDialog = false; buildAudioUri = null; buildAudioName = null
                        buildXmlUri = null; buildXmlName = null
                    },
                    enabled = buildAudioUri != null
                ) { Text("Build") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBuildDialog = false; buildAudioUri = null; buildAudioName = null
                    buildXmlUri = null; buildXmlName = null
                }) { Text("Cancel") }
            }
        )
    }

    // ── Move to Group bottom sheet ──
    if (showMoveToGroupSheet) {
        val idsToMove = if (moveToGroupTargetId != null) listOf(moveToGroupTargetId!!)
        else selectedIds.toList()

        MoveToGroupSheet(
            groups = availableGroups,
            colorOverrides = colorOverrides,
            onDismiss = {
                showMoveToGroupSheet = false
                moveToGroupTargetId = null
            },
            onSelectGroup = { groupId, groupName ->
                viewModel.moveToGroup(idsToMove, groupId, groupName)
                showMoveToGroupSheet = false
                moveToGroupTargetId = null
            },
            onCreateNew = {
                showMoveToGroupSheet = false
                showGroupDialog = true
            }
        )
    }

    Scaffold(
        topBar = {
            if (selectedIds.isNotEmpty()) {
                // Selection mode top bar
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll(filteredIdeas) }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(onClick = { showGroupDialog = true }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New Group")
                        }
                        if (availableGroups.isNotEmpty()) {
                            IconButton(onClick = {
                                moveToGroupTargetId = null
                                showMoveToGroupSheet = true
                            }) {
                                Icon(Icons.Filled.DriveFileMove, contentDescription = "Move to Group")
                            }
                        }
                        IconButton(onClick = { viewModel.softDeleteSelected() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete Selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(if (showingTrash) "Trash" else "Library") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (showingTrash) showingTrash = false else onNavigateBack()
                        }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (!showingTrash) {
                            IconButton(onClick = { showBuildDialog = true }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Construction, contentDescription = "Build", modifier = Modifier.size(20.dp))
                                    Text("Build", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                }
                            }
                            IconButton(onClick = {
                                importLauncher.launch(arrayOf("*/*"))
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.FileOpen, contentDescription = "Import", modifier = Modifier.size(20.dp))
                                    Text("Import", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                }
                            }
                            IconButton(
                                onClick = { showingTrash = true },
                                modifier = Modifier.size(52.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    BadgedBox(
                                        badge = {
                                            if (trashIdeas.isNotEmpty()) {
                                                Badge { Text("${trashIdeas.size}") }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Trash", modifier = Modifier.size(20.dp))
                                    }
                                    Text("Trash", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                }
                            }
                        } else if (trashIdeas.isNotEmpty()) {
                            IconButton(onClick = { showEmptyTrashDialog = true }, modifier = Modifier.size(52.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.DeleteForever, contentDescription = "Empty Trash", modifier = Modifier.size(20.dp))
                                    Text("Empty", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showingTrash) {
                // ── Trash view ──
                if (trashIdeas.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.DeleteOutline,
                        message = "Trash is empty",
                        subtitle = "Deleted ideas appear here for 30 days"
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Items are automatically deleted after 30 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showEmptyTrashDialog = true }) {
                            Text("Empty", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(trashIdeas, key = { it.id }) { idea ->
                            TrashCard(
                                idea = idea,
                                onRestore = { viewModel.restoreIdea(idea) },
                                onDeletePermanently = { viewModel.permanentlyDeleteIdea(idea) }
                            )
                        }
                    }
                }
            } else {
                // ── Active library ──
                if (selectedIds.isEmpty()) {
                    // Search bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search ideas or folders...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true
                    )

                    // Sort chips
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortMode.entries.forEach { mode ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick = { viewModel.setSortMode(mode) },
                                label = { Text(mode.label) },
                                leadingIcon = if (sortMode == mode) {
                                    { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (filteredIdeas.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.MusicOff,
                        message = if (searchQuery.isNotBlank()) "No matching ideas" else "No ideas yet",
                        subtitle = if (searchQuery.isBlank()) "Record your first melody!" else null
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Grouped ideas
                        grouped.forEach { (groupId, groupIdeas) ->
                            val groupName = groupIdeas.firstOrNull()?.groupName ?: "Group"
                            val isExpanded = groupId !in collapsedGroups
                            val gColor = groupColor(groupId, colorOverrides)

                            item(key = "group_header_$groupId") {
                                GroupHeader(
                                    name = groupName,
                                    count = groupIdeas.size,
                                    isExpanded = isExpanded,
                                    accentColor = gColor,
                                    onToggleExpand = { viewModel.toggleGroupExpanded(groupId) },
                                    onUngroup = { viewModel.ungroupIdeas(groupId, groupIdeas) },
                                    onRename = { newName -> viewModel.renameGroup(groupId, newName, groupIdeas) },
                                    onRecolor = { color -> viewModel.setGroupColor(groupId, color) }
                                )
                            }
                            if (isExpanded) {
                                items(groupIdeas, key = { it.id }) { idea ->
                                    IdeaCard(
                                        idea = idea,
                                        isSelected = idea.id in selectedIds,
                                        isSelecting = selectedIds.isNotEmpty(),
                                        accentColor = gColor,
                                        availableGroups = availableGroups,
                                        onClick = {
                                            if (selectedIds.isNotEmpty()) viewModel.toggleSelection(idea.id)
                                            else onNavigateToPreview(idea.id)
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.toggleSelection(idea.id)
                                        },
                                        onDelete = { viewModel.softDeleteIdea(idea) },
                                        onRename = { newTitle -> viewModel.renameIdea(idea.id, newTitle) },
                                        onDuplicate = { viewModel.duplicateIdea(idea) },
                                        onEditFiles = { editFilesTargetId = idea.id },
                                        onRemoveFromGroup = { viewModel.removeFromGroup(idea.id) },
                                        onMoveToGroup = {
                                            moveToGroupTargetId = idea.id
                                            showMoveToGroupSheet = true
                                        }
                                    )
                                }
                            }
                        }

                        // Ungrouped ideas
                        if (ungrouped.isNotEmpty() && grouped.isNotEmpty()) {
                            item(key = "ungrouped_header") {
                                Text(
                                    "Ungrouped",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                        }
                        items(ungrouped, key = { it.id }) { idea ->
                            IdeaCard(
                                idea = idea,
                                isSelected = idea.id in selectedIds,
                                isSelecting = selectedIds.isNotEmpty(),
                                accentColor = null,
                                availableGroups = availableGroups,
                                onClick = {
                                    if (selectedIds.isNotEmpty()) viewModel.toggleSelection(idea.id)
                                    else onNavigateToPreview(idea.id)
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleSelection(idea.id)
                                },
                                onDelete = { viewModel.softDeleteIdea(idea) },
                                onRename = { newTitle -> viewModel.renameIdea(idea.id, newTitle) },
                                onDuplicate = { viewModel.duplicateIdea(idea) },
                                onEditFiles = { editFilesTargetId = idea.id },
                                onRemoveFromGroup = null,
                                onMoveToGroup = {
                                    moveToGroupTargetId = idea.id
                                    showMoveToGroupSheet = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────── Components ────────────────────────────

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    subtitle: String? = null
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupHeader(
    name: String,
    count: Int,
    isExpanded: Boolean,
    accentColor: Color,
    onToggleExpand: () -> Unit,
    onUngroup: () -> Unit,
    onRename: (String) -> Unit,
    onRecolor: (Color) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(newName); showRenameDialog = false }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Color wheel picker dialog
    if (showColorPicker) {
        ColorWheelPickerDialog(
            currentColor = accentColor,
            onColorSelected = { onRecolor(it) },
            onDismiss = { showColorPicker = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                // Colored left border
                drawRect(
                    color = accentColor,
                    topLeft = Offset.Zero,
                    size = Size(4.dp.toPx(), size.height)
                )
            }
            .background(accentColor.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null,
            tint = accentColor, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Expand/collapse
        IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
            Icon(
                if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp)
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Group options",
                    modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { showMenu = false; showRenameDialog = true },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Change Color") },
                    onClick = { showMenu = false; showColorPicker = true },
                    leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Ungroup All") },
                    onClick = { showMenu = false; onUngroup() },
                    leadingIcon = { Icon(Icons.Filled.FolderOff, contentDescription = null) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdeaCard(
    idea: MelodyIdea,
    isSelected: Boolean,
    isSelecting: Boolean,
    accentColor: Color?,
    availableGroups: Map<String, String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onEditFiles: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    onMoveToGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showItemMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Rename dialog
    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(idea.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(renameText)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (accentColor != null) {
                    Modifier.drawBehind {
                        drawRect(
                            color = accentColor,
                            topLeft = Offset.Zero,
                            size = Size(4.dp.toPx(), size.height)
                        )
                    }
                } else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                accentColor != null -> accentColor.copy(alpha = 0.06f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        shape = RoundedCornerShape(
            topStart = if (accentColor != null) 0.dp else 12.dp,
            bottomStart = if (accentColor != null) 0.dp else 12.dp,
            topEnd = 12.dp,
            bottomEnd = 12.dp
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelecting) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = accentColor ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = idea.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(idea.keySignature ?: "")
                        if (idea.timeSignature != null) {
                            if (isNotEmpty()) append(" \u2022 ")
                            append(idea.timeSignature)
                        }
                        append(" \u2022 ${idea.tempoBpm} BPM")
                        append(" \u2022 ${idea.instrument}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDate(idea.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isSelecting) {
                Box {
                    IconButton(onClick = { showItemMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = showItemMenu,
                        onDismissRequest = { showItemMenu = false }
                    ) {
                        // Group actions
                        DropdownMenuItem(
                            text = { Text("Move to Group...") },
                            onClick = { showItemMenu = false; onMoveToGroup() },
                            leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) }
                        )
                        if (onRemoveFromGroup != null) {
                            DropdownMenuItem(
                                text = { Text("Remove from Group") },
                                onClick = { showItemMenu = false; onRemoveFromGroup() },
                                leadingIcon = { Icon(Icons.Filled.FolderOff, null) }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        // Item actions
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { showItemMenu = false; showRenameDialog = true },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { showItemMenu = false; onDuplicate() },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Files\u2026") },
                            onClick = { showItemMenu = false; onEditFiles() },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, null) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        // Destructive
                        DropdownMenuItem(
                            text = { Text("Move to Trash") },
                            onClick = { showItemMenu = false; showDeleteDialog = true },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Move to Trash?") },
            text = { Text("\u201c${idea.title}\u201d will be moved to trash and automatically deleted after 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrashCard(
    idea: MelodyIdea,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    var showPermanentDeleteDialog by remember { mutableStateOf(false) }
    val deletedDaysAgo = idea.deletedAt?.let {
        ((System.currentTimeMillis() - it) / (24 * 60 * 60 * 1000)).toInt()
    } ?: 0
    val daysRemaining = (30 - deletedDaysAgo).coerceAtLeast(0)

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(idea.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    text = "$daysRemaining days remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore")
            }
            IconButton(onClick = { showPermanentDeleteDialog = true }) {
                Icon(Icons.Filled.DeleteForever, contentDescription = "Delete permanently",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showPermanentDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showPermanentDeleteDialog = false },
            title = { Text("Delete permanently?") },
            text = { Text("This will permanently delete \u201c${idea.title}\u201d and all its files. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePermanently()
                    showPermanentDeleteDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MoveToGroupSheet(
    groups: Map<String, String>,
    colorOverrides: Map<String, Int>,
    onDismiss: () -> Unit,
    onSelectGroup: (groupId: String, groupName: String) -> Unit,
    onCreateNew: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Move to Group",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        groups.forEach { (id, name) ->
            val gColor = groupColor(id, colorOverrides)
            ListItem(
                headlineContent = { Text(name) },
                leadingContent = { Icon(Icons.Filled.Folder, null, tint = gColor) },
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onSelectGroup(id, name) })
            )
        }
        ListItem(
            headlineContent = { Text("New Group...") },
            leadingContent = {
                Icon(Icons.Filled.CreateNewFolder, null,
                    tint = MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onCreateNew)
        )
        Spacer(Modifier.height(32.dp))
    }
}

private fun formatDate(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

// ──────────────────────────── Color Wheel Picker ────────────────────────────

@Composable
private fun ColorWheelPickerDialog(
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    // Extract initial HSV from the current colour
    val initHsv = remember(currentColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(currentColor.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var bri by remember { mutableFloatStateOf(initHsv[2]) }

    val previewColor = Color.hsv(hue, sat.coerceIn(0f, 1f), bri.coerceIn(0f, 1f))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose color") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Preview swatch
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(previewColor)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )

                Spacer(Modifier.height(16.dp))

                // ── Colour wheel (hue + saturation) ──
                val wheelSizeDp = 200.dp
                Canvas(
                    modifier = Modifier
                        .size(wheelSizeDp)
                        .pointerInput(Unit) {
                            val r = minOf(size.width, size.height) / 2f
                            val cx = size.width / 2f
                            val cy = size.height / 2f

                            fun update(pos: Offset) {
                                val dx = pos.x - cx
                                val dy = pos.y - cy
                                val dist = sqrt(dx * dx + dy * dy).coerceAtMost(r)
                                val angle =
                                    Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                hue = ((angle % 360f) + 360f) % 360f
                                sat = (dist / r).coerceIn(0f, 1f)
                            }

                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitPointerEvent()
                                    val change = down.changes.firstOrNull() ?: continue
                                    if (change.pressed) {
                                        update(change.position)
                                        change.consume()
                                        // Drag loop
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val c = event.changes.firstOrNull() ?: break
                                            if (!c.pressed) break
                                            update(c.position)
                                            c.consume()
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    val r = size.minDimension / 2f
                    val c = center

                    // Hue sweep gradient
                    val hueStops = (0..6).map { i ->
                        Color.hsv(i * 60f, 1f, 1f)
                    } + Color.hsv(0f, 1f, 1f) // close the loop
                    drawCircle(
                        brush = Brush.sweepGradient(colors = hueStops, center = c),
                        radius = r,
                        center = c
                    )
                    // Saturation overlay: white at centre → transparent at edge
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White, Color.Transparent),
                            center = c,
                            radius = r
                        ),
                        radius = r,
                        center = c
                    )

                    // Selector thumb
                    val angleRad = Math.toRadians(hue.toDouble())
                    val dist = sat * r
                    val sx = c.x + dist * cos(angleRad).toFloat()
                    val sy = c.y + dist * sin(angleRad).toFloat()
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(sx, sy))
                    drawCircle(previewColor, radius = 8.dp.toPx(), center = Offset(sx, sy))
                    drawCircle(
                        Color.Black.copy(alpha = 0.3f),
                        radius = 10.dp.toPx(),
                        center = Offset(sx, sy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Brightness slider ──
                Text(
                    "Brightness",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = bri,
                    onValueChange = { bri = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                // ── Quick-pick presets (rainbow + neutrals) ──
                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(4.dp))
                // 2-row grid of preset swatches, horizontally scrollable
                val presetColors = listOf(
                    Color(0xFFE53935), // Red
                    Color(0xFFFF7043), // Deep Orange
                    Color(0xFFFFA726), // Orange
                    Color(0xFFFFEE58), // Yellow
                    Color(0xFF66BB6A), // Green
                    Color(0xFF26A69A), // Teal
                    Color(0xFF29B6F6), // Light Blue
                    Color(0xFF5C6BC0), // Indigo
                    Color(0xFF8E24AA), // Purple
                    Color(0xFFEC407A), // Pink
                    Color(0xFFFFFFFF), // White
                    Color(0xFFF5F0E1), // Beige
                    Color(0xFFBDBDBD), // Light Gray
                    Color(0xFF616161), // Dark Gray
                    Color(0xFF212121), // Black
                )
                val rowSize = (presetColors.size + 1) / 2 // ceil divide
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (row in 0 until 2) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val start = row * rowSize
                                val end = minOf(start + rowSize, presetColors.size)
                                for (i in start until end) {
                                    val c = presetColors[i]
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .clickable {
                                                val ph = FloatArray(3)
                                                android.graphics.Color.colorToHSV(c.toArgb(), ph)
                                                hue = ph[0]; sat = ph[1]; bri = ph[2]
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onColorSelected(previewColor)
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
