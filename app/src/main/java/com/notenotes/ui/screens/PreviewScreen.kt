package com.notenotes.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notenotes.audio.AudioPlayer
import com.notenotes.ui.components.NoteNameView
import com.notenotes.ui.components.SheetMusicWebView
import com.notenotes.ui.components.TransportControls
import com.notenotes.ui.components.WaveformView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    ideaId: Long,
    onNavigateBack: () -> Unit,
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
    
    val isRetranscribing by viewModel.isRetranscribing.collectAsState()
    
    // Tab state: 0 = Sheet Music, 1 = Note Names, 2 = Waveform
    var selectedTab by remember { mutableIntStateOf(0) }

    // Load idea on first composition
    LaunchedEffect(ideaId) {
        viewModel.loadIdea(ideaId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(idea?.title ?: "Preview") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Export menu
                    var showExportMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Filled.Share, contentDescription = "Export")
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share MIDI") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.shareMidi(context)
                                },
                                leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Share MusicXML") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.shareMusicXml(context)
                                },
                                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Share Audio") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.shareAudio(context)
                                },
                                leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Share All") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.shareAll(context)
                                },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) }
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
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Info bar
            idea?.let { melodyIdea ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoChip(label = "Key", value = melodyIdea.keySignature ?: "?")
                    InfoChip(label = "Time", value = melodyIdea.timeSignature ?: "?")
                    InfoChip(label = "BPM", value = melodyIdea.tempoBpm.toString())
                    InfoChip(label = "Instrument", value = melodyIdea.instrument)
                }
                Divider()
            }

            // Tab selector: Sheet Music | Note Names | Waveform
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Sheet Music") },
                    icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Note Names") },
                    icon = { Icon(Icons.Filled.TextFields, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Waveform") },
                    icon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) }
                )
            }

            // Content based on selected tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> {
                        // Sheet music display
                        if (musicXml != null) {
                            SheetMusicWebView(
                                musicXml = musicXml,
                                modifier = Modifier.fillMaxSize(),
                                playbackProgress = playbackProgress,
                                isPlaying = playbackState == AudioPlayer.PlaybackState.PLAYING,
                                onError = { viewModel.setError(it) }
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    1 -> {
                        // Note name view
                        NoteNameView(
                            notes = notesList,
                            modifier = Modifier.fillMaxSize(),
                            tempoBpm = idea?.tempoBpm ?: 120
                        )
                    }
                    2 -> {
                        // Waveform view with note overlay
                        WaveformView(
                            waveformData = waveformData,
                            notes = notesList,
                            playbackProgress = playbackProgress,
                            durationMs = audioDurationMs,
                            tempoBpm = idea?.tempoBpm ?: 120,
                            onSeek = { fraction -> viewModel.seekTo(fraction) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Divider()

            // Playback controls with progress bar
            TransportControls(
                playbackState = playbackState,
                onPlay = { viewModel.playVoiceMemo() },
                onPause = { viewModel.pausePlayback() },
                onResume = { viewModel.resumePlayback() },
                onStop = { viewModel.stopPlayback() },
                durationMs = audioDurationMs,
                currentProgress = playbackProgress,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Retranscribe button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                if (isRetranscribing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Retranscribing...", style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedButton(
                        onClick = { viewModel.retranscribe() },
                        enabled = idea?.audioFilePath != null
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retranscribe")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
