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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
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
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.Season
import com.polinalinen.madre.model.SeasonalEdition
import com.polinalinen.madre.model.WeatherNote
import com.polinalinen.madre.sourdough.FeedingCall
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.sourdough.hoursSinceFeeding
import com.polinalinen.madre.ui.components.BookBreath
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BookButtonVariant
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.RibbonBookmark
import com.polinalinen.madre.ui.components.Stamp
import com.polinalinen.madre.ui.components.TicketFrame
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.breathingPage
import com.polinalinen.madre.ui.components.dampPaper
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.WeatherViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ярлыки для тестов: первая полоса длинная, и до строки оглавления надо
 * ещё доехать — без ярлыка её в композиции просто нет.
 */
object TocLine {
    /** A11y и тесты: «Хлебушек домашний, рецепт 1». */
    fun contentDescription(name: String, index: Int): String =
        "$name, рецепт $index"

    /**
     * Однострочное оглавление для тестов и логов.
     * Визуальные точки на экране рисует [TocLeaders]; здесь — фиксированные
     * лидеры, чтобы assert не зависел от ширины.
     */
    fun format(name: String, index: Int, leaderWidth: Int = 24): String {
        val dots = ".".repeat(leaderWidth.coerceAtLeast(3))
        return "$name$dots$index"
    }
}

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
    /** Когда кормили в прошлый раз; null — дневник ещё пуст. */
    lastFeedingMillis: Long? = null,
    onOpenRecipe: (String) -> Unit,
    onOpenStarter: () -> Unit,
    onOpenTimer: (sessionId: Long) -> Unit,
    onOpenFeeding: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShelf: () -> Unit,
    viewModel: BakingViewModel = viewModel(),
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
    // После возврата из системных настроек (или обновления APK) перечитываем
    // grant — иначе UI может думать, что геолокации всё ещё нет.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission =
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) weatherViewModel.load()
    }
    val weatherNote by weatherViewModel.note.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    // «Затёртая страница» (Cycle 4, WornPages) — часто печёные главы темнеют.
    val bakeCounts by viewModel.bakeCounts.collectAsState()
    // «Ветхое ляссе» (Cycle 9, AgedRibbon): лента стареет со всей книгой —
    // возраст считаем по общему числу выпечек всех глав.
    val totalBakes = bakeCounts.values.sum()
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
                item { FeedingCallButton(starterName, phase, lastFeedingMillis, onOpenFeeding) }
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
                    PageLabel(
                        "Оглавление",
                        color = colors.espresso,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(recipes, key = { it.id }) { recipe ->
                    val index = recipes.indexOf(recipe) + 1
                    ChapterRow(
                        index = index,
                        recipe = recipe,
                        isSeasonal = recipe.id == seasonalRecipeId,
                        bakeCount = bakeCounts[recipe.id] ?: 0,
                        onClick = { onOpenRecipe(recipe.id) },
                    )
                }
                item { Colophon() }
            }
            PageRibbon(
                hasActiveBake = sessions.isNotEmpty(),
                phase = phase,
                totalBakes = totalBakes,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 34.dp),
            )
        }
    }
}

/**
 * Ляссе поверх первой полосы — только одно за раз: пока идёт хотя бы одна
 * выпечка, это лента выпечки, иначе mood-ляссе «Мадре советует» (в фазах
 * LAG/GROWING/EMPTY не показывается вовсе).
 *
 * Cycle 18: обе ленты — украшение страницы, и ничего больше.
 *
 * Baking-ляссе вело к таймеру второй дорогой мимо талона, причём не к той
 * выпечке, на которую человек смотрит, а к ближайшей по времени: с двумя
 * противнями в печи тап по ленте уводил не туда. Mood-ляссе решало за
 * человека само — на пике открывало рецепт попроще, в остальных фазах
 * кормление, — а о том, какая сейчас фаза, в момент нажатия знала только
 * книга. Теперь к выпечке ведёт её талон, к кормлению — кнопка под строкой
 * от Мадре, а лента лежит на странице и показывает, что происходит.
 *
 * Вынесено из [HomeScreen] отдельным composable не ради длины функции: так
 * «лента ничего не обещает» проверяется тестом напрямую, без Room, без
 * ViewModel и без восстановления незавершённой выпечки — на них проверка
 * первой полосы разъезжалась от одного лишь соседства с другими тестами.
 */
