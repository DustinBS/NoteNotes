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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onNavigateToPreview: (Long) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: RecordViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val savedIdeaId by viewModel.savedIdeaId.collectAsState()
    val selectedInstrument by viewModel.selectedInstrument.collectAsState()
    val autoTranscribe by viewModel.autoTranscribe.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val amplitudeLevel by viewModel.amplitudeLevel.collectAsState()
    val livePitch by viewModel.livePitch.collectAsState()
    val context = LocalContext.current

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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Instrument selector
            InstrumentSelector(
                selected = selectedInstrument,
                onSelect = { viewModel.setInstrument(it) },
                enabled = uiState == RecordViewModel.UiState.IDLE
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-transcribe toggle
            Row(
                modifier = Modifier.fillMaxWidth(0.7f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Auto-transcribe",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = autoTranscribe,
                    onCheckedChange = { viewModel.setAutoTranscribe(it) },
                    enabled = uiState == RecordViewModel.UiState.IDLE
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Recording duration display
            if (uiState == RecordViewModel.UiState.RECORDING) {
                Text(
                    text = formatDuration(recordingDuration),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Light,
                    color = Color.Red
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Music Tracer - shows detected note in real-time
                MusicTracer(pitchInfo = livePitch)
                Spacer(modifier = Modifier.height(16.dp))

                // Amplitude indicator
                AmplitudeIndicator(level = amplitudeLevel)
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Status message
            when (uiState) {
                RecordViewModel.UiState.IDLE -> {
                    Text(
                        text = "Tap to record your melody",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RecordViewModel.UiState.PROCESSING -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (autoTranscribe) "Transcribing..." else "Saving...",
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

            Spacer(modifier = Modifier.height(32.dp))

            // Record button
            RecordButton(
                isRecording = uiState == RecordViewModel.UiState.RECORDING,
                enabled = uiState == RecordViewModel.UiState.IDLE || uiState == RecordViewModel.UiState.RECORDING || uiState == RecordViewModel.UiState.ERROR,
                onClick = {
                    when (uiState) {
                        RecordViewModel.UiState.RECORDING -> viewModel.stopRecording()
                        RecordViewModel.UiState.ERROR -> {
                            viewModel.reset()
                        }
                        else -> {
                            if (viewModel.amplitudeLevel.value >= 0f) {
                                // Check if already granted to avoid unnecessary dialog
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
                }
            )
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
        progress = level,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentSelector(
    selected: InstrumentProfile,
    onSelect: (InstrumentProfile) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Instrument") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            InstrumentProfile.ALL.forEach { instrument ->
                DropdownMenuItem(
                    text = { Text(instrument.displayName) },
                    onClick = {
                        onSelect(instrument)
                        expanded = false
                    }
                )
            }
        }
    }
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
                    progress = pitchInfo.confidence.toFloat(),
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
