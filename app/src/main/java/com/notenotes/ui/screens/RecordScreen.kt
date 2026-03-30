package com.notenotes.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notenotes.audio.AudioRecorder
import com.notenotes.model.InstrumentProfile
import com.notenotes.ui.components.TransportControls
import com.notenotes.ui.components.WaveformView
import com.notenotes.ui.components.StopPlaybackOnDispose
import com.notenotes.audio.AudioPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onNavigateToPreview: (Long) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    rerecordIdeaId: Long? = null,
    viewModel: RecordViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val savedIdeaId by viewModel.savedIdeaId.collectAsState()

    val waveformData by viewModel.waveformData.collectAsState()
    val loadedIdea by viewModel.loadedIdea.collectAsState()
    val isChanged by viewModel.isChanged.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val waveformWindowSize by viewModel.waveformWindowSize.collectAsState()
    val recordHeadFraction by viewModel.recordHeadFraction.collectAsState()
    val punchInPositionMs by viewModel.punchInPositionMs.collectAsState()
    val isSynced by viewModel.isSynced.collectAsState()

    val context = LocalContext.current

        var isWindowLocked by remember { mutableStateOf(false) }
    val targetWindowStartSec = remember { mutableFloatStateOf(0f) }
    val windowStartSec by animateFloatAsState(
        targetValue = targetWindowStartSec.floatValue,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "windowScrollSec"
    )
    val totalSecData = maxOf(waveformData?.durationSeconds ?: 0.1f, waveformWindowSize)
    val windowStartFraction = if (totalSecData > 0) windowStartSec / totalSecData else 0f

    LaunchedEffect(punchInPositionMs, waveformData?.durationSeconds, waveformWindowSize, isWindowLocked) {
        if (isWindowLocked) return@LaunchedEffect
        val currentEditSec = punchInPositionMs / 1000f
        val isRecording = uiState == RecordViewModel.UiState.RECORDING
        
        if (currentEditSec > targetWindowStartSec.floatValue + waveformWindowSize * 0.75f) {
            val maxScroll = if (isRecording) Float.MAX_VALUE else maxOf(0f, (waveformData?.durationSeconds ?: 0.1f) - waveformWindowSize)
            targetWindowStartSec.floatValue = (currentEditSec - waveformWindowSize * 0.75f).coerceIn(0f, maxScroll)
        } else if (currentEditSec < targetWindowStartSec.floatValue) {
            targetWindowStartSec.floatValue = currentEditSec
        }
    }

    LaunchedEffect(rerecordIdeaId) {
        if (rerecordIdeaId != null) {
            viewModel.loadIdeaForRerecord(rerecordIdeaId)
        }
    }

    // Request MANAGE_EXTERNAL_STORAGE permission on first launch (API 30+)
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        }
    }

    // Navigate to preview when done
    LaunchedEffect(savedIdeaId) {
        savedIdeaId?.let { id ->
            onNavigateToPreview(id)
            viewModel.reset()
        }
    }

    // Stop playback when this composable leaves composition
    StopPlaybackOnDispose { viewModel.stopPlayback() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NoteNotes") },
                actions = {
                    IconButton(onClick = onNavigateToLibrary, modifier = Modifier.size(52.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.LibraryMusic, contentDescription = "Library", modifier = Modifier.size(20.dp))
                            Text("Library", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                        }
                    }
                    IconButton(onClick = onNavigateToStats, modifier = Modifier.size(52.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.BarChart, contentDescription = "Stats", modifier = Modifier.size(20.dp))
                            Text("Stats", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                        }
                    }
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(52.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
                            Text("Settings", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
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
                // Reduce horizontal margins so transport controls expand wider
                .padding(horizontal = 12.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val currentDisplayProgress = if (uiState == RecordViewModel.UiState.RECORDING) recordHeadFraction else playbackProgress

            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                WaveformView(
                    waveformData = waveformData,
                    notes = emptyList(),
                    playbackProgress = currentDisplayProgress,
                    editCursorFraction = if (waveformData != null && waveformData!!.durationSeconds > 0) (punchInPositionMs / 1000f) / waveformData!!.durationSeconds else null,
                    durationMs = (waveformData?.durationSeconds?.times(1000f))?.toInt() ?: 0,
                    tempoBpm = 120,
                    windowSizeSec = waveformWindowSize,
                    windowStartFraction = windowStartFraction,
                    onSeek = { viewModel.seekEditHead(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState == RecordViewModel.UiState.IDLE) {
                Text(
                    text = if (loadedIdea != null) "Tap to record over: ${loadedIdea?.title}" else "Tap to record your melody",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canReset = uiState != RecordViewModel.UiState.RECORDING && isChanged

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { viewModel.reset() },
                        enabled = canReset,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset", modifier = Modifier.size(32.dp))
                    }
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { viewModel.toggleSync() }) {
                        Icon(
                            imageVector = if (isSynced) Icons.Filled.Link else Icons.Filled.LinkOff,
                            contentDescription = if (isSynced) "Synced" else "Desynced",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = if (isSynced) "Synced" else "Desynced",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                RecordButton(
                    isRecording = uiState == RecordViewModel.UiState.RECORDING,
                    enabled = uiState == RecordViewModel.UiState.IDLE || 
                              uiState == RecordViewModel.UiState.RECORDING || 
                              uiState == RecordViewModel.UiState.PAUSED || 
                              uiState == RecordViewModel.UiState.ERROR,
                    onClick = {
                        when (uiState) {
                            RecordViewModel.UiState.RECORDING -> viewModel.pauseRecording()
                            RecordViewModel.UiState.ERROR -> viewModel.reset()
                            else -> {
                                val hasPermission = context.checkSelfPermission(
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    viewModel.startRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    }
                )
                
                val canSave = (uiState == RecordViewModel.UiState.PAUSED || uiState == RecordViewModel.UiState.IDLE) && isChanged

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { viewModel.saveRecording() },
                        enabled = canSave,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save", modifier = Modifier.size(32.dp))
                    }
                    Text("Save", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = { viewModel.jumpPlayheadToEdit() },
                        border = androidx.compose.foundation.BorderStroke(2.dp, com.notenotes.ui.theme.EditCursorColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = com.notenotes.ui.theme.EditCursorColor),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .padding(end = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Jump\nto Edit", textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.jumpEditToPlayhead() },
                        border = androidx.compose.foundation.BorderStroke(2.dp, com.notenotes.ui.theme.PlayheadCursorColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = com.notenotes.ui.theme.PlayheadCursorColor),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .padding(start = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Jump\nto Playhead", textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

            TransportControls(
                playbackState = playbackState,
                onPlay = { viewModel.play() },
                onPause = { viewModel.pause() },
                onResume = { viewModel.play() },
                onStop = { viewModel.stopPlayback() },
                durationMs = (waveformData?.durationSeconds?.times(1000f))?.toInt() ?: 0,
                currentProgress = currentDisplayProgress,
                onSeek = { viewModel.seekPlayhead(it) },
                windowStartFraction = if (totalSecData > 0) targetWindowStartSec.floatValue / totalSecData else 0f,
                windowSizeSec = waveformWindowSize,
                playbackSpeed = playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                isWindowLocked = isWindowLocked,
                onPanToEditCursor = {}, // Not applicable in RecordScreen
                onPanToPlayhead = {
                     if (totalSecData > 0) {
                         val windowFractionSize = waveformWindowSize / totalSecData
                         val newStart = (currentDisplayProgress - windowFractionSize / 2f).coerceIn(0f, (1f - windowFractionSize).coerceAtLeast(0f))
                         targetWindowStartSec.floatValue = newStart * totalSecData
                     }
                },
                onToggleLock = { isWindowLocked = !isWindowLocked }
            )

            Spacer(modifier = Modifier.height(16.dp))

            /* Replaced Visualizers */
            if (uiState != RecordViewModel.UiState.RECORDING) {
                when (uiState) {
                    RecordViewModel.UiState.PROCESSING -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        val auto = viewModel.autoTranscribe.collectAsState()
                        Text(
                            text = if (auto.value) "Transcribing..." else "Saving...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    RecordViewModel.UiState.ERROR -> {
                        Text(
                            text = errorMessage ?: "An error occurred",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.2f else 1f,
        animationSpec = if (isRecording) {
            infiniteRepeatable(
                animation = tween(800, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(300)
        },
        label = "recordButtonScale"
    )

    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(80.dp)
            .scale(scale),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
            modifier = Modifier.size(40.dp),
            tint = Color.White
        )
    }
}

@Composable
private fun AmplitudeIndicator(level: Float) {
    LinearProgressIndicator(
        progress = { level },
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .height(8.dp),
        color = when {
            level > 0.8f -> Color.Red
            level > 0.5f -> Color.Yellow
            else -> MaterialTheme.colorScheme.primary
        }
    )
}

private fun formatDuration(seconds: Double): String {
    val mins = (seconds / 60).toInt()
    val secs = (seconds % 60).toInt()
    val tenths = ((seconds % 1) * 10).toInt()
    return "%d:%02d.%d".format(mins, secs, tenths)
}

/**
 * Music Tracer - Real-time display of detected pitch during recording.
 * Shows note name, frequency, confidence, and cents deviation.
 */
@Composable
private fun MusicTracer(pitchInfo: AudioRecorder.LivePitchInfo) {
    val hasNote = pitchInfo.midiNote >= 0
    val noteColor = if (hasNote) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .border(
                width = 2.dp,
                color = if (hasNote) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                       else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            )
            .background(
                color = if (hasNote) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                       else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Note name (large)
        Text(
            text = pitchInfo.noteName,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = noteColor,
            textAlign = TextAlign.Center
        )

        if (hasNote) {
            Spacer(modifier = Modifier.height(4.dp))

            // Frequency and cents
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${String.format("%.1f", pitchInfo.frequencyHz)} Hz",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val centsStr = if (pitchInfo.cents >= 0) "+${String.format("%.0f", pitchInfo.cents)}"
                              else String.format("%.0f", pitchInfo.cents)
                val centsColor = when {
                    kotlin.math.abs(pitchInfo.cents) < 10 -> Color(0xFF4CAF50) // green = in tune
                    kotlin.math.abs(pitchInfo.cents) < 25 -> Color(0xFFFFC107) // yellow
                    else -> Color(0xFFF44336) // red = out of tune
                }
                Text(
                    text = "$centsStr cents",
                    style = MaterialTheme.typography.bodySmall,
                    color = centsColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Confidence bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Conf:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp)
                )
                LinearProgressIndicator(
                    progress = { pitchInfo.confidence.toFloat() },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                    color = when {
                        pitchInfo.confidence > 0.85 -> Color(0xFF4CAF50)
                        pitchInfo.confidence > 0.7 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                )
                Text(
                    text = "${(pitchInfo.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }
        } else {
            Text(
                text = "Listening...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}



