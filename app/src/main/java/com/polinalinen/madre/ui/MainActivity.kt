package com.polinalinen.madre.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.LevitoMadreTheme
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.R
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.service.TimerHelper

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Mutable state for notification deep-links so Compose recomposes
    private var _notifSessionId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TimerHelper.createChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Handle initial intent (cold start from notification)
        _notifSessionId.value = intent?.getStringExtra("SESSION_ID")

        setContent {
            val prefs = remember { getSharedPreferences("levito_prefs", Context.MODE_PRIVATE) }
            val isDarkTheme = remember { mutableStateOf(prefs.getBoolean("is_dark_theme", true)) }

            LevitoMadreTheme(isDarkTheme = isDarkTheme.value) {
                // Read from MutableState so Compose observes changes
                val sessionId by remember { _notifSessionId }
                LevitoApp(
                    initialSessionId = sessionId,
                    isDarkTheme = isDarkTheme.value,
                    onToggleTheme = {
                        isDarkTheme.value = !isDarkTheme.value
                        prefs.edit().putBoolean("is_dark_theme", isDarkTheme.value).apply()
                    },
                    onConsumedIntent = {
                        _notifSessionId.value = null
                        intent.removeExtra("SESSION_ID")
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Trigger recomposition for notification taps while activity is alive
        _notifSessionId.value = intent.getStringExtra("SESSION_ID")
    }
}

sealed class Screen {
    data object RecipeList : Screen()
    data class RecipeDetail(val recipe: Recipe) : Screen()
    data class Baking(val sessionId: String) : Screen()
    data object Completed : Screen()
    data object Diagnostics : Screen()
}

@Composable
fun LevitoApp(
    viewModel: BakingViewModel = viewModel(),
    initialSessionId: String? = null,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    onConsumedIntent: () -> Unit = {}
) {
    val recipes by viewModel.recipes.collectAsState()
    val session by viewModel.session.collectAsState()
    val sessions by viewModel.activeSessions.collectAsState()
    val remainingSeconds by viewModel.remainingSeconds.collectAsState()
    val devMode by viewModel.devMode.collectAsState()
    val restoredCount by viewModel.restoredSessionCount.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.RecipeList) }

    // Snackbar state for crash recovery notification
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show snackbar when sessions are restored from crash
    LaunchedEffect(restoredCount) {
        if (restoredCount > 0) {
            val msg = "\u2615 Ваша готовка восстановлена!"
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = msg,
                    duration = SnackbarDuration.Long
                )
            }
            viewModel.consumeRestoredCount()
        }
    }

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
            is Screen.Diagnostics -> {
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

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    ) { padding ->
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        },
        label = "screenTransition",
        modifier = Modifier.padding(padding)
    ) { screen ->
        // Force recomposition when theme changes without re-triggering transition
        key(isDarkTheme) {
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
                        onDiagnostics = { currentScreen = Screen.Diagnostics },
                        onToggleTheme = onToggleTheme,
                        isDarkTheme = isDarkTheme,
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
                val completedRecipe = session?.recipe
                CompletedScreen(
                    onHome = {
                        viewModel.exitSession()
                        currentScreen = Screen.RecipeList
                    },
                    recipeEmoji = completedRecipe?.emoji ?: "🍞",
                    recipeName = completedRecipe?.name ?: "",
                    recipeId = completedRecipe?.id ?: ""
                )
            }

            is Screen.Diagnostics -> {
                DiagnosticsScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.RecipeList }
                )
            }
        }
        } // key(isDarkTheme)
    }
    } // Scaffold
}

@Composable
fun ActiveSessionsBar(
    sessions: List<ActiveSession>,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "🔥 Активные готовки",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.accentGold,
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

    // Format remaining time
    val remainingText = if (session.remainingSeconds > 0 && isWait) {
        val mins = session.remainingSeconds / 60
        val secs = session.remainingSeconds % 60
        when {
            mins > 0 && secs > 0 -> "${mins}:${String.format("%02d", secs)}"
            mins > 0 -> "${mins}мин"
            else -> "${secs}с"
        }
    } else null

    Card(
        modifier = modifier
            .width(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isWait) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
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
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = session.currentStepTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Remaining time
                if (remainingText != null) {
                    Text(
                        text = "⏱ $remainingText",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (session.remainingSeconds <= 60) AppColors.timerUrgent else AppColors.accentGold,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // Mini progress bar
                LinearProgressIndicator(
                    progress = session.session.progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = if (isWait) AppColors.statusWait else AppColors.statusAction,
                    trackColor = AppColors.dividerColor
                )
            }

            // Close button
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    viewModel: BakingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val diagnostics = remember { viewModel.getDiagnostics(context) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .statusBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "\u2699\uFE0F Диагностика",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.accentGold
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(diagnostics.entries.toList()) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = entry.key,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = entry.value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        }
    }
}
