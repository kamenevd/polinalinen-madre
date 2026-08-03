package com.polinalinen.madre.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.MadreVoice
import com.polinalinen.madre.sourdough.currentPhase
import com.polinalinen.madre.sourdough.hoursSinceFeeding
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.screens.BakingCompleteScreen
import com.polinalinen.madre.ui.screens.BakingTimerScreen
import com.polinalinen.madre.ui.screens.BookStatsScreen
import com.polinalinen.madre.ui.screens.FeedingFormScreen
import com.polinalinen.madre.ui.screens.HomeScreen
import com.polinalinen.madre.ui.screens.RecipeDetailScreen
import com.polinalinen.madre.ui.screens.SettingsScreen
import com.polinalinen.madre.ui.screens.ShelfScreen
import com.polinalinen.madre.ui.screens.StarterDiaryScreen
import com.polinalinen.madre.ui.theme.CalmModeSetting
import com.polinalinen.madre.ui.theme.LocalCalmMode
import com.polinalinen.madre.ui.theme.SharedPreferencesFlagStore
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.SourdoughViewModel

/**
 * Home, RecipeDetail, BakingTimer, StarterDiary, Settings, Полка, BookStats,
 * Feeding — реальные («Живая книга»). Complete тоже реальный (2026-07-21), но
 * его визуальная композиция ещё не согласована с Димой/Полиной (мокап 6 в
 * DESIGN-V4.md отмечен как черновой) — данные настоящие.
 *
 * BakingViewModel и SourdoughViewModel шарятся между экранами через
 * activity-scoped viewModel(). Sourdough-состояние — реальное, из Room
 * (Cycle 3, 2026-07-21): SourdoughViewModel бутстрапит неявный User+Config
 * и держит config/history реактивными через Flow.
 */
