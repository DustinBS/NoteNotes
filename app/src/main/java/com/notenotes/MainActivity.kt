package com.notenotes

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.notenotes.ui.NoteNotesNavHost
import com.notenotes.ui.theme.NoteNotesTheme

private const val TAG = "NoteNotes"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "=== NoteNotes App Started ===")
        Log.i(TAG, "Device: ${android.os.Build.MODEL}, API: ${android.os.Build.VERSION.SDK_INT}")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        setContent {
            NoteNotesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NoteNotesNavHost(navController = navController)
                }
            }
        }
    }
}
