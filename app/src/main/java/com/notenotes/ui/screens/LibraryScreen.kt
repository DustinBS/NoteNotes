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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notenotes.data.AppDatabase
import com.notenotes.export.NntFile
import com.notenotes.model.MelodyIdea
import com.notenotes.model.MusicalNote
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

// ──────────────────────── Duration Mode ────────────────────────

/** How to resolve duration mismatches between audio and transcription. */
enum class DurationMode { EXPAND, SHRINK }

/** Format milliseconds as m:ss.SSS (e.g., 1:05.320, 0:30.125). */
private fun formatDurationMmSs(ms: Long): String {
    val abMs = ms.coerceAtLeast(0)
    val totalSec = abMs / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val millis = abMs % 1000
    return "$min:%02d.%03d".format(sec, millis)
}

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
    TITLE_ZA("Z → A"),
    RECENT("Recent")
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

    /** Add a single idea to the selection without toggling. Used by drag-to-select. */
    fun addToSelection(id: Long) {
        _selectedIds.value = _selectedIds.value + id
    }

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

    /** Update last-opened timestamp for recently-opened sort. */
    fun updateLastOpenedAt(id: Long) {
        viewModelScope.launch { dao.updateLastOpenedAt(id, System.currentTimeMillis()) }
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

    /**
     * Get audio duration in milliseconds from a content URI.
     * Returns null if duration cannot be determined.
     */
    fun getAudioDurationMs(context: Context, uri: Uri): Long? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            retriever.release()
            durationStr?.toLongOrNull()
        } catch (e: Exception) {
            android.util.Log.w("LibraryVM", "Could not get audio duration", e)
            null
        }
    }

    /**
     * Get transcription duration in milliseconds from a content URI.
     * Supports both .nnt and .musicxml files.
     * Returns null if duration cannot be determined.
     */
    fun getTranscriptionDurationMs(context: Context, uri: Uri): Long? {
        val name = getDisplayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when (ext) {
                NntTranscription.FILE_EXTENSION -> {
                    val nnt = context.contentResolver.openInputStream(uri)?.use { input ->
                        NntFile.read(input)
                    } ?: return null
                    computeNotesDurationMs(nnt.notes, nnt.tempoBpm)
                }
                "musicxml", "xml" -> {
                    val xml = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.reader().readText()
                    } ?: return null
                    val parser = com.notenotes.export.MusicXmlParser()
                    val result = parser.parse(xml)
                    computeNotesDurationMs(result.notes, result.tempoBpm ?: 120)
                }
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("LibraryVM", "Could not get transcription duration", e)
            null
        }
    }

    /**
     * Get the audio duration from an existing idea's file path.
     */
    fun getIdeaAudioDurationMs(ideaId: Long): Long? {
        val idea = kotlinx.coroutines.runBlocking { dao.getIdeaById(ideaId) } ?: return null
        val path = idea.audioFilePath ?: return null
        val file = java.io.File(path)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            retriever.release()
            durationStr?.toLongOrNull()
        } catch (e: Exception) { null }
    }

    /**
     * Get the transcription duration from an existing idea's stored notes.
     */
    fun getIdeaTranscriptionDurationMs(ideaId: Long): Long? {
        val idea = kotlinx.coroutines.runBlocking { dao.getIdeaById(ideaId) } ?: return null
        val notesJson = idea.notes ?: return null
        return try {
            val gson = com.google.gson.Gson()
            val notesType = object : com.google.gson.reflect.TypeToken<List<MusicalNote>>() {}.type
            val notes: List<MusicalNote> = gson.fromJson(notesJson, notesType)
            if (notes.isEmpty()) null else computeNotesDurationMs(notes, idea.tempoBpm)
        } catch (e: Exception) { null }
    }

    /**
     * Compute total duration of a note list in milliseconds.
     * Uses divisions=4: a quarter note = 4 ticks, so ms per tick = 60000 / tempo / 4.
     *
     * For manual notes with timePositionMs, uses the actual time span
     * (from time 0 to end of last note) so leading silence is included.
     * This matches perceived duration when played back against the audio.
     */
    private fun computeNotesDurationMs(notes: List<com.notenotes.model.MusicalNote>, tempoBpm: Int): Long {
        if (notes.isEmpty()) return 0L
        val msPerTick = 60000.0 / tempoBpm / 4.0  // divisions = 4

        // Check if notes have timePositionMs (manual notes)
        val lastNote = notes.last()
        if (lastNote.timePositionMs != null && lastNote.timePositionMs > 0f) {
            // Use position-based duration: last note start + its duration
            val lastNoteEndMs = lastNote.timePositionMs + lastNote.durationTicks * msPerTick
            return lastNoteEndMs.toLong()
        }

        // Fallback: sum all ticks (auto-transcribed notes without positions)
        val totalTicks = notes.sumOf { it.durationTicks }
        return (totalTicks * msPerTick).toLong()
    }

    // ── Duration adjustment helpers ──

    /**
     * Trim a note list so total duration does not exceed [targetMs].
     * Notes that start beyond the target are dropped entirely.
     */
    private fun trimNotesToDuration(
        notes: List<MusicalNote>, tempoBpm: Int, targetMs: Long
    ): List<MusicalNote> {
        if (notes.isEmpty()) return notes
        val msPerTick = 60000.0 / tempoBpm / 4.0  // divisions = 4
        var accumulatedMs = 0.0
        val result = mutableListOf<MusicalNote>()
        for (note in notes) {
            val durationMs = note.durationTicks * msPerTick
            if (accumulatedMs + durationMs <= targetMs + 1) { // +1 for rounding tolerance
                result.add(note)
                accumulatedMs += durationMs
            } else {
                break
            }
        }
        return result
    }

    /**
     * Adjust an audio file to exactly [targetMs] duration.
     * Decodes to PCM, trims or pads with silence, writes as WAV.
     * Returns the absolute path of the resulting file (always .wav).
     */
    private fun adjustAudioDuration(audioFile: java.io.File, targetMs: Long): String {
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(audioFile.absolutePath)

        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) { audioTrack = i; break }
        }
        if (audioTrack < 0) { extractor.release(); return audioFile.absolutePath }

        extractor.selectTrack(audioTrack)
        val format = extractor.getTrackFormat(audioTrack)
        val mime = format.getString(android.media.MediaFormat.KEY_MIME)
            ?: run { extractor.release(); return audioFile.absolutePath }
        val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)

        // Decode to PCM
        val codec = android.media.MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmOutput = java.io.ByteArrayOutputStream()
        val timeoutUs = 10_000L
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(timeoutUs)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val info = android.media.MediaCodec.BufferInfo()
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    val chunk = ByteArray(info.size)
                    outBuf.get(chunk)
                    pcmOutput.write(chunk)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop(); codec.release(); extractor.release()

        // Calculate target byte count (16-bit PCM)
        val bytesPerSample = 2 * channels
        val targetBytes = ((sampleRate.toLong() * targetMs / 1000) * bytesPerSample).toInt()
        val pcmBytes = pcmOutput.toByteArray()

        val adjustedPcm = when {
            pcmBytes.size > targetBytes -> pcmBytes.copyOf(targetBytes)
            pcmBytes.size < targetBytes -> pcmBytes + ByteArray(targetBytes - pcmBytes.size)
            else -> pcmBytes
        }

        // Write WAV, possibly changing extension
        val wavFile = java.io.File(
            audioFile.parent,
            audioFile.nameWithoutExtension + ".wav"
        )
        writeWavFile(wavFile, adjustedPcm, sampleRate, channels)

        if (wavFile.absolutePath != audioFile.absolutePath) {
            audioFile.delete()
        }
        return wavFile.absolutePath
    }

    /** Write raw 16-bit PCM data as a standard WAV file. */
    private fun writeWavFile(
        file: java.io.File, pcmData: ByteArray, sampleRate: Int, channels: Int
    ) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size

        file.outputStream().use { out ->
            val header = java.nio.ByteBuffer.allocate(44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            out.write(header.array())
            out.write(pcmData)
        }
    }

    /**
     * Apply duration mode adjustment. Both audio and transcription must be present.
     * Returns a Triple of (adjustedAudioPath, adjustedNotesJson, adjustedTempo).
     */
    private fun applyDurationMode(
        audioFile: java.io.File,
        notes: List<MusicalNote>,
        tempoBpm: Int,
        durationMode: DurationMode
    ): Triple<String, List<MusicalNote>, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(audioFile.absolutePath)
        val audioDurMs = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        retriever.release()

        val transDurMs = computeNotesDurationMs(notes, tempoBpm)

        if (audioDurMs == transDurMs || audioDurMs == 0L || transDurMs == 0L) {
            return Triple(audioFile.absolutePath, notes, tempoBpm)
        }

        return when (durationMode) {
            DurationMode.SHRINK -> {
                val targetMs = minOf(audioDurMs, transDurMs)
                val newPath = if (audioDurMs > transDurMs) {
                    adjustAudioDuration(audioFile, targetMs)
                } else audioFile.absolutePath
                val newNotes = if (transDurMs > audioDurMs) {
                    trimNotesToDuration(notes, tempoBpm, targetMs)
                } else notes
                Triple(newPath, newNotes, tempoBpm)
            }
            DurationMode.EXPAND -> {
                val targetMs = maxOf(audioDurMs, transDurMs)
                val newPath = if (audioDurMs < transDurMs) {
                    adjustAudioDuration(audioFile, targetMs)
                } else audioFile.absolutePath
                // In expand mode, transcription shorter than audio → leave as-is
                Triple(newPath, notes, tempoBpm)
            }
        }
    }

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
    fun replaceIdeaFiles(context: Context, ideaId: Long, audioUri: Uri?, xmlUri: Uri?, durationMode: DurationMode? = null) {
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

                        if (durationMode != null) {
                            // Duration adjustment needs notes now — parse XML eagerly
                            val xml = dest.readText()
                            val parser = com.notenotes.export.MusicXmlParser()
                            val result = parser.parse(xml, newTempo)
                            val gson = com.google.gson.Gson()
                            newNotes = gson.toJson(result.notes)
                            result.instrument?.let { newInstrument = it }
                            result.tempoBpm?.let { newTempo = it }
                            result.keySignature?.let { newKey = it }
                            result.timeSignature?.let { newTime = it }
                        } else {
                            newNotes = null  // force re-parse from XML on next load
                        }
                    }
                }

                // ── Apply duration adjustment if both audio and transcription are present ──
                if (durationMode != null && newAudioPath != null && newNotes != null) {
                    val gson = com.google.gson.Gson()
                    val noteList = gson.fromJson(
                        newNotes,
                        Array<MusicalNote>::class.java
                    ).toList()
                    val audioFile = java.io.File(newAudioPath)
                    val (adjPath, adjNotes, _) = applyDurationMode(
                        audioFile, noteList, newTempo, durationMode
                    )
                    newAudioPath = adjPath
                    newNotes = gson.toJson(adjNotes)
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
    fun buildIdea(context: Context, audioUri: Uri, xmlUri: Uri?, durationMode: DurationMode? = null, onDone: (Long) -> Unit) {
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

                        if (durationMode != null) {
                            // Duration adjustment needs notes now — parse XML eagerly
                            val xml = xmlDest.readText()
                            val parser = com.notenotes.export.MusicXmlParser()
                            val result = parser.parse(xml, tempoBpm)
                            val gson = com.google.gson.Gson()
                            notes = gson.toJson(result.notes)
                            result.instrument?.let { instrument = it }
                            result.tempoBpm?.let { tempoBpm = it }
                            result.keySignature?.let { keySignature = it }
                            result.timeSignature?.let { timeSignature = it }
                        }
                    }
                }

                // ── Apply duration adjustment if both audio and transcription are present ──
                var finalAudioPath = audioDest.absolutePath
                if (durationMode != null && notes != null) {
                    val gson = com.google.gson.Gson()
                    val noteList = gson.fromJson(
                        notes,
                        Array<MusicalNote>::class.java
                    ).toList()
                    val (adjPath, adjNotes, _) = applyDurationMode(
                        audioDest, noteList, tempoBpm, durationMode
                    )
                    finalAudioPath = adjPath
                    notes = gson.toJson(adjNotes)
                }

                val idea = MelodyIdea(
                    title = title,
                    createdAt = ts,
                    audioFilePath = finalAudioPath,
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

    /**
     * Import a ZIP snapshot — handles both single-idea ZIPs (flat: metadata.json, audio.wav …)
     * and multi-idea ZIPs (sub-folders: IdeaTitle/metadata.json, IdeaTitle/audio.wav …).
     */
    private suspend fun importSnapshot(context: Context, zipUri: Uri, onDone: (Long) -> Unit) {
        val audioDir = java.io.File(context.filesDir, "audio")
        audioDir.mkdirs()

        val inputStream = context.contentResolver.openInputStream(zipUri)
        if (inputStream == null) {
            android.util.Log.e("LibraryVM", "Failed to open input stream for ZIP: $zipUri")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Cannot read file", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        // ── First pass: group entries by subfolder ("" = root) ──
        data class ZipBlob(val name: String, val bytes: ByteArray)
        val buckets = mutableMapOf<String, MutableList<ZipBlob>>()

        inputStream.use { input ->
            java.util.zip.ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fullName = entry.name
                        val slashIdx = fullName.indexOf('/')
                        val (bucket, leaf) = if (slashIdx > 0) {
                            fullName.substring(0, slashIdx) to fullName.substring(slashIdx + 1)
                        } else {
                            "" to fullName
                        }
                        buckets.getOrPut(bucket) { mutableListOf() }.add(ZipBlob(leaf, zis.readBytes()))
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        if (buckets.isEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "ZIP file appears empty", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Determine if this is a single flat snapshot or a multi-idea package.
        // Single snapshots have entries in the root bucket (""): metadata.json, audio.wav, …
        val isSingle = buckets.containsKey("") && buckets[""]!!.any { it.name == "metadata.json" }
        val groups: Collection<List<ZipBlob>> = if (isSingle) listOf(buckets[""]!!) else buckets.values

        val gson = com.google.gson.Gson()
        var importedCount = 0
        var lastId: Long = -1

        for (blobs in groups) {
            val ts = System.currentTimeMillis() + importedCount  // unique timestamp per idea
            var audioPath: String? = null
            var xmlPath: String? = null
            var midiPath: String? = null
            var metaJson: String? = null

            for (blob in blobs) {
                when {
                    blob.name == "metadata.json" -> metaJson = blob.bytes.toString(Charsets.UTF_8)
                    blob.name.startsWith("audio.") -> {
                        val ext = blob.name.substringAfterLast('.', "wav")
                        val dest = java.io.File(audioDir, "import_${ts}.$ext")
                        dest.writeBytes(blob.bytes)
                        audioPath = dest.absolutePath
                    }
                    blob.name.endsWith(".musicxml") -> {
                        val dest = java.io.File(audioDir, "import_${ts}.musicxml")
                        dest.writeBytes(blob.bytes)
                        xmlPath = dest.absolutePath
                    }
                    blob.name.endsWith(".mid") -> {
                        val dest = java.io.File(audioDir, "import_${ts}.mid")
                        dest.writeBytes(blob.bytes)
                        midiPath = dest.absolutePath
                    }
                    // .nnt entries — notes are already in metadata.json
                }
            }

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
            lastId = dao.insert(idea)
            importedCount++
        }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (importedCount > 1) {
                android.widget.Toast.makeText(context, "Imported $importedCount ideas", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                onDone(lastId)
            }
        }
    }

    /** Create a ZIP containing snapshot ZIPs for every selected idea, then share it. */
    fun zipSelectedIdeas(context: Context) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val allIdeas = dao.getAllIdeasSnapshot()
                val selected = allIdeas.filter { it.id in ids }
                if (selected.isEmpty()) return@launch

                val dir = java.io.File(context.filesDir, "exports")
                dir.mkdirs()
                val ts = System.currentTimeMillis()
                val zipFile = java.io.File(dir, "NoteNotes_${selected.size}_ideas_$ts.zip")

                val gson = com.google.gson.Gson()
                java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                    selected.forEach { idea ->
                        val prefix = "${idea.title.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")}/"

                        // metadata.json
                        val meta = mutableMapOf<String, Any?>()
                        meta["title"] = idea.title
                        meta["instrument"] = idea.instrument
                        meta["tempoBpm"] = idea.tempoBpm
                        meta["keySignature"] = idea.keySignature
                        meta["timeSignature"] = idea.timeSignature
                        meta["notes"] = idea.notes
                        meta["groupId"] = idea.groupId
                        meta["groupName"] = idea.groupName
                        meta["createdAt"] = idea.createdAt

                        zos.putNextEntry(java.util.zip.ZipEntry("${prefix}metadata.json"))
                        zos.write(gson.toJson(meta).toByteArray())
                        zos.closeEntry()

                        // Audio
                        idea.audioFilePath?.let { path ->
                            val audio = java.io.File(path)
                            if (audio.exists()) {
                                val ext = audio.extension.ifEmpty { "wav" }
                                zos.putNextEntry(java.util.zip.ZipEntry("${prefix}audio.$ext"))
                                audio.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }

                        // MIDI
                        idea.midiFilePath?.let { path ->
                            val f = java.io.File(path)
                            if (f.exists()) {
                                zos.putNextEntry(java.util.zip.ZipEntry("${prefix}melody.mid"))
                                f.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }

                        // MusicXML
                        idea.musicXmlFilePath?.let { path ->
                            val f = java.io.File(path)
                            if (f.exists()) {
                                zos.putNextEntry(java.util.zip.ZipEntry("${prefix}melody.musicxml"))
                                f.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }

                        // NNT transcription from notes JSON
                        idea.notes?.let { notesJson ->
                            val nnt = NntFile.fromNotesJson(
                                notesJson,
                                instrument = idea.instrument,
                                tempoBpm = idea.tempoBpm,
                                keySignature = idea.keySignature,
                                timeSignature = idea.timeSignature
                            )
                            if (nnt != null) {
                                zos.putNextEntry(java.util.zip.ZipEntry("${prefix}melody.${NntTranscription.FILE_EXTENSION}"))
                                zos.write(NntFile.toJson(nnt).toByteArray())
                                zos.closeEntry()
                            }
                        }
                    }
                }

                // Share the ZIP
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", zipFile
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    context.startActivity(
                        android.content.Intent.createChooser(intent, "Share ${selected.size} Ideas")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                _selectedIds.value = emptySet()
            } catch (e: Exception) {
                android.util.Log.e("LibraryVM", "zipSelectedIdeas: Error", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Zip failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
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

    // Scroll to top only when sort mode actually changes (not on re-composition after nav back)
    val listState = rememberLazyListState()
    var previousSortMode by remember { mutableStateOf(sortMode) }
    LaunchedEffect(sortMode) {
        if (sortMode != previousSortMode) {
            previousSortMode = sortMode
            listState.animateScrollToItem(0)
        }
    }

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
            SortMode.RECENT -> base.sortedByDescending { it.lastOpenedAt ?: 0L }
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
        var editDurationMode by remember { mutableStateOf(DurationMode.EXPAND) }
        val editAudioDurationMs = remember(editAudioUri) {
            editAudioUri?.let { viewModel.getAudioDurationMs(context, it) }
        }
        val editTransDurationMs = remember(editXmlUri) {
            editXmlUri?.let { viewModel.getTranscriptionDurationMs(context, it) }
        }
        // Existing idea durations (fallbacks when only one side is replaced)
        val existingAudioMs = remember(editFilesTargetId) {
            editFilesTargetId?.let { viewModel.getIdeaAudioDurationMs(it) }
        }
        val existingTransMs = remember(editFilesTargetId) {
            editFilesTargetId?.let { viewModel.getIdeaTranscriptionDurationMs(it) }
        }

        // Effective durations: newly picked file overrides existing
        val effectiveAudioMs = editAudioDurationMs ?: existingAudioMs
        val effectiveTransMs = editTransDurationMs ?: existingTransMs
        val hasFileSelected = editAudioUri != null || editXmlUri != null
        val editDurationsDiffer = hasFileSelected
                && effectiveAudioMs != null && effectiveTransMs != null
                && effectiveAudioMs != effectiveTransMs

        // Validate transcription file extension
        val editTransValid = remember(editXmlName) {
            if (editXmlName == null) true
            else {
                val ext = editXmlName!!.substringAfterLast('.', "").lowercase()
                ext in listOf("nnt", "musicxml", "xml")
            }
        }

        AlertDialog(
            onDismissRequest = {
                editFilesTargetId = null; editAudioUri = null; editAudioName = null
                editXmlUri = null; editXmlName = null
            },
            title = { Text("Edit Files") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Optionally replace the audio or transcription files for this idea.",
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
                        Text(editAudioName ?: "Select Audio (.wav, .mp3, .m4a)")
                    }
                    // Transcription file picker
                    OutlinedButton(
                        onClick = { editXmlPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(editXmlName ?: "Select Transcription (.nnt, .musicxml)")
                    }
                    // Show error for unsupported transcription file
                    if (!editTransValid) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "\u274C $editXmlName — unsupported format. Use .nnt or .musicxml",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Duration mismatch section
                    if (editDurationsDiffer) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning, null,
                                tint = Color(0xFFF9A825),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Duration Mismatch",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFF9A825)
                            )
                        }
                        Text(
                            "Audio: ${formatDurationMmSs(effectiveAudioMs!!)}  •  Transcription: ${formatDurationMmSs(effectiveTransMs!!)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF9A825)
                        )

                        // Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = editDurationMode == DurationMode.EXPAND,
                                onClick = { editDurationMode = DurationMode.EXPAND },
                                label = { Text("Expand to longest") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = editDurationMode == DurationMode.SHRINK,
                                onClick = { editDurationMode = DurationMode.SHRINK },
                                label = { Text("Shrink to shortest") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Look-ahead summary
                        val shorterMs = minOf(effectiveAudioMs, effectiveTransMs)
                        val audioIsLonger = effectiveAudioMs > effectiveTransMs
                        val audioLabel = editAudioName?.substringAfterLast('.', "audio") ?: "audio"
                        val transLabel = editXmlName?.substringAfterLast('.', "transcription")
                            ?: if (existingTransMs != null) "transcription" else "transcription"

                        val summary = if (editDurationMode == DurationMode.SHRINK) {
                            if (audioIsLonger) {
                                "A copy of the audio will be trimmed from ${formatDurationMmSs(effectiveAudioMs)} to ${formatDurationMmSs(shorterMs)} to match the transcription. Original files are not modified."
                            } else {
                                "A copy of the transcription will be trimmed from ${formatDurationMmSs(effectiveTransMs)} to ${formatDurationMmSs(shorterMs)} to match the audio. Original files are not modified."
                            }
                        } else {
                            if (audioIsLonger) {
                                "The transcription ends at ${formatDurationMmSs(effectiveTransMs)}. The remaining audio until ${formatDurationMmSs(effectiveAudioMs)} will have no transcription. Original files are not modified."
                            } else {
                                "The audio ends at ${formatDurationMmSs(effectiveAudioMs)}. A copy with silent padding will be created up to ${formatDurationMmSs(effectiveTransMs)} so the full transcription is playable. Original files are not modified."
                            }
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mode = if (editDurationsDiffer) editDurationMode else null
                        editFilesTargetId?.let { id ->
                            viewModel.replaceIdeaFiles(context, id, editAudioUri, editXmlUri, mode)
                        }
                        editFilesTargetId = null; editAudioUri = null; editAudioName = null
                        editXmlUri = null; editXmlName = null
                    },
                    enabled = (editAudioUri != null || editXmlUri != null) && editTransValid
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
        var buildDurationMode by remember { mutableStateOf(DurationMode.EXPAND) }
        val buildAudioDurationMs = remember(buildAudioUri) {
            buildAudioUri?.let { viewModel.getAudioDurationMs(context, it) }
        }
        val buildTransDurationMs = remember(buildXmlUri) {
            buildXmlUri?.let { viewModel.getTranscriptionDurationMs(context, it) }
        }
        val buildBothSelected = buildAudioUri != null && buildXmlUri != null
        val buildDurationsDiffer = buildBothSelected && buildAudioDurationMs != null && buildTransDurationMs != null
                && buildAudioDurationMs != buildTransDurationMs

        // Validate transcription file extension
        val buildTransValid = remember(buildXmlName) {
            if (buildXmlName == null) true
            else {
                val ext = buildXmlName!!.substringAfterLast('.', "").lowercase()
                ext in listOf("nnt", "musicxml", "xml")
            }
        }

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
                        Text(buildAudioName ?: "Select Audio (.wav, .mp3, .m4a) *")
                    }
                    if (buildAudioName != null) {
                        Text(
                            "\u2705 ${buildAudioName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Transcription file picker (optional)
                    OutlinedButton(
                        onClick = { buildXmlPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(buildXmlName ?: "Select Transcription (.nnt, .musicxml)")
                    }
                    if (buildXmlName != null && buildTransValid) {
                        Text(
                            "\u2705 ${buildXmlName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Show error for unsupported transcription file
                    if (!buildTransValid) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "\u274C $buildXmlName — unsupported format. Use .nnt or .musicxml",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Duration mismatch section
                    if (buildDurationsDiffer) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning, null,
                                tint = Color(0xFFF9A825),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Duration Mismatch",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFF9A825)
                            )
                        }
                        Text(
                            "Audio: ${formatDurationMmSs(buildAudioDurationMs!!)}  •  Transcription: ${formatDurationMmSs(buildTransDurationMs!!)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF9A825)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = buildDurationMode == DurationMode.EXPAND,
                                onClick = { buildDurationMode = DurationMode.EXPAND },
                                label = { Text("Expand to longest") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = buildDurationMode == DurationMode.SHRINK,
                                onClick = { buildDurationMode = DurationMode.SHRINK },
                                label = { Text("Shrink to shortest") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        val shorterMs = minOf(buildAudioDurationMs, buildTransDurationMs)
                        val audioIsLonger = buildAudioDurationMs > buildTransDurationMs

                        val summary = if (buildDurationMode == DurationMode.SHRINK) {
                            if (audioIsLonger) {
                                "A copy of the audio will be trimmed from ${formatDurationMmSs(buildAudioDurationMs)} to ${formatDurationMmSs(shorterMs)} to match the transcription. Original files are not modified."
                            } else {
                                "A copy of the transcription will be trimmed from ${formatDurationMmSs(buildTransDurationMs)} to ${formatDurationMmSs(shorterMs)} to match the audio. Original files are not modified."
                            }
                        } else {
                            if (audioIsLonger) {
                                "The transcription ends at ${formatDurationMmSs(buildTransDurationMs)}. The remaining audio until ${formatDurationMmSs(buildAudioDurationMs)} will have no transcription. Original files are not modified."
                            } else {
                                "The audio ends at ${formatDurationMmSs(buildAudioDurationMs)}. A copy with silent padding will be created up to ${formatDurationMmSs(buildTransDurationMs)} so the full transcription is playable. Original files are not modified."
                            }
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mode = if (buildDurationsDiffer) buildDurationMode else null
                        buildAudioUri?.let { audioUri ->
                            viewModel.buildIdea(context, audioUri, buildXmlUri, mode) { newId ->
                                onNavigateToPreview(newId)
                            }
                        }
                        showBuildDialog = false; buildAudioUri = null; buildAudioName = null
                        buildXmlUri = null; buildXmlName = null
                    },
                    enabled = buildAudioUri != null && buildTransValid
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
                    title = { Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleSmall) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll(filteredIdeas) }, modifier = Modifier.size(48.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "Select All", modifier = Modifier.size(20.dp))
                                Text("All", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                            }
                        }
                        if (availableGroups.isNotEmpty()) {
                            IconButton(onClick = {
                                moveToGroupTargetId = null
                                showMoveToGroupSheet = true
                            }, modifier = Modifier.size(48.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.DriveFileMove, contentDescription = "Move to Group", modifier = Modifier.size(20.dp))
                                    Text("Move", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.softDeleteSelected() }, modifier = Modifier.size(48.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Delete, contentDescription = "Trash", modifier = Modifier.size(20.dp))
                                Text("Trash", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                            }
                        }
                        IconButton(onClick = {
                            viewModel.zipSelectedIdeas(viewModel.getApplication<Application>())
                        }, modifier = Modifier.size(48.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.FolderZip, contentDescription = "Zip", modifier = Modifier.size(20.dp))
                                Text("Zip", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                            }
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
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.FolderZip, contentDescription = "Unzip", modifier = Modifier.size(20.dp))
                                    Text("Unzip", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
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

                    // Sort chips — Date and Title toggle direction on click
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Date toggle: Newest ↔ Oldest
                        val isDateSort = sortMode == SortMode.DATE_DESC || sortMode == SortMode.DATE_ASC
                        val dateLabel = if (sortMode == SortMode.DATE_ASC) "Oldest" else "Newest"
                        FilterChip(
                            selected = isDateSort,
                            onClick = {
                                viewModel.setSortMode(
                                    if (sortMode == SortMode.DATE_DESC) SortMode.DATE_ASC
                                    else SortMode.DATE_DESC
                                )
                            },
                            label = { Text(dateLabel) },
                            leadingIcon = if (isDateSort) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )

                        // Title toggle: A→Z ↔ Z→A
                        val isTitleSort = sortMode == SortMode.TITLE_AZ || sortMode == SortMode.TITLE_ZA
                        val titleLabel = if (sortMode == SortMode.TITLE_ZA) "Z → A" else "A → Z"
                        FilterChip(
                            selected = isTitleSort,
                            onClick = {
                                viewModel.setSortMode(
                                    if (sortMode == SortMode.TITLE_AZ) SortMode.TITLE_ZA
                                    else SortMode.TITLE_AZ
                                )
                            },
                            label = { Text(titleLabel) },
                            leadingIcon = if (isTitleSort) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )

                        // Recent
                        FilterChip(
                            selected = sortMode == SortMode.RECENT,
                            onClick = { viewModel.setSortMode(SortMode.RECENT) },
                            label = { Text("Recent") },
                            leadingIcon = if (sortMode == SortMode.RECENT) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )
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
                    // Drag-to-select: when in selection mode, dragging across items selects them
                    var isDragSelecting by remember { mutableStateOf(false) }
                    val isSelectMode = selectedIds.isNotEmpty()

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isSelectMode) {
                                Modifier.pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                        var totalDrag = 0f
                                        var dragActive = false

                                        while (true) {
                                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break

                                            totalDrag += abs(change.positionChange().y)
                                            if (totalDrag > viewConfiguration.touchSlop && !dragActive) {
                                                dragActive = true
                                                isDragSelecting = true
                                            }

                                            if (dragActive) {
                                                change.consume()
                                                // Find item at current Y and add to selection
                                                val y = change.position.y
                                                val hitItem = listState.layoutInfo.visibleItemsInfo.find { item ->
                                                    y >= item.offset && y < item.offset + item.size
                                                }
                                                val hitKey = hitItem?.key
                                                if (hitKey is Long) {
                                                    viewModel.addToSelection(hitKey)
                                                }
                                            }
                                        }
                                        isDragSelecting = false
                                    }
                                }
                            } else Modifier),
                        userScrollEnabled = !isDragSelecting,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (sortMode == SortMode.RECENT) {
                            // Flat list — no group headers, show "groupName / title"
                            items(filteredIdeas, key = { it.id }) { idea ->
                                IdeaCard(
                                    idea = idea,
                                    isSelected = idea.id in selectedIds,
                                    isSelecting = selectedIds.isNotEmpty(),
                                    accentColor = idea.groupId?.let { groupColor(it, colorOverrides) },
                                    availableGroups = availableGroups,
                                    groupPrefix = idea.groupName,
                                    onClick = {
                                        if (selectedIds.isNotEmpty()) viewModel.toggleSelection(idea.id)
                                        else {
                                            viewModel.updateLastOpenedAt(idea.id)
                                            onNavigateToPreview(idea.id)
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleSelection(idea.id)
                                    },
                                    onDelete = { viewModel.softDeleteIdea(idea) },
                                    onRename = { newTitle -> viewModel.renameIdea(idea.id, newTitle) },
                                    onDuplicate = { viewModel.duplicateIdea(idea) },
                                    onEditFiles = { editFilesTargetId = idea.id },
                                    onRemoveFromGroup = if (idea.groupId != null) {{ viewModel.removeFromGroup(idea.id) }} else null,
                                    onMoveToGroup = {
                                        moveToGroupTargetId = idea.id
                                        showMoveToGroupSheet = true
                                    }
                                )
                            }
                        } else {
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
                                            else {
                                                viewModel.updateLastOpenedAt(idea.id)
                                                onNavigateToPreview(idea.id)
                                            }
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
                                    else {
                                        viewModel.updateLastOpenedAt(idea.id)
                                        onNavigateToPreview(idea.id)
                                    }
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
                        } // end else (non-RECENT)
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
    modifier: Modifier = Modifier,
    groupPrefix: String? = null
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
                if (groupPrefix != null) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                fontWeight = FontWeight.Normal
                            )) {
                                append(groupPrefix)
                                append(" / ")
                            }
                            append(idea.title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = idea.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
