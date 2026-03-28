package com.notenotes.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.notenotes.data.AppDatabase
import com.notenotes.model.InstrumentProfile
import com.notenotes.model.MusicalNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "notenotes_settings"
private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
const val KEY_WAVEFORM_WINDOW_SIZE = "waveform_window_size"
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
    var waveformWindowSize by remember { mutableStateOf(prefs.getFloat(KEY_WAVEFORM_WINDOW_SIZE, 5f)) }
    
    // Save path - human-readable label derived from stored URI
    var savePathUri by remember { mutableStateOf(prefs.getString(KEY_SAVE_PATH_URI, null)) }
    val savePathLabel = remember(savePathUri) {
        if (savePathUri == null) {
            "Downloads/NoteNotes (default)"
        } else {
            try {
                val uri = android.net.Uri.parse(savePathUri)
                val treeDoc = uri.lastPathSegment ?: ""
                val readable = treeDoc.substringAfter("primary:", treeDoc.substringAfter(":", treeDoc))
                "SD Card/$readable"
            } catch (e: Exception) {
                "Custom Path (Unknown)"
            }
        }
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val uriStr = uri.toString()
            prefs.edit().putString(KEY_SAVE_PATH_URI, uriStr).apply()
            savePathUri = uriStr
        }
    }

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
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
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

            Spacer(modifier = Modifier.height(24.dp))
            
            // Visualization Settings
            Text(
                text = "Visualization Settings",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Column(modifier = Modifier.fillMaxWidth()) {
                var windowSizeText by remember(waveformWindowSize) { mutableStateOf(String.format(java.util.Locale.US, "%.1f", waveformWindowSize)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Waveform Window (sec): ",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = windowSizeText,
                        onValueChange = { newValue ->
                            windowSizeText = newValue
                        },
                        modifier = Modifier
                            .width(100.dp)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val validFloat = windowSizeText.toFloatOrNull() ?: waveformWindowSize
                                    val finalValue = maxOf(1f, validFloat)
                                    if (waveformWindowSize != finalValue) {
                                        waveformWindowSize = finalValue
                                        prefs.edit().putFloat(KEY_WAVEFORM_WINDOW_SIZE, finalValue).apply()
                                    }
                                    windowSizeText = String.format(java.util.Locale.US, "%.1f", finalValue)
                                }
                            },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        singleLine = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("5s", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = waveformWindowSize.coerceIn(5f, 30f),
                        onValueChange = { newValue ->
                            val snapped = kotlin.math.round(newValue)
                            waveformWindowSize = snapped
                            windowSizeText = String.format(java.util.Locale.US, "%.1f", snapped)
                        },
                        onValueChangeFinished = {
                            prefs.edit().putFloat(KEY_WAVEFORM_WINDOW_SIZE, waveformWindowSize).apply()
                        },
                        valueRange = 5f..30f,
                        steps = 24,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("30s", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = "Adjust the duration of audio visible on screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Choose "Save to Device" folder
            Text(
                text = "Export Destination",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Save files to:", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        savePathLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { launcher.launch(null) }) {
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
        }
    }
}
