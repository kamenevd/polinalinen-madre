package com.polinalinen.madre.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.service.TimerHelper
import com.polinalinen.madre.ui.theme.*

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimerHelper.createChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            LevitoMadreTheme {
                LevitoApp(
                    initialSessionId = intent?.getStringExtra("SESSION_ID"),
                    onConsumedIntent = { intent.removeExtra("SESSION_ID") }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

sealed class Screen {
    data object RecipeList : Screen()
    data class RecipeDetail(val recipe: Recipe) : Screen()
    data class Baking(val sessionId: String) : Screen()
    data object Completed : Screen()
}

@Composable
fun LevitoApp(
    viewModel: BakingViewModel = viewModel(),
    initialSessionId: String? = null,
    onConsumedIntent: () -> Unit = {}
) {
    val recipes by viewModel.recipes.collectAsState()
    val session by viewModel.session.collectAsState()
    val sessions by viewModel.activeSessions.collectAsState()
    val remainingSeconds by viewModel.remainingSeconds.collectAsState()
    val devMode by viewModel.devMode.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.RecipeList) }

    // Handle notification tap — navigate to the baking session
    LaunchedEffect(initialSessionId) {
        val sid = initialSessionId
        if (sid != null) {
            viewModel.resumeSession(sid)
            currentScreen = Screen.Baking(sid)
            onConsumedIntent()
        }
    }

    // Handle system back button — stay in app on inner screens
    BackHandler(enabled = currentScreen !is Screen.RecipeList) {
        when (currentScreen) {
            is Screen.Baking, is Screen.Completed -> {
                viewModel.exitSession()
                currentScreen = Screen.RecipeList
            }
            is Screen.RecipeDetail -> {
                currentScreen = Screen.RecipeList
            }
            else -> {}
        }
    }

    // Auto-navigate when session starts
    LaunchedEffect(session) {
        if (session != null && currentScreen is Screen.RecipeDetail) {
            currentScreen = Screen.Baking("")
        }
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
                Column(modifier = Modifier.fillMaxSize()) {
                    // Active sessions bar at top
                    if (sessions.isNotEmpty()) {
                        ActiveSessionsBar(
                            sessions = sessions,
                            onResume = { sid ->
                                viewModel.resumeSession(sid)
                                currentScreen = Screen.Baking(sid)
                            },
                            onRemove = { sid ->
                                viewModel.removeSession(sid)
                            }
                        )
                    }

                    RecipeListScreen(
                        recipes = recipes,
                        onRecipeClick = { recipe ->
                            currentScreen = Screen.RecipeDetail(recipe)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
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
                    },
                    devMode = devMode,
                    onToggleDevMode = { viewModel.toggleDevMode() },
                    onSkipStep = { viewModel.skipCurrentStep() }
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

@Composable
fun ActiveSessionsBar(
    sessions: List<ActiveSession>,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = BackgroundCard,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "🔥 Активные готовки",
                style = MaterialTheme.typography.labelLarge,
                color = AccentGold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { active ->
                    ActiveSessionChip(
                        session = active,
                        onClick = { onResume(active.id) },
                        onRemove = { onRemove(active.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveSessionChip(
    session: ActiveSession,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val step = session.session.currentStep
    val isWait = step.type == com.polinalinen.madre.model.StepType.WAIT

    Card(
        modifier = modifier
            .width(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isWait) BackgroundCardHover else BackgroundCard
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = session.currentStepTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Mini progress bar
                LinearProgressIndicator(
                    progress = session.session.progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = if (isWait) StatusWait else StatusAction,
                    trackColor = DividerColor
                )
            }

            // Close button
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Text("✕", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
