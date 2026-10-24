package com.polinalinen.madre.navigation

import com.polinalinen.madre.ui.kit.*
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.currentPhase
import com.polinalinen.madre.sourdough.hoursSinceFeeding
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.ui.screens.BakingFinale
import com.polinalinen.madre.ui.screens.BookStatsScreen
import com.polinalinen.madre.ui.screens.FeedingFormScreen
import com.polinalinen.madre.ui.screens.GalleryPhoto
import com.polinalinen.madre.ui.screens.PhotoGalleryScreen
import com.polinalinen.madre.ui.screens.RecipeDetailScreen
import com.polinalinen.madre.ui.screens.SettingsScreen
import com.polinalinen.madre.ui.screens.SettingsShelfScreen
import com.polinalinen.madre.ui.screens.ShelfScreen
import com.polinalinen.madre.ui.screens.StarterDiaryScreen
import com.polinalinen.madre.shelf.FamilyShelf
import com.polinalinen.madre.viewmodel.ShelfViewModel
import com.polinalinen.madre.ui.kit.CalmModeSetting
import com.polinalinen.madre.ui.kit.LocalCalmMode
import com.polinalinen.madre.ui.kit.SharedPreferencesFlagStore
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.FeedingSaveState
import com.polinalinen.madre.viewmodel.FamilyBookViewModel
import com.polinalinen.madre.viewmodel.SourdoughViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import com.polinalinen.madre.ui.razvorot.*

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
    pendingPlanned: MutableStateFlow<com.polinalinen.madre.notifications.PlannedBakeRequest?>? = null,
) {
    val context = LocalContext.current
    val readingPreferences = rememberReadingPreferences()
    val bakingViewModel: BakingViewModel = viewModel()
    val bookPreferences = remember(context) { BookPreferences(context) }
    val initialHomeSection = remember { bookPreferences.initialTab() }
    var activeSection by rememberSaveable { mutableStateOf(initialHomeSection) }
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry) {
        activeSection=when(currentEntry?.destination?.route) {
            MadreDestinations.CHAPTER->0
            MadreDestinations.BAKE->1
            MadreDestinations.STARTER_DETAIL,MadreDestinations.FEEDING_FORM,MadreDestinations.PHOTO_GALLERY->2
            MadreDestinations.SHELF,MadreDestinations.BOOK_STATS,MadreDestinations.SETTINGS_SHELF->3
            MadreDestinations.HOME->currentEntry?.savedStateHandle?.get<Int>("home_section")?:initialHomeSection
            else->activeSection
        }
    }
    val navSnackbar = remember { PaperSnackbarState() }
    val restoreReady by bakingViewModel.restoreReady.collectAsState()
    var startupHandled by rememberSaveable { mutableStateOf(false) }
    var startRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var startMode by rememberSaveable { mutableStateOf(0) }
    var startFromOven by rememberSaveable { mutableStateOf(true) }
    var editingPlanId by rememberSaveable { mutableStateOf<String?>(null) }
    var planSaving by remember { mutableStateOf(false) }
    var planError by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bookPreferences) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val route = navController.currentDestination?.route
                val section = when(route) {
                    MadreDestinations.CHAPTER->0
                    MadreDestinations.BAKE->1
                    MadreDestinations.SHELF,MadreDestinations.BOOK_STATS,MadreDestinations.SETTINGS_SHELF->3
                    MadreDestinations.STARTER_DETAIL,MadreDestinations.FEEDING_FORM,MadreDestinations.PHOTO_GALLERY->2
                    else->activeSection
                }
                bookPreferences.visit(section)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer);bookPreferences.close() }
    }
    fun showHome(section:Int) {
        activeSection=section
        runCatching { navController.getBackStackEntry(MadreDestinations.HOME).savedStateHandle["home_section"]=section }
        navController.popBackStack(MadreDestinations.HOME,inclusive=false)
        bookPreferences.visit(section)
    }
    fun openChapter(id:String,page:Int=-1,step:Int=-1) {
        navController.getBackStackEntry(MadreDestinations.HOME).savedStateHandle["home_section"]=0
        val current=navController.currentBackStackEntry
        if(current?.destination?.route==MadreDestinations.CHAPTER &&
            current.arguments?.getString("recipeId")==id && page<0 && step<0)return
        navController.navigate(MadreDestinations.chapter(id,page=page,step=step)) {
            popUpTo(MadreDestinations.HOME){inclusive=false}
            launchSingleTop=true
        }
    }
    val sourdoughViewModel: SourdoughViewModel = viewModel()
    val shelfViewModel: ShelfViewModel = viewModel()
    val familyBookViewModel: FamilyBookViewModel = viewModel()
    val app = context.applicationContext as MadreApplication
    val displaySettings by app.bookDisplaySettings.collectAsState()
    val navigationScope = rememberCoroutineScope()
    var aliceSheet by remember { mutableStateOf(false) }
    val planScheduler=remember(context){com.polinalinen.madre.notifications.PlannedBakeScheduler(context.applicationContext)}
    fun openStart(id:String, mode:Int=0, planId:String?=null,fromOven:Boolean=true) {
        startMode=mode;editingPlanId=planId;planError=null;startRecipeId=id;startFromOven=fromOven
    }
    suspend fun storePlan(plan:PlannedBake,replacingId:String?=null) = withContext(NonCancellable+Dispatchers.IO) {
        check(plan.startAtMillis>=System.currentTimeMillis()) { "Start time has passed" }
        check(bookPreferences.putPlan(plan,replacingId)) { "Plan was not persisted" }
        if(replacingId!=null && replacingId!=plan.id)planScheduler.cancel(replacingId)
        planScheduler.schedule(plan).result.get()
    }
    suspend fun removePlan(id:String) = withContext(NonCancellable+Dispatchers.IO) {
        check(bookPreferences.removePlan(id)) { "Plan was not removed" }
        planScheduler.cancel(id)
    }
    LaunchedEffect(bookPreferences) {
        try { withContext(NonCancellable+Dispatchers.IO){planScheduler.restoreMissing(bookPreferences.plans)} }
        catch(e:Exception) {
            if(e is kotlinx.coroutines.CancellationException)throw e
            navSnackbar.showSnackbar("Планы сохранены, но не удалось восстановить напоминания. Откройте план и повторите сохранение.")
        }
    }

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
        LaunchedEffect(requested,restoreReady) {
            if(!restoreReady)return@LaunchedEffect
            val id = requested ?: return@LaunchedEffect
            startupHandled=true
            showHome(1)
            if(bakingViewModel.prepareBakeDestination(id))navController.navigate(MadreDestinations.bake(id.toString())){launchSingleTop=true}
            else navSnackbar.showSnackbar("Выпечка уже закрыта")
            if(pendingSessionId.value==id)pendingSessionId.value=null
        }
    }

    // Cycle 18: кнопка «Покормить» из шторки открывает форму кормления. Тот же
    // приём, что и с выпечкой: намерение ждёт, пока NavHost готов, и гасится
    // сразу после исполнения, чтобы поворот экрана не открыл форму дважды.
    if (pendingFeeding != null) {
        val requested by pendingFeeding.collectAsState()
        LaunchedEffect(requested) {
            if (!requested) return@LaunchedEffect
            startupHandled=true
            pendingFeeding.value = false
            enterFeedingForm()
        }
    }

    LaunchedEffect(pendingPlanned,restoreReady) {
        if(pendingPlanned==null || !restoreReady)return@LaunchedEffect
        pendingPlanned.collect { request ->
            if(request==null)return@collect
            startupHandled=true
            val plan=bookPreferences.plans.firstOrNull{it.id==request.id}
            val recipe=plan?.let{p->bakingViewModel.recipes.value.firstOrNull{it.id==p.recipeId}}
            try {
                if(plan==null && request.action==com.polinalinen.madre.notifications.PlannedBakeAction.START) {
                    bakingViewModel.sessionForPlan(request.id)?.let{session->
                        showHome(1);navController.navigate(MadreDestinations.bake(session.id.toString())){launchSingleTop=true}
                    }
                } else if(plan!=null && recipe!=null) {
                    aliceSheet=false;startRecipeId=null;showHome(1)
                    when(request.action) {
                        com.polinalinen.madre.notifications.PlannedBakeAction.OPEN->openStart(recipe.id,1,plan.id)
                        com.polinalinen.madre.notifications.PlannedBakeAction.START->{
                            val sid=bakingViewModel.startPlannedBake(recipe,plan.portions.toDouble(),plan.id)
                            if(sid!=null) {
                                removePlan(plan.id)
                                navController.navigate(MadreDestinations.bake(sid.toString())){launchSingleTop=true}
                            } else navigationScope.launch{navSnackbar.showSnackbar("Не удалось начать выпечку. План сохранён.")}
                        }
                        com.polinalinen.madre.notifications.PlannedBakeAction.SNOOZE->{
                            storePlan(BakePlanning.snooze(plan,System.currentTimeMillis()),plan.id)
                            navigationScope.launch{navSnackbar.showSnackbar("Напомним через 30 минут")}
                        }
                    }
                }
            } catch(e:Exception) {
                if(e is kotlinx.coroutines.CancellationException)throw e
                if(plan!=null)openStart(plan.recipeId,1,plan.id)
                planError="Не удалось выполнить действие. Проверьте план и попробуйте ещё раз."
            }
            if(pendingPlanned.value==request)pendingPlanned.value=null
        }
    }

    LaunchedEffect(restoreReady) {
        if(!restoreReady || startupHandled)return@LaunchedEffect
        startupHandled=true
        if(navController.currentDestination?.route!=MadreDestinations.HOME || pendingSessionId?.value!=null || pendingFeeding?.value==true || pendingPlanned?.value!=null)return@LaunchedEffect
        val now=com.polinalinen.madre.model.BakingClock.elapsed()
        bakingViewModel.sessions.value.filter { !it.isCompleted && !it.isPaused && it.restoredStepValid && it.remainingSeconds(now)==0L }
            .minByOrNull { it.stepEndsAtElapsed(now) }?.let { showHome(1);navController.navigate(MadreDestinations.bake(it.id.toString())) }
    }
    val undo by bakingViewModel.undo.collectAsState()
    val undoScope = rememberCoroutineScope()
    LaunchedEffect(undo?.token) {
        val token=undo?:return@LaunchedEffect
        if(navSnackbar.showSnackbar(token.message,token.actionLabel,indefinite=true)) {
            undoScope.launch {
                bakingViewModel.undo(token.token).join()
                if(token.kind==com.polinalinen.madre.model.BakeUndo.Kind.START && bakingViewModel.session(token.sessionId)==null &&
                    navController.currentDestination?.route==MadreDestinations.BAKE && navController.currentBackStackEntry?.arguments?.getString("sessionId")==token.sessionId.toString())navController.popBackStack()
                if(token.kind==com.polinalinen.madre.model.BakeUndo.Kind.ABANDON && bakingViewModel.session(token.sessionId)!=null) {
                    showHome(1);navController.navigate(MadreDestinations.bake(token.sessionId.toString()))
                }
            }
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
    readingPreferences.onReducedMotionChanged=setCalmMode

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

    CompositionLocalProvider(LocalCalmMode provides calmMode, LocalBookDisplaySettings provides displaySettings) {
        RazvorotTheme(readingPreferences) {
        Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = MadreDestinations.HOME) {
            composable(MadreDestinations.HOME) {
                val sessions by bakingViewModel.sessions.collectAsState()
                val homeSection by it.savedStateHandle.getStateFlow("home_section",initialHomeSection).collectAsState()
                RazvorotHome(
                    recipes = recipesForStats, sessions = sessions, viewModel = bakingViewModel,
                    starterName = starterName, day = dayNumber, phase = phase,
                    lastFeedingMillis = authoritativeLastFeedingMillis, hydration = shownHydration,
                    preferences = readingPreferences,
                    intervalHours = sourdoughConfig?.intervalHours ?: 24,
                    initialSection = homeSection,
                    onSectionChanged = {section->activeSection=section;it.savedStateHandle["home_section"]=section;bookPreferences.visit(section)},
                    bookPreferences = bookPreferences,
                    onOpenChapter = {id,page->openChapter(id,page=page)},
                    onStartRecipe = {recipe->navigationScope.launch { bakingViewModel.startBakingWithUndo(recipe,bookPreferences.portions(recipe.id).toDouble())?.let{id->navController.navigate(MadreDestinations.bake(id.toString()))} }},
                    onConfigureRecipe = {recipe->openStart(recipe.id)},
                    onConfigurePlan = {plan->openStart(plan.recipeId,1,plan.id)},
                    onOpenShelf = { navController.navigate(MadreDestinations.SHELF) },
                    onContinueReading = { session -> openChapter(session.recipe.id,step=session.currentStepIndex) },
                    onOpenRecipe = { id -> openChapter(id) },
                    onOpenTimer = { id -> navController.navigate(MadreDestinations.bake(id.toString())) },
                    onFeed = enterFeedingForm,
                    onDiary = { navController.navigate(MadreDestinations.STARTER_DETAIL) },
                    onSettings = { navController.navigate(MadreDestinations.SETTINGS) },
                    shelf = { ShelfScreen(myName, bakeRecords, { navController.navigate(MadreDestinations.SETTINGS_SHELF) },
                        { owner -> navController.navigate(MadreDestinations.bookStats(owner)) }, familyBookState.account, shelfViewModel, onOpenAlice = { navController.navigate(MadreDestinations.ALICE) }) },
                )
            }
            composable(MadreDestinations.CHAPTER,arguments=listOf(
                navArgument("page"){type=NavType.IntType;defaultValue=-1},navArgument("step"){type=NavType.IntType;defaultValue=-1}),
                enterTransition={androidx.compose.animation.EnterTransition.None}) { backStackEntry ->
                val recipeId = backStackEntry.arguments?.getString("recipeId").orEmpty()
                val sessions by bakingViewModel.sessions.collectAsState()
                var factor by rememberSaveable(recipeId) { mutableStateOf(bookPreferences.portions(recipeId).toDouble()) }
                var calculator by rememberSaveable(recipeId) { mutableStateOf(false) }
                val changeFactor:(Double)->Unit={value->factor=value;if(value in 1.0..5.0 && value==value.toInt().toDouble())bookPreferences.savePortions(recipeId,value.toInt())}
                ChapterEntry(recipeId,readingPreferences.reducedMotion || calmMode) {
                RazvorotReader(recipesForStats, recipeId, readingPreferences,
                    onBack = { showHome(0) },
                    onBake = { recipe -> navigationScope.launch { bakingViewModel.startBakingWithUndo(recipe, factor)?.let { id -> navController.navigate(MadreDestinations.bake(id.toString())) } } },
                    onCalculator = { calculator=true },
                    activeSessions = sessions,
                    onContinueBake = { id -> navController.navigate(MadreDestinations.bake(id.toString())) },
                    initialStep=backStackEntry.arguments?.getInt("step")?.takeIf{it>=0},
                    initialPage=backStackEntry.arguments?.getInt("page")?:-1,
                    onBookmarkChanged=bookPreferences::saveBookmark,
                    scaleFactor=factor,onScaleFactor=changeFactor,onOpenAlice={aliceSheet=true},
                    onSchedule={recipe->openStart(recipe.id,1,fromOven=false)},
                    scrollContent={ _,scrollNavigation->RecipeDetailScreen(recipeId,{showHome(0)},{},bakingViewModel,onOpenAlice={aliceSheet=true},
                        embedded=true,scaleFactorOverride=factor,textSize=readingPreferences.textSize,chapterScrollNavigation=scrollNavigation,onPortions={calculator=true}) },
                )
                }
                if(calculator)recipesForStats.firstOrNull{it.id==recipeId}?.let { recipe->BookPortionsSheet(recipe,factor,changeFactor){calculator=false} }
            }
            composable(MadreDestinations.BAKE) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull() ?: return@composable
                val sessions by bakingViewModel.sessions.collectAsState()
                val completions by bakingViewModel.completions.collectAsState()
                var prepared by remember(sessionId) { mutableStateOf(false) }

                LaunchedEffect(sessionId) {
                    if(!bakingViewModel.prepareBakeDestination(sessionId)) { showHome(1);navSnackbar.showSnackbar("Выпечка уже закрыта") }
                    prepared=true
                }
                val session=sessions.firstOrNull{it.id==sessionId}
                if(!prepared)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){PaperProgress()}
                else if(session?.isCompleted==true || completions.containsKey(sessionId))BakingFinale(sessionId,
                    onHome={showHome(1);navigationScope.launch{navSnackbar.showSnackbar("Вписано в книгу")}},viewModel=bakingViewModel,
                    onBack={showHome(1)},recentPhotos=galleryPhotos,familyName=familyBookState.account?.familyName,
                    onSwitchSession={id->navController.navigate(MadreDestinations.bake(id.toString())) {
                        popUpTo(MadreDestinations.BAKE){inclusive=true}
                    }})
                else RazvorotTimer(
                    sessionId = sessionId,

                    onOpenAlice = { aliceSheet = true },
                    onBack = { navController.popBackStack() },
                    onComplete = {},
                    viewModel = bakingViewModel,
                    onOpenChapter={ bake->openChapter(bake.recipe.id,step=bake.currentStepIndex) },
                    onKeepScreenOnChange={ value->navigationScope.launch {
                        try { app.familySettingsRepository.setKeepScreenOn(value) }
                        catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_:Exception) { navSnackbar.showSnackbar("Не удалось сохранить настройку экрана. Повторите выбор.") }
                    } },
                    onSwitchSession={id->navController.navigate(MadreDestinations.bake(id.toString())) {
                        popUpTo(MadreDestinations.BAKE){inclusive=true}
                    } },
                    onAbandon={id->navigationScope.launch {
                        bakingViewModel.abandonWithUndo(id).join()
                        if(bakingViewModel.pendingAbandons.value.containsKey(id))showHome(1)
                    } },
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
                    onOpenAlice = { navController.navigate(MadreDestinations.ALICE) },
                    familyBookViewModel = familyBookViewModel,
                    readingPreferences = readingPreferences,
                )
            }
            composable(MadreDestinations.ALICE) {
                com.polinalinen.madre.ui.screens.AliceScreen(bakingViewModel, app.voiceCoordinator) { navController.popBackStack() }
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
                Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                Box(androidx.compose.ui.Modifier.weight(1f)) { ShelfScreen(
                    myName = myName,
                    localRecords = bakeRecords,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { ownerId -> navController.navigate(MadreDestinations.bookStats(ownerId)) },
                    account = familyBookState.account,
                    shelfViewModel = shelfViewModel,
                    onOpenAlice = { navController.navigate(MadreDestinations.ALICE) },
                ) }

                SpineBar(3) { section -> if(section!=3) {navController.previousBackStackEntry?.savedStateHandle?.set("home_section",section);navController.popBackStack()} }
                }
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
            PaperSnackbar(navSnackbar,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal=12.dp,vertical=80.dp))
        }
            startRecipeId?.let { id->recipesForStats.firstOrNull{it.id==id}?.let { recipe->
                val editing=bookPreferences.plans.firstOrNull{it.id==editingPlanId}
                BookStartSheet(recipe,editing?.portions?:bookPreferences.portions(id),onStart={portions->
                    planSaving=true;planError=null
                    navigationScope.launch {
                        try {
                            val sid=if(editing!=null)bakingViewModel.startPlannedBake(recipe,portions.toDouble(),editing.id)
                                else bakingViewModel.startBakingWithUndo(recipe,portions.toDouble())
                            if(sid!=null) {
                                if(editing!=null)removePlan(editing.id)
                                bookPreferences.savePortions(id,portions);startRecipeId=null
                                navController.navigate(MadreDestinations.bake(sid.toString()))
                            } else planError="Не удалось начать выпечку. Попробуйте ещё раз."
                        } catch(e:Exception) {
                            if(e is kotlinx.coroutines.CancellationException)throw e
                            planError="Не удалось завершить действие. План сохранён."
                        } finally {planSaving=false}
                    }
                },onDismiss={startRecipeId=null},onChapter=if(startFromOven)({startRecipeId=null;openChapter(id)}) else null,
                    initialMode=startMode,existingPlan=editing,busy=planSaving,error=planError,onPlan={portions,start->
                        planSaving=true;planError=null
                        navigationScope.launch {
                            try {
                                storePlan(PlannedBake(id,portions,start),editing?.id)
                                bookPreferences.savePortions(id,portions);startRecipeId=null;showHome(1)
                                navigationScope.launch { navSnackbar.showSnackbar(if(com.polinalinen.madre.notifications.MadreNotifier(context).canPost())"Запланировано · напомним за 10 минут"
                                    else "План сохранён. Уведомления отключены в настройках Android.") }
                            } catch(e:Exception) {
                                if(e is kotlinx.coroutines.CancellationException)throw e
                                planError="Не удалось сохранить план и напоминание. Попробуйте ещё раз."
                            } finally {planSaving=false}
                        }
                    },onRemovePlan={planSaving=true;navigationScope.launch {
                        try {editing?.let{removePlan(it.id)};startRecipeId=null}
                        catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;planError="Не удалось убрать план. Попробуйте ещё раз."}
                        finally{planSaving=false}
                    }})
            } }
            if (aliceSheet) AliceSheet(bakingViewModel, app.voiceCoordinator,
                onDismiss = { aliceSheet = false }, onOpenSettings = {
                    aliceSheet = false; navController.navigate(MadreDestinations.ALICE)
                })
        }
    }
}
