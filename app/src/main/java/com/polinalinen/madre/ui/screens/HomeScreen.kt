package com.polinalinen.madre.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.model.CommunityStats
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.Season
import com.polinalinen.madre.model.SeasonalEdition
import com.polinalinen.madre.model.WeatherNote
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.ui.components.BookBreath
import com.polinalinen.madre.ui.components.DogEar
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.RibbonBookmark
import com.polinalinen.madre.ui.components.Stamp
import com.polinalinen.madre.ui.components.TicketFrame
import com.polinalinen.madre.ui.components.WornPage
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.breathingPage
import com.polinalinen.madre.ui.components.dampPaper
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.CommunityState
import com.polinalinen.madre.viewmodel.CommunityStatsViewModel
import com.polinalinen.madre.viewmodel.WeatherViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ярлыки для тестов: первая полоса длинная, и до строки оглавления надо
 * ещё доехать — без ярлыка её в композиции просто нет.
 */
object Home {
    const val LIST_TAG = "home-list"

    fun chapterRowTag(recipeId: String): String = "home-chapter-$recipeId"
}

/**
 * Главная — «Первая полоса» (DESIGN-V4.md, экран 1).
 * Masthead → строка от Мадре → талон «В ПЕЧИ» + ляссе → оглавление.
 */
