package com.notenotes.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notenotes.model.InstrumentProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    var selectedInstrument by remember { mutableStateOf(InstrumentProfile.GUITAR) }
    var defaultTempo by remember { mutableStateOf("120") }
    var windowSize by remember { mutableFloatStateOf(5f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Recording Settings",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Default instrument
            var instrumentExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = instrumentExpanded,
                onExpandedChange = { instrumentExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedInstrument.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Default Instrument") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = instrumentExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = instrumentExpanded,
                    onDismissRequest = { instrumentExpanded = false }
                ) {
                    InstrumentProfile.ALL.forEach { instrument ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(instrument.displayName)
                                    if (instrument.transposeSemitones != 0) {
                                        Text(
                                            text = "Transpose: ${if (instrument.transposeSemitones > 0) "+" else ""}${instrument.transposeSemitones} semitones",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                selectedInstrument = instrument
                                instrumentExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Default tempo
            OutlinedTextField(
                value = defaultTempo,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                        defaultTempo = newValue
                    }
                },
                label = { Text("Default Tempo (BPM)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Waveform window size
            Text(
                text = "Waveform Settings",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Window Size: ${windowSize.toInt()}s",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = windowSize,
                onValueChange = { windowSize = it },
                valueRange = 1f..30f,
                steps = 28,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Controls how many seconds of audio are shown in the waveform view at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "NoteNotes v0.1.0",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Auto-transcribing voice memo app for musicians.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Open Source Libraries",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• OpenSheetMusicDisplay (BSD-3-Clause)\n• Jetpack Compose (Apache-2.0)\n• Room Database (Apache-2.0)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
