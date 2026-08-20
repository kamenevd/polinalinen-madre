package com.polinalinen.madre.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import com.polinalinen.madre.sourdough.currentPhase
import com.polinalinen.madre.sourdough.hoursSinceFeeding
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.ui.screens.BakingCompleteScreen
import com.polinalinen.madre.ui.screens.BakingTimerScreen
import com.polinalinen.madre.ui.screens.BookStatsScreen
import com.polinalinen.madre.ui.screens.FeedingFormScreen
import com.polinalinen.madre.ui.screens.GalleryPhoto
import com.polinalinen.madre.ui.screens.HomeScreen
import com.polinalinen.madre.ui.screens.PhotoGalleryScreen
import com.polinalinen.madre.ui.screens.RecipeDetailScreen
import com.polinalinen.madre.ui.screens.SettingsScreen
import com.polinalinen.madre.ui.screens.SettingsShelfScreen
import com.polinalinen.madre.ui.screens.ShelfScreen
import com.polinalinen.madre.ui.screens.StarterDiaryScreen
import com.polinalinen.madre.shelf.FamilyShelf
import com.polinalinen.madre.viewmodel.ShelfViewModel
import com.polinalinen.madre.ui.theme.CalmModeSetting
import com.polinalinen.madre.ui.theme.LocalCalmMode
import com.polinalinen.madre.ui.theme.SharedPreferencesFlagStore
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.FeedingSaveState
import com.polinalinen.madre.viewmodel.FamilyBookViewModel
import com.polinalinen.madre.viewmodel.SourdoughViewModel
import kotlinx.coroutines.flow.MutableStateFlow

internal fun authoritativeFeedingFromHistory(history: List<FeedingEntity>): FeedingEntity? = history.firstOrNull()