@Composable
fun HomeScreen(
    madreHeadline: String,
    starterName: String = StarterName.DEFAULT,
    phase: GrowthPhase,
    favoriteIds: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onOpenStarter: () -> Unit,
    onOpenTimer: (sessionId: Long) -> Unit,
    onOpenFeeding: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShelf: () -> Unit,
    viewModel: BakingViewModel = viewModel(),
    communityViewModel: CommunityStatsViewModel = viewModel(),
    weatherViewModel: WeatherViewModel = viewModel(),
) {
    val colors = AppColors.current
    val recipes by viewModel.recipes.collectAsState()
    // «Погода за окном» (Cycle 7, WeatherPage): заметка Мадре на полях +
    // отсыревшая бумага в дождь. Без разрешения на геолокацию — тихое
    // приглашение; без сети/локации — молчание (ViewModel не ругается).
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) weatherViewModel.load()
    }
    val weatherNote by weatherViewModel.note.collectAsState()
    // «Общая статистика» (Cycle 5) — сводка bake_stats других семей из PocketBase.
    val communityState by communityViewModel.state.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    // «Затёртая страница» (Cycle 4, WornPages) — часто печёные главы темнеют.
    val bakeCounts by viewModel.bakeCounts.collectAsState()
    // «Ветхое ляссе» (Cycle 9, AgedRibbon): лента стареет со всей книгой —
    // возраст считаем по общему числу выпечек всех глав.
    val totalBakes = bakeCounts.values.sum()
    // Ляссе ведёт к той выпечке, что ближе всего к следующему шагу. Сам остаток
    // первая полоса не читает и читать не должна: талон выпечки показывает шаг,
    // а не секунды, а карта остатков меняется раз в секунду — на ней всё
    // оглавление перерисовывалось бы вместе с ней (Cycle 16).
    val nearestSessionId by viewModel.nearestSessionId.collectAsState()
    // «Мадре советует» — рецепт попроще, пока закваска на пике и время дорого.
    val recommendedRecipeId = recipes.minByOrNull { if (it.difficulty > 0) it.difficulty else Int.MAX_VALUE }?.id
    // «Сезонная глава» (Cycle 3) — ярлык у даты + отметка на одном рецепте оглавления.
    val season = SeasonalEdition.seasonForMonth(Calendar.getInstance().get(Calendar.MONTH) + 1)
    val seasonalRecipeId = SeasonalEdition.recipeIdFor(season)

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        // «Дыхание книги» (Cycle 6, BookBreath): первая полоса дышит целиком —
        // вместе с ляссе — но вдвое тише дневника (scale 1.000→1.002).
        Box(
            Modifier
                .breathingPage(phase, amplitude = BookBreath.HOME_AMPLITUDE)
                .dampPaper(weatherNote?.dampAlpha ?: 0f)
        ) {
            LazyColumn(modifier = Modifier.statusBarsPadding().testTag(Home.LIST_TAG)) {
                item { Masthead(season, onOpenSettings, onOpenShelf) }
                item { MadreLine(madreHeadline, starterName, onOpenStarter) }
                item {
                    WeatherMargin(
                        note = weatherNote,
                        showInvite = !hasLocationPermission,
                        onInvite = {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        },
                    )
                }
                // Талон на каждую активную выпечку разом — печей в доме может
                // готовиться несколько одновременно (2026-07-21).
                items(sessions, key = { it.id }) { s ->
                    Box(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                        TicketFrame(
                            Modifier
                                .fillMaxWidth()
                                .then(bookAction("Открыть выпечку «${s.recipe.name}»") { onOpenTimer(s.id) })
                        ) {
                            ActiveBakingTicket(
                                recipeName = s.recipe.name,
                                stepIndex = s.currentStepIndex,
                                stepCount = s.recipe.timeline.size,
                                portions = s.scaleFactor.toInt().coerceAtLeast(1),
                                isPaused = s.isPaused,
                            )
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        PageLabel("Оглавление", color = colors.espresso)
                        Text(
                            "все ${recipes.size}",
                            color = colors.crust,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 12.sp,
                        )
                    }
                }
                items(recipes, key = { it.id }) { recipe ->
                    ChapterRow(
                        index = recipes.indexOf(recipe) + 1,
                        recipe = recipe,
                        isFavorite = recipe.id in favoriteIds,
                        isSeasonal = recipe.id == seasonalRecipeId,
                        bakeCount = bakeCounts[recipe.id] ?: 0,
                        onClick = { onOpenRecipe(recipe.id) },
                        onToggleFavorite = { onToggleFavorite(recipe.id) },
                    )
                }
                item { CommunitySection(communityState, onRetry = communityViewModel::refresh) }
                item { Colophon() }
            }
            // Ляссе поверх страницы — только пока идёт хотя бы одна выпечка.
            // Если выпечки нет — вместо неё mood-ляссе «Мадре советует» (приоритет
            // у baking-ляссе, они никогда не показываются вместе).
            val nearest = nearestSessionId
            if (nearest != null) {
                RibbonBookmark(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 34.dp)
                        .then(bookAction("Открыть таймер активной выпечки") { onOpenTimer(nearest) }),
                    totalBakes = totalBakes,
                )
            } else {
                moodBookmarkSpec(phase)?.let { spec ->
                    MoodBookmark(
                        spec = spec,
                        totalBakes = totalBakes,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 34.dp)
                            .then(
                                bookAction(spec.description) {
                                    if (phase == GrowthPhase.PEAK) {
                                        recommendedRecipeId?.let(onOpenRecipe)
                                    } else {
                                        onOpenFeeding()
                                    }
                                }
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Masthead(
    season: Season,
    onOpenSettings: () -> Unit,
    onOpenShelf: () -> Unit,
) {
    val colors = AppColors.current
    val date = SimpleDateFormat("EEEE · d MMMM", Locale("ru")).format(Date())
    Column(
        Modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageLabel("Домашняя пекарня Полины")
        Text(
            "МАДРЕ",
            color = colors.espresso,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 44.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeavyRule(Modifier.weight(1f))
            Text(
                // «Сезонная глава» (Cycle 3) — ярлык рядом с датой выпуска.
                "${date.uppercase()} · ${SeasonalEdition.labelFor(season).uppercase()}",
                color = colors.cocoa,
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            HeavyRule(Modifier.weight(1f))
        }
        // Cycle 12: два выхода с первой полосы — названные и видимые.
        //
        // До этого «Полка» пряталась под надписью «Домашняя пекарня Полины», а
        // «Настройки» — под самим словом МАДРЕ. Ни то, ни другое ничем не
        // выдавало себя как кнопку: попасть туда можно было только случайно,
        // а тап по заголовку книги, уводящий в настройки, ещё и сбивал с толку.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextAction("Полка", onClick = onOpenShelf)
            TextAction("Настройки", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun MadreLine(headline: String, starterName: String, onOpenStarter: () -> Unit) {
    val colors = AppColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .then(bookAction("Открыть дневник закваски") { onOpenStarter() })
            .padding(horizontal = 22.dp, vertical = 6.dp)
    ) {
        PageLabel(StarterName.homeLabel(starterName))
        Text(
            "«$headline»",
            color = colors.espresso,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * «Погода за окном» (DESIGN-V4.md Cycle 7, фича WeatherPage) — заметка Мадре
 * на полях первой полосы: рукописная (Cursive), с лёгким поворотом, как
 * заметки Мадре. Пока разрешения на геолокацию нет — тихая строка-
 * приглашение; в мягкую погоду и без разрешения-отказа блок молчит совсем.
 */
@Composable
private fun WeatherMargin(note: WeatherNote?, showInvite: Boolean, onInvite: () -> Unit) {
    val colors = AppColors.current
    when {
        note != null -> Text(
            note.text,
            color = colors.cocoa,
            fontFamily = FontFamily.Cursive,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier
                .padding(horizontal = 22.dp, vertical = 4.dp)
                .rotate(-1.2f),
        )
        // Cycle 18: приглашение было надписью 10sp высотой в ноготь — тихим
        // оно и осталось, но теперь это тихое действие книги, а не строка,
        // про которую надо догадаться, что она нажимается.
        showInvite -> TextAction(
            label = "впустить погоду за окном",
            onClick = onInvite,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun ActiveBakingTicket(
    recipeName: String,
    stepIndex: Int,
    stepCount: Int,
    portions: Int,
    isPaused: Boolean,
) {
    val colors = AppColors.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(recipeName, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 17.sp)
                Text(
                    "  ×$portions ${familyWord(portions)}",
                    color = colors.crust,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                )
            }
            Stamp("В печи", colors.terracotta)
        }
        Text(
            "шаг ${stepIndex + 1} из $stepCount" + if (isPaused) " · пауза" else "",
            color = colors.cocoa,
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(stepCount) { i ->
                val tone = when {
                    i < stepIndex -> colors.espresso
                    i == stepIndex -> colors.crust
                    else -> colors.flour
                }
                Box(Modifier.weight(1f).height(3.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(tone) }
                }
            }
        }
    }
}

private fun familyWord(n: Int) = when (n) {
    1 -> "семья"
    in 2..4 -> "семьи"
    else -> "семей"
}

@Composable
private fun ChapterRow(
    index: Int,
    recipe: Recipe,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    isSeasonal: Boolean = false,
    bakeCount: Int = 0,
) {
    val colors = AppColors.current
    val difficultyLabel = when {
        recipe.difficulty <= 2 -> "легко" to colors.sage
        recipe.difficulty == 3 -> "средне" to colors.crust
        else -> "сложно" to colors.terracotta
    }
    val hours = recipe.timeline.sumOf { it.durationMinutes } / 60
    // «Затёртая страница» (Cycle 4, WornPages): строку часто печёной главы
    // едва заметно тонирует Espresso — как засаленную страницу оглавления.
    val wornAlpha = WornPage.tocAlpha(bakeCount)
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(Home.chapterRowTag(recipe.id))
            .then(bookAction("Открыть главу «${recipe.name}»") { onClick() })
            .drawBehind { if (wornAlpha > 0f) drawRect(colors.espresso.copy(alpha = wornAlpha)) }
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "%02d".format(index),
                color = colors.flour,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.width(40.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(recipe.name, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 16.sp)
                Row {
                    Text(
                        "$hours ч · ",
                        color = colors.cocoa, fontFamily = FontFamily.SansSerif, fontSize = 11.sp,
                    )
                    Text(
                        difficultyLabel.first,
                        color = difficultyLabel.second, fontFamily = FontFamily.SansSerif, fontSize = 11.sp,
                    )
                    // «Глава сезона» (Cycle 3, SeasonalEdition) — отметка на одном рецепте.
                    if (isSeasonal) {
                        Text(
                            " · глава сезона",
                            color = colors.crust,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        // Загнутый уголок — избранное (механика #3). Touch target 48dp через padding.
        DogEar(
            isFavorite = isFavorite,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .then(
                    bookAction(
                        if (isFavorite) "Убрать главу из избранного" else "Отметить главу как любимую"
                    ) { onToggleFavorite() }
                )
                .padding(6.dp),
        )
        HairRule(Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp))
    }
}

/**
 * «Мадре советует» (DESIGN-V4.md Cycle 1, фича MoodBookmark) — вторая ляссе
 * на главной, цвет и текст зависят от фазы закваски. Показывается только
 * пока не идёт активная выпечка (см. HomeScreen — baking-ляссе в приоритете).
 */
private data class MoodBookmarkSpec(val color: Color, val label: String, val description: String)

@Composable
private fun moodBookmarkSpec(phase: GrowthPhase): MoodBookmarkSpec? {
    val colors = AppColors.current
    return when (phase) {
        GrowthPhase.PEAK -> MoodBookmarkSpec(
            colors.sage, "Мадре советует: пеките сейчас!",
            "Закваска на пике — открыть рецепт",
        )
        GrowthPhase.DECLINING -> MoodBookmarkSpec(
            colors.crust, "Покормите меня сначала…",
            "Закваска просит еды — покормить",
        )
        GrowthPhase.HUNGRY -> MoodBookmarkSpec(
            colors.terracotta, "Я проголодалась…",
            "Закваска давно не кормлена — покормить",
        )
        else -> null
    }
}

@Composable
private fun MoodBookmark(spec: MoodBookmarkSpec, totalBakes: Int = 0, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        RibbonBookmark(color = spec.color, description = spec.description, totalBakes = totalBakes)
        Text(
            spec.label,
            color = spec.color,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 130.dp).padding(top = 4.dp, end = 4.dp),
        )
    }
}

/**
 * «Общая статистика» (DESIGN-V4.md Cycle 5) — газетный подвал перед колофоном:
 * сколько ещё телефонов в семье пишут в эту книгу и что пекли на неделе.
 * Данные — bake_stats других устройств (CommunityStatsViewModel); без сети
 * секция не исчезает, а честно говорит, что общая книга сейчас не видна.
 *
 * Cycle 17: и про незнакомые семьи здесь больше ни слова. Сервер отдаёт
 * только свою семью (см. CommunityStats), а подвал до сих пор считал те же
 * записи «другими семьями, ведущими такую же книгу».
 */
@Composable
private fun CommunitySection(state: CommunityState, onRetry: () -> Unit) {
    val colors = AppColors.current
    val stats = (state as? CommunityState.Loaded)?.stats
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
        PageLabel("Общая статистика", color = colors.espresso)
        Spacer(Modifier.height(10.dp))
        when {
            state is CommunityState.Loading -> Text(
                "заглядываем в общую книгу…",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
            )
            // Сети нет — и это не тупик: попытку можно повторить, не выходя
            // из книги. Раньше здесь была строка без единого действия.
            // Аккаунта нет — общей книги не существует, и это законный
            // выбор, а не поломка. Повторять здесь нечего: дорога внутрь одна
            // и лежит через колофон.
            state is CommunityState.SignedOut -> Text(
                "общая книга не подключена — её открывают в «Выходных данных»",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
            )
            state is CommunityState.Unreachable -> Column {
                Text(
                    "общая книга сейчас не отвечает — ваша книга работает и без неё",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                )
                TextAction("заглянуть ещё раз", onClick = onRetry)
            }
            stats == null -> Unit
            stats.handsBaking == 0 -> Text(
                "пока в общей книге только ваши записи — остальные подтянутся",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
            )
            else -> Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${stats.handsBaking}",
                        color = colors.crust,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                    )
                    Text(
                        "  ещё ${handsWord(stats.handsBaking)} в семье ${bakeVerb(stats.handsBaking)} по этой книге",
                        color = colors.espresso,
                        fontFamily = FontFamily.Serif,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                if (stats.popularRecipeOfWeek != null) {
                    Text(
                        "рецепт недели — «${stats.popularRecipeOfWeek}», " +
                            "${stats.bakesThisWeek} ${bakeWord(stats.bakesThisWeek)} за семь дней",
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun handsWord(n: Int) = when {
    n % 100 in 11..14 -> "телефонов"
    n % 10 == 1 -> "телефон"
    n % 10 in 2..4 -> "телефона"
    else -> "телефонов"
}

private fun bakeVerb(n: Int) = if (n % 10 == 1 && n % 100 !in 11..14) "печёт" else "пекут"

// bakeWord живёт в BookStatsScreen.kt — одно склонение на весь пакет экранов.
// Две одинаковые копии в одном пакете расходятся ровно в тот день, когда одну
// из них поправят.

@Composable
private fun Colophon() {
    val colors = AppColors.current
    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HeavyRule()
        Text(
            "— тираж: одна семья · печатается с любовью —",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
