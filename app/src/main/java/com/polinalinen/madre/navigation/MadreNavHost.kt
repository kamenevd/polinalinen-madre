package com.polinalinen.madre.navigation

import android.content.Context
import androidx.compose.runtime.Composable
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
import com.polinalinen.madre.sourdough.hoursSinceFeeding
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.screens.BakingCompleteScreenPlaceholder
import com.polinalinen.madre.ui.screens.BakingTimerScreen
import com.polinalinen.madre.ui.screens.BookStatsScreen
import com.polinalinen.madre.ui.screens.FeedingFormScreenPlaceholder
import com.polinalinen.madre.ui.screens.HomeScreen
import com.polinalinen.madre.ui.screens.NotificationsScreenPlaceholder
import com.polinalinen.madre.ui.screens.RecipeDetailScreen
import com.polinalinen.madre.ui.screens.SettingsScreen
import com.polinalinen.madre.ui.screens.ShelfScreen
import com.polinalinen.madre.ui.screens.StarterDiaryScreen
import com.polinalinen.madre.viewmodel.BakingViewModel

/**
 * Cycle 1: Home, RecipeDetail, BakingTimer, StarterDiary, Settings, Полка,
 * BookStats — реальные («Живая книга»). Feeding/Complete/Notifications —
 * плейсхолдеры до согласования мокапов.
 *
 * BakingViewModel шарится между экранами через activity-scoped viewModel().
 * Sourdough-состояние здесь пока временное (без Room-подписки) — подключается
 * в Cycle 3 через SourdoughRepository.
 */
@Composable
fun MadreNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val bakingViewModel: BakingViewModel = viewModel()
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

    // Реальная история выпечек — питает Формуляр книги / хитмэп на Полке.
    val bakeRecords by app.bakeHistoryRepository.observeAll().collectAsState(initial = emptyList())
    val recipesForStats by bakingViewModel.recipes.collectAsState()

    // Временный sourdough-стейт до Cycle 3 (нет ещё формы кормления):
    val profile = remember { profileForInterval(24) }
    val phase = GrowthPhase.EMPTY
    val headline = MadreVoice.headline(null, profile)

    NavHost(navController = navController, startDestination = MadreDestinations.HOME) {
        composable(MadreDestinations.HOME) {
            HomeScreen(
                madreHeadline = headline,
                favoriteIds = favoriteIds,
                onToggleFavorite = toggleFavorite,
                onOpenRecipe = { id -> navController.navigate(MadreDestinations.recipeDetail(id)) },
                onOpenStarter = { navController.navigate(MadreDestinations.STARTER_DETAIL) },
                onOpenTimer = { sessionId -> navController.navigate(MadreDestinations.bakingTimer(sessionId.toString())) },
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
                onComplete = { navController.navigate(MadreDestinations.bakingComplete(sessionId.toString())) },
                viewModel = bakingViewModel,
            )
        }
        composable(MadreDestinations.BAKING_COMPLETE) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull()
            BakingCompleteScreenPlaceholder(
                onHome = {
                    // Убирает только ЭТУ сессию — если печётся что-то ещё, оно продолжает идти.
                    sessionId?.let { bakingViewModel.exitSession(it) }
                    navController.popBackStack(MadreDestinations.HOME, inclusive = false)
                },
            )
        }
        composable(MadreDestinations.STARTER_DETAIL) {
            StarterDiaryScreen(
                dayNumber = 1,
                phase = phase,
                entries = MadreVoice.entriesFor(null, profile),
                history = emptyList(),
                onBack = { navController.popBackStack() },
                onFeed = { navController.navigate(MadreDestinations.FEEDING_FORM) },
            )
        }
        composable(MadreDestinations.FEEDING_FORM) {
            FeedingFormScreenPlaceholder(onSaved = { navController.popBackStack() })
        }
        composable(MadreDestinations.NOTIFICATIONS) {
            NotificationsScreenPlaceholder(onBack = { navController.popBackStack() })
        }
        composable(MadreDestinations.SETTINGS) {
            SettingsScreen(
                myName = myName,
                onMyNameChange = setMyName,
                onBack = { navController.popBackStack() },
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