@Composable
internal fun PageRibbon(
    hasActiveBake: Boolean,
    phase: GrowthPhase,
    totalBakes: Int,
    modifier: Modifier = Modifier,
) {
    if (hasActiveBake) {
        RibbonBookmark(modifier, totalBakes = totalBakes)
    } else {
        moodBookmarkSpec(phase)?.let { spec ->
            MoodBookmark(spec = spec, totalBakes = totalBakes, modifier = modifier)
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
        // Cycle 18 MoA: «Полка» → «Летопись» — ведёт сразу в свою книгу-формуляр,
        // не в промежуточный разворот корешков (там пока нечего выбирать).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextAction("Летопись", onClick = onOpenShelf)
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
 * «Покормить» — главное действие дня, сразу под строкой от Мадре
 * (DESIGN-V4.md Cycle 18).
 *
 * Кормление — единственное, что делают в этой книге каждый день, а дорога к
 * нему шла через три экрана: первая полоса → дневник → форма. Теперь один тап.
 *
 * Пока кормить пора — это единственная кнопка заливкой на всей полосе. Когда
 * кормили недавно, она не гаснет и не исчезает: кормить закваску раньше срока
 * человек вправе, и запрещать ему это книге не за что. Меняется только голос —
 * рамка вместо заливки — и под кнопкой появляется, когда кормили. Время
 * относительное и настоящее: если его нет, подписи тоже нет (см.
 * [FeedingCall.sinceLabel]).
 */
@Composable
private fun FeedingCallButton(
    starterName: String,
    phase: GrowthPhase,
    lastFeedingMillis: Long?,
    onOpenFeeding: () -> Unit,
) {
    val fresh = FeedingCall.isFresh(phase)
    val caption = if (fresh) {
        FeedingCall.sinceLabel(lastFeedingMillis?.let { hoursSinceFeeding(it) })
    } else {
        null
    }
    BookButton(
        label = FeedingCall.label(starterName),
        onClick = onOpenFeeding,
        variant = if (fresh) BookButtonVariant.SECONDARY else BookButtonVariant.PRIMARY,
        caption = caption,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
    )
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
    onClick: () -> Unit,
    isSeasonal: Boolean = false,
    @Suppress("UNUSED_PARAMETER") bakeCount: Int = 0,
) {
    val colors = AppColors.current
    // Оглавление: имя + лидеры Canvas + номер. Без серой WornPage-заливки.
    val a11y = TocLine.contentDescription(recipe.name, index)
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(Home.chapterRowTag(recipe.id))
            .then(bookAction(a11y) { onClick() }),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // weight(fill=false): имя берёт сколько нужно, но не съедает ряд целиком —
            // иначе у длинных названий (3–11) лидерам остаётся 0 dp.
            Text(
                recipe.name,
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            TocLeaders(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .padding(bottom = 5.dp),
                color = colors.cocoa.copy(alpha = 0.45f),
            )
            Text(
                index.toString(),
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
            )
        }
        if (isSeasonal) {
            Text(
                "глава сезона",
                color = colors.crust,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 22.dp, top = 2.dp),
            )
        }
        HairRule(Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp))
    }
}

/**
 * Лидеры — пунктир Canvas на всю доступную ширину (не Text с ·.repeat:
 * длинное имя сжимало weight до нуля и точки пропадали).
 */
@Composable
private fun TocLeaders(modifier: Modifier = Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(8.dp)) {
        val y = size.height - 1.dp.toPx()
        val step = 5.dp.toPx()
        val r = 1.1.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawCircle(color = color, radius = r, center = androidx.compose.ui.geometry.Offset(x, y))
            x += step
        }
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
    // Cycle 18: описания рассказывают о закваске, а не о нажатии — ляссе
    // больше никуда не ведёт. «— открыть рецепт» и «— покормить» обещали
    // дорогу, которая зависела от фазы и была не видна глазом.
    return when (phase) {
        GrowthPhase.PEAK -> MoodBookmarkSpec(
            colors.sage, "Мадре советует: пеките сейчас!",
            "Закваска на пике",
        )
        GrowthPhase.DECLINING -> MoodBookmarkSpec(
            colors.crust, "Покормите меня сначала…",
            "Закваска просит еды",
        )
        GrowthPhase.HUNGRY -> MoodBookmarkSpec(
            colors.terracotta, "Я проголодалась…",
            "Закваска давно не кормлена",
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
 * «Общая статистика» жила подвалом первой полосы с Cycle 5 и умерла в
 * Cycle 18 — см. docs/graveyard.md. Единственным её читателем был этот экран,
 * поэтому CommunityStatsViewModel и CommunityStats.from сейчас не показывает
 * никто; они оставлены как есть, и это записано в graveyard, чтобы следующий
 * цикл решил их судьбу осознанно, а не наткнулся на них случайно.
 */

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
