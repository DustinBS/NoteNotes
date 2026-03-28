package com.notenotes.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.notenotes.ui.screens.LibraryScreen
import com.notenotes.ui.screens.PreviewScreen
import com.notenotes.ui.screens.RecordScreen
import com.notenotes.ui.screens.SettingsScreen
import com.notenotes.ui.screens.StatsScreen

private const val TAG = "NNNav"

/**
 * Navigation routes for NoteNotes.
 */
object Routes {
    const val RECORD = "record?rerecordIdeaId={rerecordIdeaId}"
    const val PREVIEW = "preview/{ideaId}"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val STATS = "stats"

    fun preview(ideaId: Long) = "preview/$ideaId"
    fun recordWithId(ideaId: Long) = "record?rerecordIdeaId=$ideaId"
}

@Composable
fun NoteNotesNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.RECORD,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(200)) },
        exitTransition = { fadeOut(animationSpec = tween(200)) },
        popEnterTransition = { fadeIn(animationSpec = tween(200)) },
        popExitTransition = { fadeOut(animationSpec = tween(200)) }
    ) {
        composable(
            route = Routes.RECORD,
            arguments = listOf(navArgument("rerecordIdeaId") {
                type = NavType.StringType
                nullable = true
            })
        ) { backStackEntry ->
            val rerecordId = backStackEntry.arguments?.getString("rerecordIdeaId")?.toLongOrNull()
            Log.d(TAG, "Navigated to RECORD screen, rerecordIdeaId=$rerecordId")
            RecordScreen(
                rerecordIdeaId = rerecordId,
                onNavigateToPreview = { ideaId ->
                    Log.i(TAG, "Navigating to PREVIEW for idea id=$ideaId")
                    navController.navigate(Routes.preview(ideaId))
                },
                onNavigateToLibrary = {
                    Log.d(TAG, "Navigating to LIBRARY")
                    navController.navigate(Routes.LIBRARY)
                },
                onNavigateToStats = {
                    Log.d(TAG, "Navigating to STATS")
                    navController.navigate(Routes.STATS)
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
                },
                onNavigateToSettings = {
                    Log.d(TAG, "Navigating to SETTINGS from PREVIEW")
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToRerecord = { id ->
                    Log.d(TAG, "Navigating to RERECORD for idea id=$id")
                    navController.navigate(Routes.recordWithId(id)) {
                        // Clear backstack to avoid deep nesting when going back and forth
                        popUpTo(Routes.RECORD) { inclusive = true }
                    }
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

        composable(Routes.STATS) {
            StatsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
