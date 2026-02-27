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
import com.notenotes.ui.components.SheetMusicWebView
import com.notenotes.ui.components.TransportControls

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
    val playbackState by viewModel.playbackState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

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
                                text = { Text("Share Both") },
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

            // Sheet music display
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (musicXml != null) {
                    SheetMusicWebView(
                        musicXml = musicXml,
                        modifier = Modifier.fillMaxSize(),
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

            Divider()

            // Playback controls
            TransportControls(
                playbackState = playbackState,
                onPlay = { viewModel.playVoiceMemo() },
                onPause = { viewModel.pausePlayback() },
                onResume = { viewModel.resumePlayback() },
                onStop = { viewModel.stopPlayback() },
                modifier = Modifier.padding(16.dp)
            )
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
