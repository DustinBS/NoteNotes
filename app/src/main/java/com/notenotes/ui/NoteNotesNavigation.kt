package com.notenotes.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.notenotes.ui.screens.LibraryScreen
import com.notenotes.ui.screens.PreviewScreen
import com.notenotes.ui.screens.RecordScreen
import com.notenotes.ui.screens.SettingsScreen

private const val TAG = "NNNav"

/**
 * Navigation routes for NoteNotes.
 */
object Routes {
    const val RECORD = "record"
    const val PREVIEW = "preview/{ideaId}"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    fun preview(ideaId: Long) = "preview/$ideaId"
}

@Composable
fun NoteNotesNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.RECORD,
        modifier = modifier
    ) {
        composable(Routes.RECORD) {
            Log.d(TAG, "Navigated to RECORD screen")
            RecordScreen(
                onNavigateToPreview = { ideaId ->
                    Log.i(TAG, "Navigating to PREVIEW for idea id=$ideaId")
                    navController.navigate(Routes.preview(ideaId))
                },
                onNavigateToLibrary = {
                    Log.d(TAG, "Navigating to LIBRARY")
                    navController.navigate(Routes.LIBRARY)
                },
                onNavigateToSettings = {
                    Log.d(TAG, "Navigating to SETTINGS")
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.PREVIEW,
            arguments = listOf(navArgument("ideaId") { type = NavType.LongType })
        ) { backStackEntry ->
            val ideaId = backStackEntry.arguments?.getLong("ideaId") ?: return@composable
            Log.d(TAG, "Navigated to PREVIEW screen, ideaId=$ideaId")
            PreviewScreen(
                ideaId = ideaId,
                onNavigateBack = {
                    Log.d(TAG, "Navigating back from PREVIEW")
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.LIBRARY) {
            Log.d(TAG, "Navigated to LIBRARY screen")
            LibraryScreen(
                onNavigateToPreview = { ideaId ->
                    Log.i(TAG, "Library → PREVIEW for idea id=$ideaId")
                    navController.navigate(Routes.preview(ideaId))
                },
                onNavigateBack = {
                    Log.d(TAG, "Navigating back from LIBRARY")
                    navController.popBackStack()
                },
                onNavigateToRecord = {
                    Log.d(TAG, "Navigating to RECORD from Library")
                    navController.navigate(Routes.RECORD) {
                        popUpTo(Routes.RECORD) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
