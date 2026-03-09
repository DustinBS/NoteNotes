package com.notenotes.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notenotes.model.InstrumentProfile

private const val PREFS_NAME = "notenotes_settings"
private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
const val KEY_SAVE_PATH_URI = "save_path_uri"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var selectedInstrument by remember { mutableStateOf(InstrumentProfile.GUITAR) }
    var defaultTempo by remember { mutableStateOf("120") }
    var autoTranscribe by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true)) }

    // Save path — human-readable label derived from stored URI
    var savePathUri by remember { mutableStateOf(prefs.getString(KEY_SAVE_PATH_URI, null)) }

    val savePathLabel = remember(savePathUri) {
        if (savePathUri == null) {
            "Downloads/NoteNotes (default)"
        } else {
            try {
                val uri = Uri.parse(savePathUri)
                // Decode tree URI to readable path
                val treeDoc = uri.lastPathSegment ?: ""
                val readable = treeDoc
                    .replace("primary:", "Internal/")
                    .replace(":", "/")
                readable.ifEmpty { savePathUri ?: "" }
            } catch (_: Exception) {
                savePathUri ?: ""
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist access permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val uriStr = uri.toString()
            prefs.edit().putString(KEY_SAVE_PATH_URI, uriStr).apply()
            savePathUri = uriStr
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Settings and Info") },
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
                .verticalScroll(rememberScrollState())
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

            // Auto-transcribe toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-transcribe", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Automatically detect notes after recording",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoTranscribe,
                    onCheckedChange = {
                        autoTranscribe = it
                        prefs.edit().putBoolean(KEY_AUTO_TRANSCRIBE, it).apply()
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Storage ──
            Text(
                text = "Storage",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Save-to-device folder",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Files are saved into an idea-named subfolder within this path.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = savePathLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = { folderPicker.launch(null) }) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Change")
                }
            }

            if (savePathUri != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        prefs.edit().remove(KEY_SAVE_PATH_URI).apply()
                        savePathUri = null
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Reset to default", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "NoteNotes v1.0",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Auto-transcribing voice memo app for musicians.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Good to Know",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• MusicXML transcriptions are approximate — the format does not represent nonstandard time intervals precisely.\n" +
                       "• .nnt is a NoteNotes file type that stores full-fidelity transcription data separate from the audio.\n" +
                       "• Use the Zip snapshot to migrate ideas between devices in bulk.",
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
