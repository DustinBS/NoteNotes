package com.notenotes.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notenotes.audio.AudioPlayer
import com.notenotes.ui.components.NoteNameView
import com.notenotes.ui.components.SheetMusicWebView
import com.notenotes.ui.components.TransportControls
import com.notenotes.ui.components.WaveformView
import com.notenotes.ui.components.NoteEditorPanel
import com.notenotes.processing.PitchAlgorithm
import com.notenotes.model.KeySignature
import com.notenotes.model.TimeSignature
import com.notenotes.model.InstrumentProfile
import com.notenotes.util.GuitarUtils
import kotlinx.coroutines.launch

private const val PREFS_NAME = "notenotes_display"
private const val KEY_BARS_PER_ROW = "bars_per_row"
private const val KEY_SCALE = "sheet_scale"
private const val KEY_WINDOW_SIZE = "window_size_sec"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    ideaId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (() -> Unit)? = null,
    viewModel: PreviewViewModel = viewModel()
) {
    val context = LocalContext.current
    val idea by viewModel.idea.collectAsState()
    val musicXml by viewModel.musicXml.collectAsState()
    val notesList by viewModel.notesList.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val audioDurationMs by viewModel.audioDurationMs.collectAsState()
    val leadingRestOffset by viewModel.leadingRestBeatCount.collectAsState()

    val isRetranscribing by viewModel.isRetranscribing.collectAsState()
    val selectedNoteIndex by viewModel.selectedNoteIndex.collectAsState()
    val editCursorFraction by viewModel.editCursorFraction.collectAsState()
    val isEditorOpen by viewModel.isEditorOpen.collectAsState()

    // Window state
    val windowStartFraction by viewModel.windowStartFraction.collectAsState()
    val windowSizeSec by viewModel.windowSizeSec.collectAsState()
    val isWindowLocked by viewModel.isWindowLocked.collectAsState()
    val isRenameDialogOpen by viewModel.isRenameDialogOpen.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()

    // Tab state from ViewModel (persists across navigation)
    val selectedTab by viewModel.selectedTab.collectAsState()

    // Display settings from SharedPreferences (persisted per user)
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var sheetBarsPerRow by remember { mutableIntStateOf(prefs.getInt(KEY_BARS_PER_ROW, -1)) }
    var sheetScale by remember { mutableFloatStateOf(prefs.getFloat(KEY_SCALE, 0.9f)) }
    var sheetWebView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    // PDF print pending state: when true, we're waiting for alphaTab to re-render at print width
    var printPending by remember { mutableStateOf(false) }
    var isMoveMode by remember { mutableStateOf(false) }

    LaunchedEffect(selectedNoteIndex) {
        if (selectedNoteIndex == null) isMoveMode = false
    }

    // Persist window size from prefs on first load
    LaunchedEffect(Unit) {
        val savedWindowSize = prefs.getFloat(KEY_WINDOW_SIZE, 5f)
        viewModel.setWindowSizeSec(savedWindowSize)
    }

    // Dialog state
    var showClearDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    var showBpmDialog by remember { mutableStateOf(false) }
    var showInstrumentDialog by remember { mutableStateOf(false) }
    var waveformEditorHasPendingChanges by remember { mutableStateOf(false) }
    var showWaveformDiscardDialog by remember { mutableStateOf(false) }
    var pendingWaveformIntent by remember { mutableStateOf<Triple<Int?, Float?, Float?>?>(null) }
    var pendingTabSelection by remember { mutableStateOf<Int?>(null) }
    var pendingPostDiscardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val interactionScope = rememberCoroutineScope()

    fun queueWaveformDiscard(
        waveformIntent: Triple<Int?, Float?, Float?>?,
        tabSelection: Int?,
        postDiscardAction: (() -> Unit)? = null
    ) {
        pendingWaveformIntent = waveformIntent
        pendingTabSelection = tabSelection
        pendingPostDiscardAction = postDiscardAction
        showWaveformDiscardDialog = true
    }

    fun hasUnconfirmedWaveformEdits(): Boolean {
        return selectedNoteIndex != null && waveformEditorHasPendingChanges
    }

    fun runGuardedAction(action: () -> Unit) {
        if (hasUnconfirmedWaveformEdits()) {
            queueWaveformDiscard(Triple(null, null, null), null, action)
            return
        }

        if (selectedNoteIndex != null) {
            interactionScope.launch {
                withFrameNanos { }
                if (hasUnconfirmedWaveformEdits()) {
                    queueWaveformDiscard(Triple(null, null, null), null, action)
                } else {
                    action()
                }
            }
            return
        }

        action()
    }

    fun applyWaveformIntent(intent: Triple<Int?, Float?, Float?>) {
        val noteIndex = intent.first
        val cursorFraction = intent.second
        val seekFraction = intent.third

        if (noteIndex != null) {
            viewModel.selectNote(noteIndex)
        } else {
            viewModel.selectNote(null)
            if (cursorFraction != null) {
                viewModel.setEditCursor(cursorFraction)
            }
        }

        if (seekFraction != null) {
            viewModel.seekAudioOnly(seekFraction)
        }
    }

    fun requestWaveformIntent(noteIndex: Int?, cursorFraction: Float?, seekFraction: Float?) {
        val requestedIntent = Triple(noteIndex, cursorFraction, seekFraction)
        val isSwitchingAwayFromSelected = selectedNoteIndex != null &&
            (noteIndex != selectedNoteIndex || cursorFraction != null)

        if (!isSwitchingAwayFromSelected) {
            applyWaveformIntent(requestedIntent)
            return
        }

        if (waveformEditorHasPendingChanges) {
            queueWaveformDiscard(requestedIntent, null)
            return
        }

        // Give the editor one frame to propagate pending state before applying an edit-exit intent.
        interactionScope.launch {
            withFrameNanos { }
            val stillSwitchingAway = selectedNoteIndex != null &&
                (noteIndex != selectedNoteIndex || cursorFraction != null)
            if (stillSwitchingAway && waveformEditorHasPendingChanges) {
                queueWaveformDiscard(requestedIntent, null)
            } else {
                applyWaveformIntent(requestedIntent)
            }
        }
    }

    fun requestTabChange(tabIndex: Int) {
        if (tabIndex == selectedTab) return

        val leavingWaveformEditContext =
            selectedTab == 2 && tabIndex != 2 && selectedNoteIndex != null

        if (!leavingWaveformEditContext) {
            viewModel.setSelectedTab(tabIndex)
            return
        }

        if (waveformEditorHasPendingChanges) {
            queueWaveformDiscard(Triple(null, null, null), tabIndex)
            return
        }

        // Re-check on next frame to avoid missing very recent edits before tab switch.
        interactionScope.launch {
            withFrameNanos { }
            val stillLeavingWaveform =
                selectedTab == 2 && tabIndex != 2 && selectedNoteIndex != null
            if (stillLeavingWaveform && waveformEditorHasPendingChanges) {
                queueWaveformDiscard(Triple(null, null, null), tabIndex)
            } else {
                viewModel.setSelectedTab(tabIndex)
            }
        }
    }

    // Back button: dismiss dialogs/editor/selection before navigating back
    BackHandler(
        enabled = showClearDialog || showKeyDialog || showTimeDialog || showBpmDialog
                || showInstrumentDialog || isRenameDialogOpen || selectedNoteIndex != null
    ) {
        when {
            showClearDialog -> showClearDialog = false
            showKeyDialog -> showKeyDialog = false
            showTimeDialog -> showTimeDialog = false
            showBpmDialog -> showBpmDialog = false
            showInstrumentDialog -> showInstrumentDialog = false
            isRenameDialogOpen -> viewModel.closeRenameDialog()
            selectedNoteIndex != null -> {
                if (waveformEditorHasPendingChanges) {
                    queueWaveformDiscard(Triple(null, null, null), null)
                } else {
                    viewModel.selectNote(null)
                }
            }
        }
    }

    // Load idea on first composition
    LaunchedEffect(ideaId) {
        viewModel.loadIdea(ideaId)
    }

    // Auto-scroll window to follow playback
    LaunchedEffect(playbackProgress) {
        if (playbackState == AudioPlayer.PlaybackState.PLAYING) {
            viewModel.updateWindowForPlayback(playbackProgress)
        }
    }

    // Rename dialog
    if (isRenameDialogOpen) {
        var renameText by remember(idea?.title) { mutableStateOf(idea?.title ?: "") }
        AlertDialog(
            onDismissRequest = { viewModel.closeRenameDialog() },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    trailingIcon = {
                        if (renameText.isNotEmpty()) {
                            IconButton(onClick = { renameText = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear title")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameIdea(renameText)
                    viewModel.closeRenameDialog()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeRenameDialog() }) { Text("Cancel") }
            }
        )
    }

    // Clear all confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Notes") },
            text = { Text("Are you sure you want to delete all ${notesList.size} notes? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllNotes()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear All") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showWaveformDiscardDialog) {
        AlertDialog(
            onDismissRequest = {
                pendingWaveformIntent = null
                pendingTabSelection = null
                pendingPostDiscardAction = null
                showWaveformDiscardDialog = false
            },
            title = { Text("Discard Changes?") },
            text = { Text("You have unsaved waveform note edits. Discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    val intent = pendingWaveformIntent
                    val tabSelection = pendingTabSelection
                    val postDiscardAction = pendingPostDiscardAction
                    pendingWaveformIntent = null
                    pendingTabSelection = null
                    pendingPostDiscardAction = null
                    showWaveformDiscardDialog = false
                    if (intent != null) {
                        applyWaveformIntent(intent)
                    }
                    if (tabSelection != null) {
                        viewModel.setSelectedTab(tabSelection)
                    }
                    postDiscardAction?.invoke()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingWaveformIntent = null
                    pendingTabSelection = null
                    pendingPostDiscardAction = null
                    showWaveformDiscardDialog = false
                }) { Text("Keep Editing") }
            }
        )
    }

    // Key signature edit dialog
    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Key Signature") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Changing key will regenerate sheet music. Notes are not transposed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    KeySignature.ALL_KEYS.forEach { key ->
                        val isCurrent = idea?.keySignature == key.toString()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    key.toString(),
                                    fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            },
                            onClick = {
                                viewModel.updateKeySignature(key)
                                showKeyDialog = false
                            },
                            leadingIcon = {
                                if (isCurrent) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Time signature edit dialog
    if (showTimeDialog) {
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            title = { Text("Time Signature") },
            text = {
                Column {
                    Text(
                        "Changing time signature will regenerate sheet music.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    TimeSignature.SUPPORTED.forEach { ts ->
                        val isCurrent = idea?.timeSignature == ts.toString()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    ts.toString(),
                                    fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            },
                            onClick = {
                                viewModel.updateTimeSignature(ts)
                                showTimeDialog = false
                            },
                            leadingIcon = {
                                if (isCurrent) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTimeDialog = false }) { Text("Cancel") }
            }
        )
    }

    // BPM edit dialog
    if (showBpmDialog) {
        var bpmText by remember(idea?.tempoBpm) { mutableStateOf((idea?.tempoBpm ?: 120).toString()) }
        AlertDialog(
            onDismissRequest = { showBpmDialog = false },
            title = { Text("Tempo (BPM)") },
            text = {
                Column {
                    Text(
                        "Changing BPM will affect note durations and regenerate sheet music.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = { bpmText = it.filter { c -> c.isDigit() } },
                        label = { Text("BPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Quick BPM presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(60, 80, 100, 120, 140).forEach { preset ->
                            FilterChip(
                                selected = bpmText == preset.toString(),
                                onClick = { bpmText = preset.toString() },
                                label = { Text(preset.toString()) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bpmText.toIntOrNull()?.let { bpm ->
                            if (bpm in 20..300) {
                                viewModel.updateTempoBpm(bpm)
                                showBpmDialog = false
                            }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showBpmDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Instrument edit dialog
    if (showInstrumentDialog) {
        AlertDialog(
            onDismissRequest = { showInstrumentDialog = false },
            title = { Text("Instrument") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Changing instrument affects transposition and clef in sheet music.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    InstrumentProfile.ALL.forEach { inst ->
                        val isCurrent = idea?.instrument == inst.name
                        DropdownMenuItem(
                            text = {
                                Text(
                                    inst.displayName,
                                    fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            },
                            onClick = {
                                viewModel.updateInstrument(inst)
                                showInstrumentDialog = false
                            },
                            leadingIcon = {
                                if (isCurrent) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInstrumentDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = idea?.title ?: "Preview",
                        modifier = Modifier.clickable { runGuardedAction { viewModel.openRenameDialog() } }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { runGuardedAction { onNavigateBack() } }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    var selectedAlgorithm by remember { mutableStateOf(PitchAlgorithm.DEFAULT) }

                    Box {
                        IconButton(onClick = { runGuardedAction { showMenu = true } }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.widthIn(max = 260.dp)
                        ) {
                            // ── Share ──
                            Text(
                                "Share",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("MIDI") },
                                onClick = { showMenu = false; viewModel.shareMidi(context) },
                                leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("MusicXML") },
                                onClick = { showMenu = false; viewModel.shareMusicXml(context) },
                                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Audio") },
                                onClick = { showMenu = false; viewModel.shareAudio(context) },
                                leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("All Files") },
                                onClick = { showMenu = false; viewModel.shareAll(context) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Snapshot (.zip)") },
                                onClick = { showMenu = false; viewModel.shareSnapshot(context) },
                                leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("PDF (Sheet Music)") },
                                onClick = {
                                    showMenu = false
                                    sheetWebView?.let { wv ->
                                        // Trigger re-render at print-friendly width
                                        android.util.Log.i("NNPdf", "PDF export triggered: WebView width=${wv.width}px, height=${wv.height}px, scale=${wv.scale}")
                                        printPending = true
                                        // Hide WebView to prevent the visible "flash" of
                                        // bars rearranging during print-width re-render
                                        wv.alpha = 0f
                                        wv.evaluateJavascript("prepareForPrint()", null)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                                enabled = sheetWebView != null && !printPending
                            )
                            DropdownMenuItem(
                                text = { Text("Save to Device") },
                                onClick = { showMenu = false; viewModel.saveToDevice(context) },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Open in Files") },
                                onClick = { showMenu = false; viewModel.openInFileManager(context) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // ── Display ──
                            Text(
                                "Display",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            // Window size control
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        var windowText by remember(windowSizeSec) {
                                            mutableStateOf(String.format("%.0f", windowSizeSec))
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Window", style = MaterialTheme.typography.bodySmall)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            OutlinedTextField(
                                                value = windowText,
                                                onValueChange = { newVal ->
                                                    windowText = newVal.filter { c -> c.isDigit() || c == '.' }
                                                },
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.Number,
                                                    imeAction = ImeAction.Done
                                                ),
                                                keyboardActions = KeyboardActions(
                                                    onDone = {
                                                        windowText.toFloatOrNull()?.let { v ->
                                                            viewModel.setWindowSizeSec(v)
                                                            prefs.edit().putFloat(KEY_WINDOW_SIZE, v).apply()
                                                        }
                                                    }
                                                ),
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.width(72.dp).height(48.dp),
                                                suffix = { Text("s", style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                        Slider(
                                            value = windowSizeSec,
                                            onValueChange = {
                                                viewModel.setWindowSizeSec(it)
                                                prefs.edit().putFloat(KEY_WINDOW_SIZE, it).apply()
                                            },
                                            valueRange = 1f..30f,
                                            steps = 28
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = true,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            )
                            // Bars per row control
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Bars/Row", style = MaterialTheme.typography.bodySmall)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                if (sheetBarsPerRow == -1) "Auto" else "$sheetBarsPerRow",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = sheetBarsPerRow.toFloat(),
                                            onValueChange = {
                                                sheetBarsPerRow = it.toInt()
                                                prefs.edit().putInt(KEY_BARS_PER_ROW, it.toInt()).apply()
                                            },
                                            valueRange = -1f..8f,
                                            steps = 8
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = true,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            )
                            // Scale control
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Scale", style = MaterialTheme.typography.bodySmall)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                String.format("%.1fx", sheetScale),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = sheetScale,
                                            onValueChange = {
                                                sheetScale = ((it * 10).toInt() / 10f)
                                                prefs.edit().putFloat(KEY_SCALE, sheetScale).apply()
                                            },
                                            valueRange = 0.5f..2.0f,
                                            steps = 14
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = true,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // ── Recording ──
                            Text(
                                "Recording",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSettings?.invoke()
                                },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // ── Song Info ──
                            Text(
                                "Song Info",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            idea?.let { melodyIdea ->
                                DropdownMenuItem(
                                    text = {
                                        Text("Key: ${melodyIdea.keySignature ?: "?"}", style = MaterialTheme.typography.bodySmall)
                                    },
                                    onClick = { showMenu = false; showKeyDialog = true },
                                    trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp)) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("Time: ${melodyIdea.timeSignature ?: "?"}", style = MaterialTheme.typography.bodySmall)
                                    },
                                    onClick = { showMenu = false; showTimeDialog = true },
                                    trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp)) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("BPM: ${melodyIdea.tempoBpm}", style = MaterialTheme.typography.bodySmall)
                                    },
                                    onClick = { showMenu = false; showBpmDialog = true },
                                    trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp)) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("Notes: ${notesList.size}", style = MaterialTheme.typography.bodySmall)
                                    },
                                    onClick = {},
                                    enabled = false,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                                DropdownMenuItem(
                                    text = {
                                        val instrumentName = InstrumentProfile.ALL
                                            .find { it.name == melodyIdea.instrument }
                                            ?.displayName ?: melodyIdea.instrument
                                        Text("Instrument: $instrumentName", style = MaterialTheme.typography.bodySmall)
                                    },
                                    onClick = { showMenu = false; showInstrumentDialog = true },
                                    trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp)) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // ── Transcription ──
                            Text(
                                "Transcription",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            if (isRetranscribing) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Retranscribing...")
                                        }
                                    },
                                    onClick = {},
                                    enabled = false
                                )
                            } else {
                                PitchAlgorithm.values().forEach { algo ->
                                    DropdownMenuItem(
                                        text = { Text(algo.displayName, style = MaterialTheme.typography.bodySmall) },
                                        onClick = { selectedAlgorithm = algo },
                                        leadingIcon = {
                                            if (algo == selectedAlgorithm) {
                                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Retranscribe") },
                                    onClick = {
                                        showMenu = false
                                        viewModel.retranscribe(pitchAlgorithm = selectedAlgorithm)
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                    enabled = idea?.audioFilePath != null
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // ── Editing ──
                            Text(
                                "Editing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Clear All Notes", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showClearDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.DeleteSweep,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error message
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Tab selector
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { requestTabChange(0) },
                    text = { Text("Sheet") },
                    icon = { Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { requestTabChange(1) },
                    text = { Text("Notes") },
                    icon = { Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { requestTabChange(2) },
                    text = { Text("Waveform") },
                    icon = { Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            // Content based on selected tab
            // Always render SheetMusicWebView so PDF export works from any tab
            val sheetCurrentNoteIndex = remember(playbackProgress, notesList, leadingRestOffset) {
                viewModel.getCurrentNoteIndex(playbackProgress) + leadingRestOffset
            }
            Box(
                modifier = Modifier
                    .weight(if (selectedTab == 0) 1f else 0.001f)
                    .fillMaxWidth()
                    .background(Color.White)
                    .then(if (selectedTab != 0) Modifier.height(0.dp) else Modifier)
            ) {
                if (musicXml != null) {
                    SheetMusicWebView(
                        musicXml = musicXml,
                        modifier = Modifier.fillMaxSize(),
                        playbackProgress = playbackProgress,
                        currentNoteIndex = sheetCurrentNoteIndex,
                        isPlaying = playbackState == AudioPlayer.PlaybackState.PLAYING,
                        barsPerRow = sheetBarsPerRow,
                        scale = sheetScale,
                        onWebViewReady = { sheetWebView = it },
                        onError = { viewModel.setError(it) },
                        onPrintReady = {
                            // alphaTab has re-rendered at print width — now print
                            printPending = false
                            sheetWebView?.let { wv ->
                                android.util.Log.i("NNPdf", "Print ready: WebView width=${wv.width}, height=${wv.height}")
                                // Capture debug dump and write to file for diagnostics
                                wv.evaluateJavascript("dumpPdfDebugInfo(false)") { debugJson ->
                                    android.util.Log.i("NNPdf", "Pre-print debug: $debugJson")
                                    try {
                                        val debugFile = java.io.File(
                                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                                            "NoteNotes_PDF_Debug.txt"
                                        )
                                        debugFile.parentFile?.mkdirs()
                                        debugFile.writeText("PDF Debug Dump - ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n$debugJson")
                                        android.util.Log.i("NNPdf", "Debug file written: ${debugFile.absolutePath}")
                                    } catch (e: Exception) {
                                        android.util.Log.w("NNPdf", "Could not write debug file: ${e.message}")
                                    }
                                }
                                val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
                                val adapter = wv.createPrintDocumentAdapter(idea?.title ?: "Sheet Music")
                                // Margins and width from PdfConstants — single source of truth
                                val m = com.notenotes.export.PdfConstants.MARGIN_MILS
                                val attributes = android.print.PrintAttributes.Builder()
                                    .setMediaSize(android.print.PrintAttributes.MediaSize.NA_LETTER)
                                    .setMinMargins(android.print.PrintAttributes.Margins(m, m, m, m))
                                    .build()
                                android.util.Log.i("NNPdf", "Printing with Letter size, margins=${m}mils (width=${com.notenotes.export.PdfConstants.PRINT_WIDTH_PX}px)")
                                printManager.print("${idea?.title ?: "NoteNotes"} Sheet Music", adapter, attributes)
                                // Restore original display settings after a brief delay
                                // then show the WebView again once the restore render completes
                                wv.postDelayed({
                                    wv.evaluateJavascript("restoreAfterPrint()", null)
                                    // Give alphaTab time to re-render at screen settings before showing
                                    wv.postDelayed({ wv.alpha = 1f }, com.notenotes.export.PdfConstants.WEBVIEW_SHOW_DELAY_MS)
                                }, com.notenotes.export.PdfConstants.RESTORE_RENDER_DELAY_MS)
                            }
                        }
                    )
                } else if (selectedTab == 0) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            when (selectedTab) {
                1 -> {
                    // Note name view (editable)
                    val notesCurrentNoteIndex = remember(playbackProgress, notesList) {
                        viewModel.getCurrentNoteIndex(playbackProgress)
                    }
                    val notesNoteProgress = remember(playbackProgress, notesList) {
                        viewModel.getPlaybackFractionInNote(playbackProgress)
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        NoteNameView(
                            notes = notesList,
                            modifier = Modifier.fillMaxSize(),
                            tempoBpm = idea?.tempoBpm ?: 120,
                            currentNoteIndex = notesCurrentNoteIndex,
                            noteProgressFraction = notesNoteProgress,
                            onUpdateNote = { index, guitarString, guitarFret ->
                                viewModel.updateNoteAt(index, guitarString, guitarFret)
                            },
                            onDeleteNote = { index ->
                                viewModel.selectNote(index)
                                viewModel.deleteSelectedNote()
                            },
                            onUpdateChordNote = { index, newPitches, newPositions ->
                                viewModel.updateChordPitches(index, newPitches, newPositions)
                            }
                        )
                    }
                }
                2 -> {
                    // Waveform tab with editor
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Waveform view
                        WaveformView(
                            waveformData = waveformData,
                            notes = notesList,
                            playbackProgress = playbackProgress,
                            durationMs = audioDurationMs,
                            tempoBpm = idea?.tempoBpm ?: 120,
                            onSeek = { fraction ->
                                runGuardedAction { viewModel.seekAudioOnly(fraction) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            selectedNoteIndex = selectedNoteIndex,
                            editCursorFraction = editCursorFraction,
                            onEditIntent = { noteIndex, cursorFraction, seekFraction ->
                                requestWaveformIntent(noteIndex, cursorFraction, seekFraction)
                            },
                            windowStartFraction = windowStartFraction,
                            windowSizeSec = windowSizeSec,
                            isMoveMode = isMoveMode,
                            onMoveSelectedNote = { noteIndex, newStartFraction ->
                                viewModel.moveNoteToFraction(noteIndex, newStartFraction)
                            }
                        )

                        // Always-visible note editor
                        NoteEditorPanel(
                            editCursorActive = editCursorFraction != null,
                            timePointSeconds = (editCursorFraction ?: 0f) * audioDurationMs / 1000f,
                            tempoBpm = idea?.tempoBpm ?: 120,
                            onAddNote = { editorNotes ->
                                viewModel.addNote(editorNotes)
                            },
                            onDeleteSelected = if (selectedNoteIndex != null) {
                                { viewModel.deleteSelectedNote(selectAdjacent = false) }
                            } else null,
                            hasSelectedNote = selectedNoteIndex != null,
                            selectedNote = viewModel.selectedNote,
                            selectedNoteIndex = selectedNoteIndex,
                            canSplitAtCursor = viewModel.isCursorInsideNote(),
                            onSplitAtCursor = { viewModel.splitNoteAtCursor() },
                            onUpdateNote = { index, guitarString, guitarFret ->
                                viewModel.updateNoteAt(index, guitarString, guitarFret)
                            },
                            onUpdateChordNote = { index, pitches, stringFrets ->
                                viewModel.updateChordPitches(index, pitches, stringFrets)
                            },
                            allNotes = notesList,
                            isMoveMode = isMoveMode,
                            onToggleMoveMode = { isMoveMode = !isMoveMode },
                            onCopyFromNote = { sourceIndex ->
                                val source = notesList.getOrNull(sourceIndex) ?: return@NoteEditorPanel
                                val targetIndex = selectedNoteIndex
                                if (targetIndex != null) {
                                    viewModel.copyChordFrom(sourceIndex, targetIndex)
                                } else {
                                    val entries = mutableListOf<Pair<Int, Pair<Int, Int>>>()
                                    val primaryPos = if (source.guitarString != null && source.guitarFret != null) {
                                        Pair(source.guitarString, source.guitarFret)
                                    } else {
                                        GuitarUtils.fromMidi(source.midiPitch)
                                    } ?: Pair(0, 0)
                                    entries.add(Pair(source.midiPitch, primaryPos))

                                    source.chordPitches.indices.forEach { idx ->
                                        val pitch = source.chordPitches[idx]
                                        val sf = source.safeChordStringFrets.getOrNull(idx)
                                            ?: GuitarUtils.fromMidi(pitch)
                                            ?: Pair((idx + 1).coerceAtMost(5), 0)
                                        entries.add(Pair(pitch, sf))
                                    }

                                    if (editCursorFraction == null) {
                                        viewModel.setEditCursor(playbackProgress)
                                    }
                                    viewModel.addNote(entries)
                                }
                            },
                            onPendingChangesChanged = { hasPendingChanges ->
                                waveformEditorHasPendingChanges = hasPendingChanges
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Transport controls
            TransportControls(
                playbackState = playbackState,
                onPlay = { runGuardedAction { viewModel.playVoiceMemo() } },
                onPause = { runGuardedAction { viewModel.pausePlayback() } },
                onResume = { runGuardedAction { viewModel.resumePlayback() } },
                onStop = { runGuardedAction { viewModel.stopPlayback() } },
                durationMs = audioDurationMs,
                currentProgress = playbackProgress,
                onSeek = { fraction -> runGuardedAction { viewModel.seekTo(fraction) } },
                windowStartFraction = windowStartFraction,
                windowSizeSec = windowSizeSec,
                isWindowLocked = isWindowLocked,
                onWindowBack = { runGuardedAction { viewModel.moveWindow(-1f) } },
                onWindowForward = { runGuardedAction { viewModel.moveWindow(1f) } },
                onToggleLock = { runGuardedAction { viewModel.toggleWindowLock() } },
                playbackSpeed = playbackSpeed,
                onSpeedChange = { speed -> runGuardedAction { viewModel.setPlaybackSpeed(speed) } },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
