package com.polinalinen.madre.ui.screens

import kotlinx.coroutines.launch

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScale
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.DottedLeaderRow
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HandwrittenEditSurface
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.TapCycle
import com.polinalinen.madre.ui.components.TapCycleRow
import com.polinalinen.madre.ui.components.CoffeeRing
import com.polinalinen.madre.ui.components.DustLayer
import com.polinalinen.madre.ui.components.coffeeRings
import com.polinalinen.madre.ui.components.crumbs
import com.polinalinen.madre.ui.components.dustLayer
import com.polinalinen.madre.ui.components.wornPage
import com.polinalinen.madre.ui.kit.LocalCalmMode
import com.polinalinen.madre.utils.heroResFor
import com.polinalinen.madre.utils.ingredientSectionTitle
import com.polinalinen.madre.viewmodel.BakingViewModel

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
    onOpenAlice: () -> Unit = {},
    embedded: Boolean = false,
    scaleFactorOverride: Double? = null,
    textSize: Float = 16f,
    chapterScrollNavigation: ChapterScrollNavigation? = null,
) {
    val startScope = androidx.compose.runtime.rememberCoroutineScope()
    val mutationError by viewModel.mutationError.collectAsState()
    val colors = LocalPaper.current
    val recipes by viewModel.recipes.collectAsState()
    val recipe = recipes.find { it.id == recipeId }
    // Книга ещё раскрывается (recipes.json читается с диска) — это не то же
    // самое, что «такой главы нет». Раньше оба случая давали пустой экран.
    if (recipe == null) {
        MissingRecipePage(loading = recipes.isEmpty(), onBack = onBack)
        return
    }
    val chapterIndex = recipes.indexOf(recipe) + 1

    // Cycle 14: порции — единственный источник масштаба. Коэффициент, выход,
    // подписи шагов и запись в историю берут его отсюда и только отсюда.
    var portions by rememberSaveable { mutableIntStateOf(RecipeScale.MIN_PORTIONS) }
    val scaleFactor = scaleFactorOverride ?: RecipeScale.factor(portions)

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

    if (embedded) {
        RecipeChapterContent(recipe, scaleFactor, textSize, onOpenAlice, chapterScrollNavigation)
        return
    }

    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
        // Cycle 12, бюджет разворота: не больше ДВУХ живых слоёв на экран.
        // Живой — тот, что либо крутит анимацию каждый кадр, либо слушает
        // палец поверх текста. Здесь их ровно два, и оба только в полном
        // оформлении: пыль (Cycle 8) и крошки (Cycle 7). Бумага при этом
        // остаётся бумагой — износ (Cycle 4) и кофейные круги (Cycle 9)
        // рисуются один раз и никуда не деваются.
        //
        // Ушли отсюда в Cycle 12:
        //  · «Чтение при свече» — полумрак 0.50 поверх всей страницы после
        //    21:00. Рецепт читают на кухне, часто с мукой на руках, и гасить
        //    ему контраст по часам — не уют, а помеха.
        //  · «Книжный жучок» — анимированная мишень поверх строк рецепта;
        //    тап по ней уходил жучку, а не тексту под ним.
        //  · «Страница на просвет» — зеркальный водяной знак под текстом
        //    съедал контраст на самом плотном по тексту экране книги. В
        //    дневнике закваски он остался: там разворот просторнее.
        val calm = LocalCalmMode.current
        Box(
            Modifier
                .fillMaxSize()
                .let {
                    if (calm) it
                    else it
                        .dustLayer(daysSinceOpened, seed = recipeId.hashCode().toLong())
                        .crumbs(bakeCount, seed = recipeId.hashCode().toLong())
                }
                .coffeeRings(coffeeRingCount, seed = recipeId.hashCode().toLong())
                .wornPage(bakeCount, seed = recipeId.hashCode().toLong())
        ) {
        // Cycle 16: LazyColumn вместо Column(verticalScroll). Ключи — стабильные
        // строки, а не индексы: список блоков зависит от того, пришли ли заметки
        // из сети, и по индексу «гостевая страница» однажды получила бы состояние
        // «из других книг».
        LazyColumn(
            modifier = Modifier,
            // Тот же нижний отступ, что раньше давал .padding(bottom) внутри
            // скролла: он едет вместе с контентом, а не подрезает viewport.
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "mutation-error") { mutationError?.let { Text(it, color = LocalPaper.current.error, style = PaperType.note, modifier = Modifier.padding(22.dp)) } }
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BackLabel("Оглавление", onClick = onBack)
                    PageLabel("Рецепт № %02d".format(chapterIndex))
                }
            }

            item(key = "title") {
                Text(
                    recipe.name,
                    color = colors.ink,style = PaperType.body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }

            item(key = "description") {
                Text(
                    recipe.description,
                    color = colors.ink2,style = PaperType.body,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                )
            }

            item(key = "stats") { StatsFooter(recipe) }

            item(key = "portions") {
                PortionSelector(
                    portions = portions,
                    onSelect = { portions = RecipeScale.clampPortions(it) },
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }

            item(key = "scale-notes") { ScaleNotes(recipe = recipe, portions = portions) }

            item(key = "photo") {
                PastedPhoto(recipe, Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
            }

            // Секция ингредиентов — один item целиком, а не item на строку:
            // отточия внутри секции меряются по её ширине, и разрезать их
            // на самостоятельные элементы значит менять вёрстку ради оптики.
            recipe.ingredients.forEach { (section, items) ->
                item(key = "ingredients-$section") {
                    val sectionTitle = ingredientSectionTitle(section)
                    PageLabel(sectionTitle, Modifier.padding(start = 22.dp, top = 14.dp), color = colors.ink)
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
                        items.forEach { ingredient ->
                            val text = RecipeScaler.scaledDisplayText(ingredient, scaleFactor)
                            // Каждая строка пересчитывается от текущего scaleFactor —
                            // старых значений на странице не остаётся ни в одной.
                            // Отточие: имя слева, граммы справа. Если формат нераздельный — одной строкой.
                            val parts = text.split(" г ", limit = 2)
                            if (parts.size == 2 && parts[0].toDoubleOrNull() != null) {
                                DottedLeaderRow(name = parts[1], value = "${parts[0]} г")
                            } else {
                                Text(
                                    text,
                                    color = colors.ink,style = PaperType.body,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "cta") {
                Spacer(Modifier.height(14.dp))
                // Что именно сейчас начнётся — прямо над кнопкой. Порции выбирают
                // в начале страницы, а нажимают «Начать выпечку» в конце, через
                // весь список ингредиентов: без этой строки легко уехать в
                // трёхчасовую выпечку не на ту семью.
                Box(Modifier.padding(horizontal = 22.dp)) {
                    PaperButton(
                        label = "Начать выпечку",
                        onClick = {
                            startScope.launch { viewModel.startBaking(recipe, scaleFactor)?.let(onStartBaking) }
                        },
                        // Часы считаются от плана рецепта, а не от порций: время
                        // этапов от количества семей не зависит (RecipeScale.TIMING_NOTE).
                        caption = "×$portions ${familyWord(portions)} · " +
                            "${RecipeScale.totalMinutes(recipe, portions) / 60} ч · " +
                            "${recipe.timeline.size} шагов — таймер поведёт за руку",
                    )
                }
            }

            if (com.polinalinen.madre.config.FeatureFlags.ALICE) item(key = "alice") {
                PaperAliceButton(onOpenAlice, Modifier.fillMaxWidth().padding(horizontal = 22.dp))
            }

            // Просьба Полины (2026-07-21, помнить и не убирать): весь рецепт должен
            // быть написан целиком, книжным текстом, прямо на этой странице —
            // для тех, кто печёт по памяти рецепта, а не по шагам таймера.
            // Источник текста — recipe.timeline, тот же, что видит таймер: так
            // книжная версия не может разойтись с шагами или показать не те цифры.
            // «Правка от руки» (Cycle 4, HandwrittenEdit) — рукописные правки
            // поверх книжного текста; bitmap в internal storage, ключ — recipeId.
            //
            // Cycle 16: весь блок — ровно один item, и разбивать его по шагам
            // нельзя. Рукописные штрихи хранятся в долях от размера этой самой
            // поверхности (HandwrittenEdit, normalize/denormalize по matchParentSize):
            // разрезав текст на элементы, мы поменяли бы систему координат, и
            // всё уже написанное Полиной съехало бы по странице.
            item(key = "full-recipe") {
                HandwrittenEditSurface(recipeId = recipeId, modifier = Modifier.padding(top = 32.dp)) {
                    FullRecipeSection(recipe, scaleFactor)
                }
            }
        }
        }
    }
}

/**
 * Глава не открылась: либо книга ещё раскрывается (recipes.json читается с
 * диска), либо такой главы в ней нет. Раньше оба случая давали пустой белый
 * экран, с которого нельзя было даже уйти назад.
 */
@Composable
private fun MissingRecipePage(loading: Boolean, onBack: () -> Unit) {
    val colors = LocalPaper.current
    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackLabel("Оглавление", onClick = onBack, modifier = Modifier.padding(horizontal = 22.dp))
            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (loading) "книга раскрывается…" else "такой главы в книге нет",
                    color = colors.ink,style = PaperType.body,
                    fontStyle = FontStyle.Italic,
                    fontSize = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** Same chapter renderer in scroll/large-type mode; no second navigation or start button. */
@Composable
fun RecipeChapterContent(recipe: Recipe, scaleFactor: Double, textSize: Float = 16f,
    onOpenAlice: (() -> Unit)? = null, navigation: ChapterScrollNavigation? = null) {
    val colors = LocalPaper.current
    val cursor = navigation?.cursor ?: rememberSaveable(recipe.id,saver=ChapterScrollState.Saver) { ChapterScrollState() }
    val scroll = cursor.scroll
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val anchors = remember(recipe.id, scaleFactor, textSize, fontScale, viewportWidth) { ChapterScrollAnchors() }
    val requested = navigation?.let { (it.pageAnchors + it.anchor).distinct() }.orEmpty()
    val currentNavigation by rememberUpdatedState(navigation)

    val layoutKey = "$viewportWidth:$fontScale:$textSize:$scaleFactor"
    fun top(coordinates: androidx.compose.ui.layout.LayoutCoordinates) =
        (coordinates.positionInRoot().y - viewportTop + scroll.value).toInt()
    fun block(anchor: String) = Modifier.onGloballyPositioned { anchors.block(anchor, top(it)) }
    fun textPosition(key: String, fragments: Map<String, Int>) = Modifier.onGloballyPositioned {
        anchors.textTop(key, top(it), fragments)
    }
    LaunchedEffect(navigation?.request, layoutKey) {
        val target = navigation ?: return@LaunchedEffect
        if (viewportWidth == 0) return@LaunchedEffect
        val restored = cursor.pendingRestore
        val explicitSeek = cursor.processed != target.request
        android.util.Log.d("N2Restore", "request=${target.request} processed=${cursor.processed} pending=$restored stored=${cursor.geometry} layout=$layoutKey value=${scroll.value} max=${scroll.maxValue}")
        if (restored != null && !explicitSeek && cursor.geometry == layoutKey) {
            snapshotFlow { anchors.positions["${recipe.id}:end"] }.filterNotNull().first()
            // Let the newly measured reader chrome/content commit before applying
            // the saved pixels. Do not publish an intermediate viewport bookmark.
            withFrameNanos { }
            android.util.Log.d("N2Restore", "restore before target=$restored value=${scroll.value} max=${scroll.maxValue}")
            scroll.scrollTo(restored)
            android.util.Log.d("N2Restore", "restore after value=${scroll.value} max=${scroll.maxValue}")
            cursor.processed = target.request
            cursor.pendingRestore = null
            return@LaunchedEffect
        }
        if (!explicitSeek && cursor.geometry == layoutKey) return@LaunchedEffect
        val destinationAnchor = if (explicitSeek) target.seekAnchor else target.anchor
        val destination = snapshotFlow { anchors.positions[destinationAnchor] }.filterNotNull().first()
        android.util.Log.d("N2Restore", "anchor target=$destinationAnchor destination=$destination value=${scroll.value} max=${scroll.maxValue}")
        scroll.scrollTo(destination)
        cursor.processed = target.request
        cursor.geometry = layoutKey
        cursor.pendingRestore = null
    }
    LaunchedEffect(scroll, anchors) {
        snapshotFlow {
            val current = currentNavigation
            if (current == null || cursor.pendingRestore != null || current.request != cursor.processed || cursor.geometry != layoutKey) null
            else if (scroll.maxValue > 0 && scroll.value == scroll.maxValue) current.pageAnchors.lastOrNull()
            else current.pageAnchors.mapNotNull { anchor -> anchors.positions[anchor]?.let { anchor to it } }
                .filter { it.second <= scroll.value + 1 }.maxByOrNull { it.second }?.first
        }.filterNotNull().collect { currentNavigation?.onAnchorChanged?.invoke(it) }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { viewportTop = it.positionInRoot().y }
        .onSizeChanged { viewportWidth = it.width }) {
    // One bounded chapter, not the whole book. ScrollState's saver preserves the
    // actual text offset. Keeping FullRecipeSection intact preserves note coordinates.
    Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("reader-scroll").padding(bottom=24.dp)) {
        BookText(recipe.name, 30, Modifier.padding(22.dp).then(block("${recipe.id}:title")))
        PaperNote(recipe.description, Modifier.padding(horizontal = 22.dp))
        recipe.ingredients.entries.forEachIndexed { bi, (section, ingredients) ->
            SmallLabel(ingredientSectionTitle(section), Modifier.padding(horizontal = 22.dp, vertical = 14.dp)
                .then(block("${recipe.id}:$bi:0")))
            val lines=ingredients.map { RecipeScaler.scaledDisplayText(it,scaleFactor) }
            val whole=lines.joinToString("\n")
            var start=0
            lines.forEachIndexed { index,line ->
                val fragments=chapterFragmentCharacters(recipe.id,bi,whole,requested,start,line.length)
                val key="ingredient-$bi-$index"
                Text(line, color = colors.ink2,
                    style = PaperType.body.copy(fontSize = textSize.sp, lineHeight = (textSize * 25f / 16f).sp),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 5.dp).then(textPosition(key,fragments)),
                    onTextLayout={anchors.textLayout(key,it,fragments)})
                start+=line.length+1
            }
        }
        val bindings=remember(recipe){RecipeScaler.scalableBindings(recipe)}
        val stepTexts=recipe.timeline.map{RecipeScaler.scaledStepText(it.description,scaleFactor,bindings)}
        HandwrittenEditSurface(recipe.id, Modifier.padding(top = 32.dp)) {
            FullRecipeSection(recipe, scaleFactor, textSize = textSize,stepModifier={index,body->
                val bi=recipe.ingredients.size+index
                if(!body)block("${recipe.id}:$bi:0") else textPosition("step-$index",
                    chapterFragmentCharacters(recipe.id,bi,stepTexts[index],requested))
            },onStepTextLayout={index,layout->anchors.textLayout("step-$index",layout,
                chapterFragmentCharacters(recipe.id,recipe.ingredients.size+index,stepTexts[index],requested))})
        }
        val appendices=buildList {
            if(recipe.servingTips.isNotEmpty())add("К столу" to recipe.servingTips.map{it.text})
            if(recipe.variations.isNotEmpty())add("Варианты" to recipe.variations.map{it.text})
        }
        appendices.forEachIndexed { index,(title,lines)->
            val bi=recipe.ingredients.size+recipe.timeline.size+index
            SmallLabel(title,Modifier.padding(22.dp).then(block("${recipe.id}:$bi:0")))
            val whole=lines.joinToString("\n");var start=0
            lines.forEachIndexed { part,line->
                val fragments=chapterFragmentCharacters(recipe.id,bi,whole,requested,start,line.length)
                val key="appendix-$index-$part"
                Text(line,style=PaperType.note,color=colors.mut,
                    modifier=Modifier.padding(horizontal=22.dp,vertical=6.dp).then(textPosition(key,fragments)),
                    onTextLayout={anchors.textLayout(key,it,fragments)})
                start+=line.length+1
            }
        }
        if(onOpenAlice!=null)PaperAliceButton(onOpenAlice, Modifier.padding(22.dp))
        BookText("Конец главы. Теперь — испечь.", 24,
            Modifier.padding(22.dp).then(block("${recipe.id}:end")), italic = true)
    }
    }
}

@Composable
private fun FullRecipeSection(recipe: Recipe, scaleFactor: Double, modifier: Modifier = Modifier, textSize: Float = 15.5f,
    stepModifier: (Int,Boolean) -> Modifier = {_,_->Modifier}, onStepTextLayout:(Int,TextLayoutResult)->Unit={_,_->}) {
    val colors = LocalPaper.current
    // Пересчитываются только те числа, которые сам рецепт пометил весом и
    // назвал по имени строки: остальное — проза, и трогать её книга не берётся.
    val quantityBindings = remember(recipe) { RecipeScaler.scalableBindings(recipe) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HairRule(Modifier.weight(1f))
            PageLabel("Рецепт целиком", color = colors.ink, modifier = Modifier.padding(horizontal = 10.dp))
            HairRule(Modifier.weight(1f))
        }
        Text(
            "для тех, кто печёт по-своему — без таймера, по книге",
            color = colors.ink2,style = PaperType.body,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp),
        )
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
            recipe.timeline.forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp).then(stepModifier(i,false))) {
                    Text(
                        "${i + 1}",
                        color = colors.line,style = PaperType.body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp,
                        modifier = Modifier.width(34.dp),
                    )
                    Column {
                        Text(
                            step.title.uppercase(),
                            color = colors.acc,style = PaperType.caption,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            // Cycle 14: граммы внутри книжного текста шага
                            // пересчитаны тем же масштабом, что и список
                            // ингредиентов. Иначе на одной странице стояли бы
                            // два разных рецепта.
                            RecipeScaler.scaledStepText(step.description, scaleFactor, quantityBindings),
                            color = colors.ink,style = PaperType.body,
                            fontSize = textSize.sp,
                            lineHeight = (textSize * 25f / 16f).sp,
                            modifier = Modifier.padding(top = 3.dp).then(stepModifier(i,true)),
                            onTextLayout = {onStepTextLayout(i,it)},
                        )
                    }
                }
            }
        }
        Text(
            "— вот и весь рецепт —",
            color = colors.ink2,style = PaperType.body,
            fontStyle = FontStyle.Italic,
            fontSize = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatsFooter(recipe: Recipe) {
    val colors = LocalPaper.current
    val hours = recipe.timeline.sumOf { it.durationMinutes } / 60
    val roman = listOf("—", "I", "II", "III", "IV", "V")
    val difficultyColor = when {
        recipe.difficulty <= 2 -> colors.sage
        recipe.difficulty == 3 -> colors.acc
        else -> colors.rib
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
    valueColor: androidx.compose.ui.graphics.Color = LocalPaper.current.ink,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor,style = PaperType.body, fontWeight = FontWeight.Medium, fontSize = 20.sp)
        Text(
            label.uppercase(),
            color = LocalPaper.current.ink2,style = PaperType.caption,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/** «На сколько печём» — одна строка-крутилка по кругу 1…5→1. */
@Composable
internal fun PortionSelector(portions: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val options = (RecipeScale.MIN_PORTIONS..RecipeScale.MAX_PORTIONS).toList()
    TapCycleRow(
        label = "На сколько печём",
        value = "×$portions ${familyWord(portions)}",
        modifier = modifier,
        onClick = { onSelect(TapCycle.next(options, portions)) },
    )
}

/**
 * Cycle 14: что именно меняет выбор порций — сказано прямо под селектором.
 *
 * Выход пересчитывается вместе с ингредиентами. Времена — нет, и книга пишет
 * об этом вслух: молчание здесь читалось бы как «расстойка тоже выросла втрое».
 * Ограничение духовки появляется только тогда, когда тесто в неё правда не
 * входит. Граммы выхода — один раз (строка выше); в рамке только противень
 * и число заходов, без повторного «теста выйдет N г».
 */
@Composable
private fun ScaleNotes(recipe: Recipe, portions: Int) {
    val colors = LocalPaper.current
    val yieldText = RecipeScale.yieldText(recipe, portions)
    val capacityNote = RecipeScale.capacityNote(recipe, portions)

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        if (yieldText != null) {
            Text(
                yieldText,
                color = colors.ink,style = PaperType.body,
                fontSize = 14.sp,
            )
        }
        Text(
            RecipeScale.TIMING_NOTE,
            color = colors.ink2,style = PaperType.body,
            fontStyle = FontStyle.Italic,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (capacityNote != null) {
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .drawBehind {
                        drawRoundRect(
                            colors.rib,
                            style = Stroke(1.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                        )
                    }
                    .padding(10.dp),
            ) {
                Text(
                    capacityNote,
                    color = colors.ink,style = PaperType.body,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

private fun familyWord(n: Int) = when (n) {
    1 -> "семья"
    in 2..4 -> "семьи"
    else -> "семей"
}

/** Как ячейка порций называет себя вслух: «печём на 3 семьи». */
internal fun portionLabel(n: Int): String = "печём на $n ${familyWord(n)}"

/** Hero-фото как вклеенная фотокарточка: белая рамка, лёгкий поворот. */
@Composable
private fun PastedPhoto(recipe: Recipe, modifier: Modifier = Modifier) {
    val colors = LocalPaper.current
    val resId = heroResFor(recipe.id) ?: return
    Box(
        modifier
            .fillMaxWidth()
            .rotate(-1.2f)
            .drawBehind { drawRect(colors.card) }
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
