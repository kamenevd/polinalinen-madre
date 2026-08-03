package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.polinalinen.madre.model.GuestNote
import com.polinalinen.madre.model.LibraryNote
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.ui.components.BookWormOverlay
import com.polinalinen.madre.ui.components.Candlelight
import com.polinalinen.madre.ui.components.candlelight
import com.polinalinen.madre.ui.components.DottedLeaderRow
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HandwrittenEditSurface
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.CoffeeRing
import com.polinalinen.madre.ui.components.DustLayer
import com.polinalinen.madre.ui.components.coffeeRings
import com.polinalinen.madre.ui.components.crumbs
import com.polinalinen.madre.ui.components.dustLayer
import com.polinalinen.madre.ui.components.lightPage
import com.polinalinen.madre.ui.components.wornPage
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.LocalCalmMode
import com.polinalinen.madre.utils.heroResFor
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.GuestNotesViewModel
import com.polinalinen.madre.viewmodel.LibraryNotesViewModel

/**
 * Рецепт — «Разворот» (DESIGN-V4.md, экран 2).
 * РЕЦЕПТ № NN → название → подвал-статистика → «НА СКОЛЬКО ПЕЧЁМ» →
 * фотокарточка → ингредиенты с отточиями → CTA.
 * Текст ингредиентов/шагов — строго из recipes.json (PDF Полины).
 */
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    onBack: () -> Unit,
    onStartBaking: (sessionId: Long) -> Unit,
    viewModel: BakingViewModel = viewModel(),
    libraryNotesViewModel: LibraryNotesViewModel = viewModel(),
    guestNotesViewModel: GuestNotesViewModel = viewModel(),
) {
    val colors = AppColors.current
    val recipes by viewModel.recipes.collectAsState()
    val recipe = recipes.find { it.id == recipeId } ?: return
    val chapterIndex = recipes.indexOf(recipe) + 1
    // «Страница на просвет» (Cycle 4, LightPage): сквозь бумагу разворота
    // зеркально просвечивает заголовок следующего рецепта книги.
    val nextRecipeName = recipes.getOrNull(chapterIndex)?.name

    var portions by remember { mutableIntStateOf(1) }
    val scaleFactor = portions.toDouble()

    // Сколько раз печён именно этот рецепт — питает крошки, износ и кофейные
    // круги. Берём из общего среза bake_records, который уже держит
    // BakingViewModel: отдельный Flow под это заводить незачем.
    val bakeCounts by viewModel.bakeCounts.collectAsState()
    val bakeCount = bakeCounts[recipeId] ?: 0
    // «Пыль на страницах» (Cycle 8, DustLayer): сколько дней главу не открывали —
    // разница между СЕЙЧАС и прошлым визитом из SharedPreferences. Читаем прошлую
    // дату один раз до перезаписи (remember), новую пишем в LaunchedEffect ниже.
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("madre_prefs", android.content.Context.MODE_PRIVATE)
    }
    val daysSinceOpened = remember(recipeId) {
        DustLayer.daysSince(prefs.getLong("last_opened_$recipeId", 0L), System.currentTimeMillis())
    }
    LaunchedEffect(recipeId) {
        prefs.edit().putLong("last_opened_$recipeId", System.currentTimeMillis()).apply()
    }
    // «След от кружки» (Cycle 9, CoffeeRing): сколько раз выпечку этой главы
    // прерывали — пишет BakingViewModel.cancelSession в те же madre_prefs.
    val coffeeRingCount = remember(recipeId) {
        prefs.getInt(CoffeeRing.prefsKey(recipeId), 0)
    }

    // «Библиотечная книга» (Cycle 6, LibraryNotes) — заметки чужих семей.
    val libraryNotes by libraryNotesViewModel.notes.collectAsState()
    // «Гостевая страница» (Cycle 7, GuestPage) — отзывы гостей из guest_notes.
    val guestNotes by guestNotesViewModel.notes.collectAsState()
    LaunchedEffect(recipeId) {
        libraryNotesViewModel.load(recipeId)
        guestNotesViewModel.load(recipeId)
    }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        // «Затёртая страница» (Cycle 4, WornPages) — износ висит на «листе»
        // (viewport-Box), а не на скроллящемся Column: край темнеет у кромки
        // экрана и не уезжает вместе с текстом.
        // «Крошки между страниц» (Cycle 7, Crumbs) — крошки лежат ПОВЕРХ износа
        // и блика, как в настоящей книге. Свайп-смахивание не мешает
        // вертикальному скроллу.
        // «Пыль на страницах» (Cycle 8, DustLayer) — самый внешний слой: пыль
        // осела последней и лежит поверх всего, включая крошки. Жест при этом
        // первым забирает внутренний обработчик крошек — см. коммент dustLayer.
        // «След от кружки» (Cycle 9, CoffeeRing) — между крошками и износом:
        // кофе пролит поверх печати, но крошки и пыль легли позже.
        // «Чтение при свече» (Cycle 10, Candlelight) — самый верхний слой:
        // после заката свет и полумрак ложатся поверх всей бумаги; палец
        // модификатор только слушает, жесты нижних слоёв не трогает.
        // «Спокойный режим» (Cycle 11) — по умолчанию включён: пыль, крошки и
        // свеча либо крутят бесконечную анимацию, либо слушают палец на всём
        // развороте, и именно они стоили прокрутке плавности. Здесь они не
        // «замедляются», а не создаются вовсе. Бумага при этом остаётся
        // бумагой: износ, кофейные круги и просвет следующей страницы —
        // статичные слои, они рисуются один раз и никуда не деваются.
        val calm = LocalCalmMode.current
        val candlelit = remember {
            Candlelight.isCandleTime(
                java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .candlelight(candlelit && !calm)
                .let {
                    if (calm) it
                    else it
                        .dustLayer(daysSinceOpened, seed = recipeId.hashCode().toLong())
                        .crumbs(bakeCount, seed = recipeId.hashCode().toLong())
                }
                .coffeeRings(coffeeRingCount, seed = recipeId.hashCode().toLong())
                .wornPage(bakeCount, seed = recipeId.hashCode().toLong())
                .lightPage(watermark = nextRecipeName, mirrored = true)
        ) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PageLabel("← Оглавление", Modifier.clickable { onBack() })
                PageLabel("Рецепт № %02d".format(chapterIndex))
            }

            Text(
                recipe.name,
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Text(
                recipe.description,
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )

            StatsFooter(recipe)

            PortionSelector(
                portions = portions,
                onSelect = { portions = it },
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )

            PastedPhoto(recipe, Modifier.padding(horizontal = 22.dp, vertical = 10.dp))

            recipe.ingredients.forEach { (section, items) ->
                val sectionTitle = when (section) {
                    "sponge" -> "Опара"
                    "main" -> "Тесто"
                    else -> section
                }
                PageLabel(sectionTitle, Modifier.padding(start = 22.dp, top = 14.dp), color = colors.espresso)
                Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
                    items.forEach { ingredient ->
                        val text = RecipeScaler.scaledDisplayText(ingredient, scaleFactor)
                        // Отточие: имя слева, граммы справа. Если формат нераздельный — одной строкой.
                        val parts = text.split(" г ", limit = 2)
                        if (parts.size == 2 && parts[0].toDoubleOrNull() != null) {
                            DottedLeaderRow(name = parts[1], value = "${parts[0]} г")
                        } else {
                            Text(
                                text,
                                color = colors.espresso,
                                fontFamily = FontFamily.Serif,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            LibraryNotesSection(
                notes = libraryNotes,
                modifier = Modifier.padding(top = 14.dp),
            )

            GuestNotesSection(
                notes = guestNotes,
                modifier = Modifier.padding(top = 18.dp),
            )

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .padding(horizontal = 22.dp)
                    .fillMaxWidth()
                    .clickable {
                        val sessionId = viewModel.startBaking(recipe, scaleFactor)
                        onStartBaking(sessionId)
                    }
                    .drawBehind {
                        drawRoundRect(colors.espresso, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Начать выпечку",
                    color = colors.paper,
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                "таймер поведёт за руку, шаг за шагом",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            // Просьба Полины (2026-07-21, помнить и не убирать): весь рецепт должен
            // быть написан целиком, книжным текстом, прямо на этой странице —
            // для тех, кто печёт по памяти рецепта, а не по шагам таймера.
            // Источник текста — recipe.timeline, тот же, что видит таймер: так
            // книжная версия не может разойтись с шагами или показать не те цифры.
            // «Правка от руки» (Cycle 4, HandwrittenEdit) — рукописные правки
            // поверх книжного текста; bitmap в internal storage, ключ — recipeId.
            HandwrittenEditSurface(recipeId = recipeId, modifier = Modifier.padding(top = 32.dp)) {
                FullRecipeSection(recipe)
            }
        }

        // «Слипшихся страниц» (Cycle 10) здесь больше нет — см. DESIGN-V4.md,
        // Cycle 12. Механика забирала себе вертикальный жест в полосе 52dp
        // вдоль правого обреза, то есть ровно там, где палец скроллит рецепт,
        // и включалась случайно, без всякого повода со стороны человека.
        if (!calm) {
            // «Книжный жучок» (Cycle 8, BookWorm) — редкий гость на левом поле;
            // живёт на «стекле» viewport-а, поверх текста, но тапы забирает только
            // маленькая мишень самого жучка. Чаще там, где пыльно (daysSinceOpened).
            BookWormOverlay(
                daysSinceOpened = daysSinceOpened,
                modifier = Modifier.matchParentSize(),
            )
        }
        }
    }
}

/**
 * «Библиотечная книга» — DESIGN-V4.md Cycle 6, фича LibraryNotes: заметки
 * других семей, читавших этот же рецепт. Раньше они проступали внутри секции
 * «На полях»; в Cycle 11 своих помет на полях у книги больше нет, и у чужой
 * руки появился свой раздел. Без сети список пуст, и блок молчит целиком —
 * пустых заголовков книга не показывает.
 */
@Composable
private fun LibraryNotesSection(notes: List<LibraryNote>, modifier: Modifier = Modifier) {
    if (notes.isEmpty()) return
    val colors = AppColors.current

    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HairRule(Modifier.weight(1f))
            PageLabel("Из других книг", color = colors.espresso, modifier = Modifier.padding(horizontal = 10.dp))
            HairRule(Modifier.weight(1f))
        }
        Column(Modifier.padding(top = 12.dp)) {
            notes.forEach { note ->
                Column(Modifier.padding(vertical = 4.dp).rotate(note.slantDeg)) {
                    Text(
                        note.text,
                        color = colors.espresso.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Cursive,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    )
                    Text(
                        "— ${note.familyLabel}",
                        color = colors.cocoa.copy(alpha = 0.4f),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
            }
        }
    }
}

/**
 * «Гостевая страница» — DESIGN-V4.md Cycle 7, фича GuestPage: отзывы гостей,
 * оставленные с их телефонов через QR (BakingCompleteScreen) и публичную
 * форму PocketBase. Каждый гость пишет своей рукой: наклон и оттенок чернил
 * детерминированы от имени (GuestPage.slantFor/inkFor). Пока отзывов нет —
 * секция молчит целиком (тот же принцип, что LibraryNotes: без сети книга
 * не ругается и не показывает пустых разделов).
 */
@Composable
private fun GuestNotesSection(notes: List<GuestNote>, modifier: Modifier = Modifier) {
    if (notes.isEmpty()) return
    val colors = AppColors.current
    val inks = listOf(colors.espresso, colors.cocoa)

    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HairRule(Modifier.weight(1f))
            PageLabel("Гостевая страница", color = colors.espresso, modifier = Modifier.padding(horizontal = 10.dp))
            HairRule(Modifier.weight(1f))
        }
        Text(
            "пара слов от тех, кто пробовал этот хлеб за нашим столом",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Column(Modifier.padding(top = 10.dp)) {
            notes.forEach { note ->
                Column(Modifier.padding(vertical = 6.dp).rotate(note.slantDeg)) {
                    Text(
                        note.text,
                        color = inks[note.inkIndex],
                        fontFamily = FontFamily.Cursive,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    )
                    Text(
                        "— ${note.authorLabel}",
                        color = colors.cocoa,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullRecipeSection(recipe: Recipe, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HairRule(Modifier.weight(1f))
            PageLabel("Рецепт целиком", color = colors.espresso, modifier = Modifier.padding(horizontal = 10.dp))
            HairRule(Modifier.weight(1f))
        }
        Text(
            "для тех, кто печёт по-своему — без таймера, по книге",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp),
        )
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
            recipe.timeline.forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                    Text(
                        "${i + 1}",
                        color = colors.flour,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp,
                        modifier = Modifier.width(34.dp),
                    )
                    Column {
                        Text(
                            step.title.uppercase(),
                            color = colors.crust,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            step.description,
                            color = colors.espresso,
                            fontFamily = FontFamily.Serif,
                            fontSize = 15.5.sp,
                            lineHeight = 23.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
        Text(
            "— вот и весь рецепт —",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatsFooter(recipe: Recipe) {
    val colors = AppColors.current
    val hours = recipe.timeline.sumOf { it.durationMinutes } / 60
    val roman = listOf("—", "I", "II", "III", "IV", "V")
    val difficultyColor = when {
        recipe.difficulty <= 2 -> colors.sage
        recipe.difficulty == 3 -> colors.crust
        else -> colors.terracotta
    }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        HeavyRule()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            StatCell("${hours}ч", "время", Modifier.weight(1f))
            StatCell("${recipe.timeline.size}", "шагов", Modifier.weight(1f))
            StatCell(roman.getOrElse(recipe.difficulty) { "—" }, "сложность", Modifier.weight(1f), valueColor = difficultyColor)
        }
        HairRule()
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = AppColors.current.espresso,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            label.uppercase(),
            color = AppColors.current.cocoa,
            fontFamily = FontFamily.SansSerif,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/**
 * «НА СКОЛЬКО ПЕЧЁМ» — pill-паттерн: активная ячейка залита Espresso и шире
 * остальных («×3 семьи»). Единственное место применения референса Yandex Music.
 */
@Composable
private fun PortionSelector(portions: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(modifier) {
        PageLabel("На сколько печём", color = colors.espresso)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .drawBehind {
                    drawRoundRect(
                        colors.espresso,
                        style = Stroke(1.5.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    )
                }
        ) {
            (1..5).forEach { n ->
                val active = n == portions
                Box(
                    Modifier
                        .weight(if (active) 1.5f else 1f)
                        .clickable { onSelect(n) }
                        .drawBehind { if (active) drawRect(colors.espresso) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (active) "×$n ${familyWord(n)}" else "×$n",
                        color = if (active) colors.paper else colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
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

/** Hero-фото как вклеенная фотокарточка: белая рамка, лёгкий поворот. */
@Composable
private fun PastedPhoto(recipe: Recipe, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    val context = LocalContext.current
    val resId = heroResFor(context, recipe.id) ?: return
    Box(
        modifier
            .fillMaxWidth()
            .rotate(-1.2f)
            .drawBehind { drawRect(colors.cream) }
            .padding(8.dp)
    ) {
        // AsyncImage (не rememberAsyncImagePainter+Image) — размер запроса Coil
        // берёт из реальных constraints этого блока (170dp), а не из исходного
        // разрешения hero_*.webp; perf-правка 2026-07-21, жалоба Димы на лаги.
        AsyncImage(
            model = resId,
            contentDescription = "Фото: ${recipe.name}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(170.dp),
        )
    }
}