@Composable
fun MadreNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val bakingViewModel: BakingViewModel = viewModel()
    val sourdoughViewModel: SourdoughViewModel = viewModel()
    val app = context.applicationContext as MadreApplication

    // Избранное + имя: SharedPreferences как в v3 (простое и рабочее, Room не нужен)
    val prefs = remember { context.getSharedPreferences("madre_prefs", Context.MODE_PRIVATE) }
    var favoriteIds by remember {
        mutableStateOf(prefs.getStringSet("favorite_recipes", emptySet())?.toSet() ?: emptySet())
    }
    val toggleFavorite: (String) -> Unit = { id ->
        val newSet = if (id in favoriteIds) favoriteIds - id else favoriteIds + id
        favoriteIds = newSet
        prefs.edit().putStringSet("favorite_recipes", newSet).apply()
    }
    var myName by remember { mutableStateOf(prefs.getString("my_name", "") ?: "") }
    val setMyName: (String) -> Unit = { name ->
        myName = name
        prefs.edit().putString("my_name", name).apply()
    }

    // «Спокойный режим» (Cycle 11) — там же, в madre_prefs. Значение раздаётся
    // вниз через LocalCalmMode: его читают и экраны, и модификаторы декораций
    // глубоко в чужих поддеревьях (Modifier.breathingPage).
    val calmModeSetting = remember { CalmModeSetting(SharedPreferencesFlagStore(prefs)) }
    var calmMode by remember { mutableStateOf(calmModeSetting.isCalm()) }
    val setCalmMode: (Boolean) -> Unit = { calm ->
        calmModeSetting.setCalm(calm)
        calmMode = calm
    }

    // Реальная история выпечек — питает Формуляр книги / хитмэп на Полке.
    val bakeRecords by app.bakeHistoryRepository.observeAll().collectAsState(initial = emptyList())
    val recipesForStats by bakingViewModel.recipes.collectAsState()

    // Реальное sourdough-состояние (Cycle 3): конфиг и история кормлений из Room,
    // через SourdoughViewModel — реактивно, без ручного refetch после кормления.
    val sourdoughConfig by sourdoughViewModel.config.collectAsState()
    val feedingHistory by sourdoughViewModel.history.collectAsState()
    val lastFeeding = feedingHistory.firstOrNull() // observeHistory сортирует DESC
    val profile = profileForInterval(sourdoughConfig?.intervalHours ?: 24)
    val phase = sourdoughConfig?.lastFeedingMillis?.let { currentPhase(hoursSinceFeeding(it), profile) }
        ?: GrowthPhase.EMPTY
    val headline = MadreVoice.headline(lastFeeding, profile)
    // "День N" — календарные дни с первого кормления в этом дневнике (не число записей).
    val dayNumber = feedingHistory.lastOrNull()?.let { oldest ->
        ((System.currentTimeMillis() - oldest.timestampMillis) / 86_400_000L).toInt() + 1
    } ?: 1

    CompositionLocalProvider(LocalCalmMode provides calmMode) {
        NavHost(navController = navController, startDestination = MadreDestinations.HOME) {
            composable(MadreDestinations.HOME) {
                HomeScreen(
                    madreHeadline = headline,
                    phase = phase,
                    favoriteIds = favoriteIds,
                    onToggleFavorite = toggleFavorite,
                    onOpenRecipe = { id -> navController.navigate(MadreDestinations.recipeDetail(id)) },
                    onOpenStarter = { navController.navigate(MadreDestinations.STARTER_DETAIL) },
                    onOpenTimer = { sessionId -> navController.navigate(MadreDestinations.bakingTimer(sessionId.toString())) },
                    onOpenFeeding = { navController.navigate(MadreDestinations.FEEDING_FORM) },
                    onOpenSettings = { navController.navigate(MadreDestinations.SETTINGS) },
                    onOpenShelf = { navController.navigate(MadreDestinations.SHELF) },
                    viewModel = bakingViewModel,
                )
            }
            composable(MadreDestinations.RECIPE_DETAIL) { backStackEntry ->
                val recipeId = backStackEntry.arguments?.getString("recipeId").orEmpty()
                RecipeDetailScreen(
                    recipeId = recipeId,
                    onBack = { navController.popBackStack() },
                    onStartBaking = { sessionId -> navController.navigate(MadreDestinations.bakingTimer(sessionId.toString())) },
                    viewModel = bakingViewModel,
                )
            }
            composable(MadreDestinations.BAKING_TIMER) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull() ?: return@composable
                BakingTimerScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack(MadreDestinations.HOME, inclusive = false) },
                    onComplete = {
                        // Cycle 12: «Готово» не кладётся поверх таймера, а
                        // сворачивает всю дорогу выпечки до первой полосы.
                        // Раньше «назад» со страницы «Готово» возвращал на
                        // таймер уже закрытой выпечки — то есть в никуда.
                        navController.navigate(MadreDestinations.bakingComplete(sessionId.toString())) {
                            popUpTo(MadreDestinations.HOME) { inclusive = false }
                        }
                    },
                    viewModel = bakingViewModel,
                )
            }
            composable(MadreDestinations.BAKING_COMPLETE) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull()
                BakingCompleteScreen(
                    sessionId = sessionId,
                    onHome = {
                        // Убирает только ЭТУ сессию — если печётся что-то ещё, оно продолжает идти.
                        sessionId?.let { bakingViewModel.exitSession(it) }
                        navController.popBackStack(MadreDestinations.HOME, inclusive = false)
                    },
                    viewModel = bakingViewModel,
                )
            }
            composable(MadreDestinations.STARTER_DETAIL) {
                val cancelledBakeCount by bakingViewModel.cancelledCount.collectAsState()
                StarterDiaryScreen(
                    dayNumber = dayNumber,
                    phase = phase,
                    profile = profile,
                    entries = MadreVoice.entriesFor(lastFeeding, profile),
                    history = feedingHistory,
                    onBack = { navController.popBackStack() },
                    onFeed = { navController.navigate(MadreDestinations.FEEDING_FORM) },
                    cancelledBakeCount = cancelledBakeCount,
                )
            }
            composable(MadreDestinations.FEEDING_FORM) {
                FeedingFormScreen(
                    onSave = { flourGrams, waterGrams, location, note, photoPath ->
                        sourdoughViewModel.feed(flourGrams, waterGrams, location, note, photoPath)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MadreDestinations.SETTINGS) {
                SettingsScreen(
                    myName = myName,
                    onMyNameChange = setMyName,
                    onBack = { navController.popBackStack() },
                    bakeCount = bakeRecords.size,
                    feedingCount = feedingHistory.size,
                    // Cycle 11: интервал и напоминания — настоящий конфиг из Room.
                    // Перепланирование уведомления делает SourdoughViewModel,
                    // реактивно от той же записи (см. rescheduleReminder).
                    intervalHours = sourdoughConfig?.intervalHours ?: 24,
                    onIntervalHoursChange = sourdoughViewModel::setIntervalHours,
                    remindersEnabled = sourdoughConfig?.remindersEnabled ?: true,
                    onRemindersEnabledChange = sourdoughViewModel::setRemindersEnabled,
                    calmMode = calmMode,
                    onCalmModeChange = setCalmMode,
                )
            }
            composable(MadreDestinations.SHELF) {
                ShelfScreen(
                    myName = myName,
                    onBack = { navController.popBackStack() },
                    onOpenMyBook = { navController.navigate(MadreDestinations.bookStats("me")) },
                )
            }
            composable(MadreDestinations.BOOK_STATS) {
                // Пока есть только "me" — книги друзей появятся здесь же, когда будет
                // от кого брать данные (нужен сервер, см. ShelfScreen).
                BookStatsScreen(
                    ownerLabel = myName.ifBlank { "вы" },
                    isMe = true,
                    recipes = recipesForStats,
                    records = bakeRecords,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
