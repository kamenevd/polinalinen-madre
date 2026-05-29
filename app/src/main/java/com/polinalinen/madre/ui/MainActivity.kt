package com.polinalinen.madre.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.service.TimerHelper
import com.polinalinen.madre.ui.theme.LevitoMadreTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create notification channel early
        TimerHelper.createChannel(this)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            LevitoMadreTheme {
                LevitoApp()
            }
        }
    }
}

sealed class Screen {
    data object RecipeList : Screen()
    data class RecipeDetail(val recipe: Recipe) : Screen()
    data class Baking(val session: BakingSession) : Screen()
    data object Completed : Screen()
}

@Composable
fun LevitoApp(
    viewModel: BakingViewModel = viewModel()
) {
    val recipes by viewModel.recipes.collectAsState()
    val session by viewModel.session.collectAsState()
    val remainingSeconds by viewModel.remainingSeconds.collectAsState()
    val error by viewModel.error.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.RecipeList) }

    // Auto-navigate when session starts
    LaunchedEffect(session) {
        if (session != null && currentScreen is Screen.RecipeDetail) {
            currentScreen = Screen.Baking(session!!)
        }
    }

    // Show error if any
    if (error != null) {
        androidx.compose.material3.Text(
            text = error ?: "",
            color = androidx.compose.ui.graphics.Color.Red
        )
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        },
        label = "screenTransition"
    ) { screen ->
        when (screen) {
            is Screen.RecipeList -> {
                RecipeListScreen(
                    recipes = recipes,
                    onRecipeClick = { recipe ->
                        currentScreen = Screen.RecipeDetail(recipe)
                    }
                )
            }

            is Screen.RecipeDetail -> {
                RecipeDetailScreen(
                    recipe = screen.recipe,
                    onStartBaking = {
                        viewModel.selectRecipe(screen.recipe)
                    },
                    onBack = {
                        currentScreen = Screen.RecipeList
                    }
                )
            }

            is Screen.Baking -> {
                val currentSession = session ?: return@AnimatedContent
                BakingTimelineScreen(
                    session = currentSession,
                    remainingSeconds = remainingSeconds,
                    onAdvance = {
                        viewModel.advanceStep()
                        if (currentSession.isLastStep) {
                            currentScreen = Screen.Completed
                        }
                    },
                    onTogglePause = {
                        viewModel.togglePause()
                    },
                    onBack = {
                        viewModel.exitSession()
                        currentScreen = Screen.RecipeList
                    }
                )
            }

            is Screen.Completed -> {
                CompletedScreen(
                    onHome = {
                        viewModel.exitSession()
                        currentScreen = Screen.RecipeList
                    }
                )
            }
        }
    }
}