internal fun latestComputedHydrationFromHistory(history: List<FeedingEntity>): Int? =
    history.firstNotNullOfOrNull { it.finalHydrationPercent }

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
fun MadreNavHost(
    navController: NavHostController = rememberNavController(),
    pendingSessionId: MutableStateFlow<Long?>? = null,
    pendingFeeding: MutableStateFlow<Boolean>? = null,
) {
    val context = LocalContext.current
    val bakingViewModel: BakingViewModel = viewModel()
    val sourdoughViewModel: SourdoughViewModel = viewModel()
    val shelfViewModel: ShelfViewModel = viewModel()
    val familyBookViewModel: FamilyBookViewModel = viewModel()
    val app = context.applicationContext as MadreApplication

    val enterFeedingForm = {
        sourdoughViewModel.clearSaveState()
        navController.navigate(MadreDestinations.FEEDING_FORM) {
            popUpTo(MadreDestinations.HOME) { inclusive = false }
        }
    }

    // Cycle 12: тап по строке хода выпечки в шторке открывает ИМЕННО ту
    // выпечку. Намерение приходит из MainActivity и ждёт здесь, пока NavHost
    // готов; после исполнения гасится, чтобы поворот экрана не увёл человека
    // на таймер второй раз.
    if (pendingSessionId != null) {
        val requested by pendingSessionId.collectAsState()
        LaunchedEffect(requested) {
            val id = requested ?: return@LaunchedEffect
            pendingSessionId.value = null
            navController.navigate(MadreDestinations.bakingTimer(id.toString())) {
                popUpTo(MadreDestinations.HOME) { inclusive = false }
            }
        }
    }

    // Cycle 18: кнопка «Покормить» из шторки открывает форму кормления. Тот же
    // приём, что и с выпечкой: намерение ждёт, пока NavHost готов, и гасится
    // сразу после исполнения, чтобы поворот экрана не открыл форму дважды.
    if (pendingFeeding != null) {
        val requested by pendingFeeding.collectAsState()
        LaunchedEffect(requested) {
            if (!requested) return@LaunchedEffect
            pendingFeeding.value = false
            enterFeedingForm()
        }
    }

    // Избранное + имя: SharedPreferences как в v3 (простое и рабочее, Room не нужен)
    val prefs = remember { context.getSharedPreferences("madre_prefs", Context.MODE_PRIVATE) }
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
    val familyBookState by familyBookViewModel.state.collectAsState()

    // Реальное sourdough-состояние (Cycle 3): конфиг и история кормлений из Room,
    // через SourdoughViewModel — реактивно, без ручного refetch после кормления.
    val sourdoughConfig by sourdoughViewModel.config.collectAsState()
    val feedingHistory by sourdoughViewModel.history.collectAsState()
    val saveState by sourdoughViewModel.saveState.collectAsState()
    // Cycle 14: имя закваски одно на всю книгу и живёт в Room. Отсюда оно
    // расходится в дневник, на первую полосу, в подписи фотокарточек кормлений
    // и (через SourdoughViewModel.rescheduleReminder) в напоминание.
    val starterName = StarterName.sanitize(sourdoughConfig?.name.orEmpty())
    val galleryPhotos = remember(feedingHistory, bakeRecords, starterName) {
        val all = mutableListOf<GalleryPhoto>()
        feedingHistory.forEach { feeding ->
            feeding.photoPath?.takeIf { it.isNotBlank() }?.let { path ->
                all += GalleryPhoto(
                    id = "feeding-${feeding.id}",
                    path = path,
                    timestampMillis = feeding.timestampMillis,
                    caption = StarterName.feedingPhotoCaption(starterName),
                )
            }
        }
        bakeRecords.forEach { bake ->
            bake.photoPath?.takeIf { it.isNotBlank() }?.let { path ->
                all += GalleryPhoto(
                    id = "bake-${bake.id}",
                    path = path,
                    timestampMillis = bake.completedAtMillis,
                    caption = bake.recipeName,
                )
            }
        }
        all.sortedByDescending { it.timestampMillis }
    }
    val latestHistoryFeeding = authoritativeFeedingFromHistory(feedingHistory)
    // Cycle 26: решения для последнего кормления опираются только на фактически
    // последнюю вставку (id DESC), а не на timestamp, чтобы rollback не ломал
    // факт и не заставлял UI возвращаться к старому порядку.
    val authoritativeLastFeedingMillis = latestHistoryFeeding?.timestampMillis
        ?: sourdoughConfig?.lastFeedingMillis

    // Cycle 26: гидратация книги — самая свежая ПОСЧИТАННАЯ; если таких ещё
    // нет, показываем «—» в суммарном блоке и не используем это как источник
    // для следующего расчёта.
    val latestFinalHydration = latestComputedHydrationFromHistory(feedingHistory)
    val shownHydration = latestFinalHydration
    val profile = profileForInterval(sourdoughConfig?.intervalHours ?: 24)
    val phase = authoritativeLastFeedingMillis?.let { currentPhase(hoursSinceFeeding(it), profile) }
        ?: GrowthPhase.EMPTY
    // "День N" — календарные дни с первого кормления в этом дневнике (не число записей).
    // Первый кормеж — по фактическому timestamp, а не по тому, как rows
    // встали в таблице.
    val dayNumber = feedingHistory.minByOrNull { it.timestampMillis }?.let { oldest ->
        ((System.currentTimeMillis() - oldest.timestampMillis) / 86_400_000L).toInt() + 1
    } ?: 1

    CompositionLocalProvider(LocalCalmMode provides calmMode) {
        NavHost(navController = navController, startDestination = MadreDestinations.HOME) {
            composable(MadreDestinations.HOME) {
                HomeScreen(
                    starterName = starterName,
                    phase = phase,
                    lastFeedingMillis = authoritativeLastFeedingMillis,
                    intervalHours = sourdoughConfig?.intervalHours ?: 24,
                    latestHydrationPercent = shownHydration,
                    onOpenRecipe = { id -> navController.navigate(MadreDestinations.recipeDetail(id)) },
                    onOpenStarter = { navController.navigate(MadreDestinations.STARTER_DETAIL) },
                    onOpenTimer = { sessionId -> navController.navigate(MadreDestinations.bakingTimer(sessionId.toString())) },
                    onOpenFeeding = enterFeedingForm,
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
                    // Generated biology is not an observation. Only saved user
                    // observations appear in the factual feeding list below.
                    entries = emptyList(),
                    history = feedingHistory,
                    onBack = { navController.popBackStack() },
                    onFeed = enterFeedingForm,
                    onOpenGallery = { navController.navigate(MadreDestinations.PHOTO_GALLERY) },
                    cancelledBakeCount = cancelledBakeCount,
                    starterName = starterName,
                    // Дневник и первая полоса считают срок одной функцией от
                    // одного и того же интервала из настроек.
                    intervalHours = sourdoughConfig?.intervalHours ?: 24,
                )
            }
            composable(MadreDestinations.PHOTO_GALLERY) {
                PhotoGalleryScreen(
                    photos = galleryPhotos,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MadreDestinations.FEEDING_FORM) {
                FeedingFormScreen(
                    onSave = { starterGrams, flourGrams, waterGrams, location, note, photoPath ->
                        sourdoughViewModel.feed(starterGrams, flourGrams, waterGrams, location, note, photoPath)
                    },
                    onBack = { navController.popBackStack() },
                    saveState = saveState,
                    // Гидратация, от которой считается это кормление: самая
                    // свежая посчитанная. null — их ещё нет, и форма вслух
                    // назовёт стартовую 50% из буклета.
                    priorHydrationPercent = latestFinalHydration,
                )
                LaunchedEffect(saveState) {
                    when (saveState) {
                        is FeedingSaveState.Success -> {
                            navController.popBackStack()
                            sourdoughViewModel.consumeSaveState()
                        }
                        else -> {}
                    }
                }
            }
            composable(MadreDestinations.SETTINGS) {
                SettingsScreen(
                    myName = myName,
                    onMyNameChange = setMyName,
                    onBack = { navController.popBackStack() },
                    starterName = starterName,
                    onStarterNameChange = sourdoughViewModel::setStarterName,
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
                    onOpenShelfSettings = { navController.navigate(MadreDestinations.SETTINGS_SHELF) },
                    familyBookViewModel = familyBookViewModel,
                )
            }
            composable(MadreDestinations.SETTINGS_SHELF) {
                SettingsShelfScreen(
                    myName = myName,
                    localRecords = bakeRecords,
                    onBack = { navController.popBackStack() },
                    familyBookViewModel = familyBookViewModel,
                    shelfViewModel = shelfViewModel,
                )
            }
            composable(MadreDestinations.SHELF) {
                ShelfScreen(
                    myName = myName,
                    localRecords = bakeRecords,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { ownerId -> navController.navigate(MadreDestinations.bookStats(ownerId)) },
                    account = familyBookState.account,
                    shelfViewModel = shelfViewModel,
                )
            }
            composable(MadreDestinations.BOOK_STATS) { backStackEntry ->
                val ownerId = backStackEntry.arguments?.getString("ownerId").orEmpty()
                val myUserId by shelfViewModel.myUserId.collectAsState()
                val own = FamilyShelf.isOwnBook(ownerId, myUserId)
                BookStatsScreen(
                    ownerLabel = if (own) {
                        myName.ifBlank { "вы" }
                    } else {
                        shelfViewModel.labelFor(ownerId, "книга")
                    },
                    isMe = own,
                    recipes = recipesForStats,
                    records = if (own) bakeRecords else shelfViewModel.recordsFor(ownerId),
                    feedingMillis = if (own) {
                        remember(feedingHistory) { feedingHistory.map { it.timestampMillis } }
                    } else {
                        emptyList()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
