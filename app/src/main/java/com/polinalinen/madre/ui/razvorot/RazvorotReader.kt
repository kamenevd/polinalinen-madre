@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.model.BakingSession
import kotlinx.coroutines.delay
import com.polinalinen.madre.ui.screens.RecipeChapterContent
import com.polinalinen.madre.ui.screens.ChapterScrollNavigation
import com.polinalinen.madre.ui.screens.ChapterScrollState
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.HairRule
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

internal data class ReadingLeaf(val chapter: Int, val title: Boolean = false, val lines: List<Pair<String,String>> = emptyList(), val anchor: String = "title", val blocks: List<String> = emptyList(), val end: Boolean = false)

@Composable
fun RazvorotReader(recipes: List<Recipe>, recipeId: String, preferences: ReadingPreferences, onBack: () -> Unit, onBake: (Recipe) -> Unit, onCalculator: (Recipe) -> Unit, initialStep: Int? = null, activeSessions: List<BakingSession> = emptyList(), onContinueBake: (Long) -> Unit = {},
    initialPage: Int = -1, onPageChanged: (Int) -> Unit = {}, scaleFactor: Double? = null,
    onScaleFactor: ((Double) -> Unit)? = null, onSchedule: ((Recipe) -> Unit)? = null,
    onOpenAlice: (() -> Unit)? = null, scrollContent: (@Composable (Recipe, ChapterScrollNavigation) -> Unit)? = null,
    onBookmarkChanged: ((com.polinalinen.madre.navigation.BookBookmark)->Unit)? = null,initialChrome:Boolean=true) {
    var settings by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(initialChrome) }
    var annotations by rememberSaveable(recipeId) { mutableStateOf(false) }
    var edgeMessage by remember { mutableStateOf<String?>(null) }
    val factor = scaleFactor ?: preferences.portions.toDouble()
    val setFactor: (Double) -> Unit = { value -> onScaleFactor?.invoke(value) ?: preferences.choosePortions(value.toFloat()) }
    var anchor by rememberSaveable(recipeId) { mutableStateOf("$recipeId:title") }
    var seekActive by rememberSaveable(recipeId,initialStep,initialPage) { mutableStateOf(initialStep != null || initialPage >= 0) }
    // Own saved viewport/intent beside the route, outside BoxWithConstraints'
    // transient subcomposition. A passive bookmark is not a new seek request.
    var page by rememberSaveable(recipeId) { mutableIntStateOf(0) }
    var scrollRequest by rememberSaveable(recipeId) { mutableLongStateOf(0L) }
    var seekAnchor by rememberSaveable(recipeId) { mutableStateOf("$recipeId:title") }
    // Keep measured chrome beside the saved cursor, outside transient subcomposition.
    // A hidden footer is not measured after recreation; guessed defaults repaginate the same page.
    // Entries are widthDp/density/fontScale/heightDp, so changed geometry cannot reuse stale measurements.
    var savedHeader by rememberSaveable(recipeId) { mutableStateOf(floatArrayOf()) }
    var savedFooter by rememberSaveable(recipeId) { mutableStateOf(floatArrayOf()) }
    val scrollCursor = rememberSaveable(recipeId, saver=ChapterScrollState.Saver) { ChapterScrollState() }
    val p = LocalPaper.current
    Immersive(barsRevealed = chrome, keepScreenOn = LocalBookDisplaySettings.current.keepScreenOn)
    val stableInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val stablePadding = stableInsets.asPaddingValues()
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg)) {
        val measurer = rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        fun chromeHeight(saved:FloatArray,fallback:Dp):Dp =
            if(saved.size==4 && saved[0]==maxWidth.value && saved[1]==density.density && saved[2]==density.fontScale)
                saved[3].dp else fallback
        val headerHeight=chromeHeight(savedHeader,56.dp+stablePadding.calculateTopPadding())
        val footerHeight=chromeHeight(savedFooter,150.dp+stablePadding.calculateBottomPadding())
        val width = with(density) { (maxWidth-52.dp-stablePadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)-stablePadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)).roundToPx() }.coerceAtLeast(1)
        val height = with(density) { (maxHeight-headerHeight-footerHeight-52.dp).roundToPx() }.coerceAtLeast(200)
        val style = PaperType.body.copy(fontSize=preferences.textSize.sp,lineHeight=(preferences.textSize*25f/16f).sp)
        val leaves = remember(recipes,recipeId,width,height,preferences.textSize,factor,density.fontScale,locale) {
            buildList {
                recipes.forEachIndexed { ci,r ->
                    if (r.id != recipeId) return@forEachIndexed
                    val label="ГЛАВА %02d · %s".format(ci+1,r.name.uppercase(locale))
                    val pageLabelHeight=measurer.measure(label,PaperType.overline,constraints=Constraints(maxWidth=width)).size.height
                    val pageLimit=height+with(density){52.dp.roundToPx()}-pageLabelHeight-with(density){16.dp.roundToPx()}
                    add(ReadingLeaf(ci,true,anchor="${r.id}:title"))
                    var used=0;var lines=mutableListOf<Pair<String,String>>();var first="ingredients";var fragmentIds=mutableListOf<String>()
                    fun flush() { if(lines.isNotEmpty()) { add(ReadingLeaf(ci,lines=lines.toList(),anchor="${r.id}:$first",blocks=fragmentIds.toList()));lines=mutableListOf();fragmentIds=mutableListOf();used=0 } }
                    val bindings=RecipeScaler.scalableBindings(r)
                    val blocks=buildList {
                        r.ingredients.forEach { (section,items) -> add(com.polinalinen.madre.utils.ingredientSectionTitle(section) to items.joinToString("\n") { RecipeScaler.scaledDisplayText(it,factor) }) }
                        r.timeline.forEachIndexed { si,step -> add("%02d · %s · %d мин".format(si+1,step.title,step.durationMinutes) to RecipeScaler.scaledStepText(step.description,factor,bindings)) }
                        if(r.servingTips.isNotEmpty()) add("К столу" to r.servingTips.joinToString("\n") { it.text })
                        if(r.variations.isNotEmpty()) add("Варианты" to r.variations.joinToString("\n") { it.text })
                    }
                    blocks.forEachIndexed { bi,(heading,text) ->
                        // Split at words, measuring actual Cyrillic glyphs at the selected
                        // font scale. No step is truncated to fit a screenshot.
                        var rest=text.split(' ');var continuation=false;var offset=0
                        while(rest.isNotEmpty()) {
                            var take=rest.size
                            val step=heading.take(2).toIntOrNull()!=null
                            val blockWidth=if(step)(width-with(density){54.dp.roundToPx()}).coerceAtLeast(1) else width
                            fun measured(n:Int):Int {
                                val extra=when {
                                    bi==0 && offset==0 -> {
                                        // Measure the actual new title/portion block, not the old guessed height:
                                        // title + its 12dp gap + card's 12dp vertical insets + 12dp below.
                                        val inside=(width-with(density){28.dp.roundToPx()}).coerceAtLeast(1)
                                        val hint=measurer.measure("− / + внизу",PaperType.note,constraints=Constraints(maxWidth=inside))
                                        val left=(inside-hint.size.width).coerceAtLeast(1)
                                        val label=measurer.measure("НА СКОЛЬКО ПЕЧЁМ",PaperType.overline,constraints=Constraints(maxWidth=left))
                                        val quantity=measurer.measure("×${BookCatalog.number(factor)}",PaperType.number,constraints=Constraints(maxWidth=left))
                                        measurer.measure("Ингредиенты",PaperType.h2,constraints=Constraints(maxWidth=width)).size.height+
                                            maxOf(label.size.height+quantity.size.height,hint.size.height)+with(density){48.dp.roundToPx()}
                                    }
                                    bi==r.ingredients.size && offset==0 -> measurer.measure("Как печём",PaperType.h2,
                                        constraints=Constraints(maxWidth=width)).size.height+with(density){16.dp.roundToPx()}
                                    else->0
                                }
                                if(bi<r.ingredients.size) {
                                    val headingHeight=measurer.measure(heading.uppercase(locale),PaperType.overline,constraints=Constraints(maxWidth=blockWidth)).size.height
                                    val rows=rest.take(n).joinToString(" ").lines().sumOf { line ->
                                        val (name,amount)=ingredientDisplayParts(line)
                                        val amountWidth=if(amount.isBlank())0 else measurer.measure(amount,PaperType.ui.copy(fontWeight=FontWeight.Bold)).size.width
                                        maxOf(measurer.measure(name,style,constraints=Constraints(maxWidth=(blockWidth-amountWidth-with(density){8.dp.roundToPx()}).coerceAtLeast(1))).size.height,
                                            if(amount.isBlank())0 else measurer.measure(amount,PaperType.ui).size.height)+with(density){4.dp.roundToPx()}
                                    }
                                    return extra+headingHeight+rows+with(density){18.dp.roundToPx()}
                                }
                                val number=heading.substringBefore(" · ").toIntOrNull()
                                val actual=number?.let{r.timeline.getOrNull(it-1)}
                                val title=actual?.title?:heading
                                val titleStyle=PaperType.body.copy(fontSize=18.sp,lineHeight=(18*1.27f).sp,fontWeight=FontWeight.Medium,
                                    fontStyle=if(actual?.type==com.polinalinen.madre.model.StepType.WAIT)androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal)
                                val titleHeight=measurer.measure(title,titleStyle,constraints=Constraints(maxWidth=blockWidth)).size.height
                                val metaHeight=if(number!=null)measurer.measure(stepOverline(r,number-1).uppercase(locale),PaperType.overline,
                                    constraints=Constraints(maxWidth=blockWidth)).size.height else 0
                                val bodyHeight=measurer.measure(rest.take(n).joinToString(" "),style,constraints=Constraints(maxWidth=blockWidth)).size.height
                                val numeralHeight=if(number!=null)measurer.measure("%02d".format(number),PaperType.body.copy(fontSize=38.sp,
                                    lineHeight=(38*1.27f).sp,fontWeight=FontWeight.ExtraLight)).size.height else 0
                                return extra+maxOf(numeralHeight,titleHeight+metaHeight+bodyHeight+with(density){6.dp.roundToPx()})+with(density){12.dp.roundToPx()}
                            }
                            if(bi<r.ingredients.size && measured(take)<=pageLimit && used+measured(take)>pageLimit)flush()
                            if(used+measured(1)>pageLimit)flush()
                            if(used+measured(take)>pageLimit) {
                                var lo=1;var hi=take
                                while(lo<hi){val mid=(lo+hi+1)/2;if(used+measured(mid)<=pageLimit)lo=mid else hi=mid-1}
                                take=lo
                            }
                            if(lines.isEmpty())first="$bi:$offset"
                            fragmentIds.add("${r.id}:$bi:$offset")
                            val part=rest.take(take).joinToString(" ")
                            lines.add((heading+if(continuation)" · продолжение" else "") to part)
                            used+=measured(take);rest=rest.drop(take);offset+=take;continuation=true
                            if(rest.isNotEmpty())flush()
                        }
                    };flush()
                    add(ReadingLeaf(ci, anchor = "${r.id}:end", end = true))
                }
            }
        }
        LaunchedEffect(leaves) {
            if (leaves.isEmpty()) return@LaunchedEffect
            if (seekActive && initialPage >= 0) {
                page = initialPage.coerceIn(0, leaves.lastIndex)
                anchor = leaves[page].anchor
                seekAnchor = anchor
                scrollRequest++
                seekActive = false
                return@LaunchedEffect
            }
            val target=if(seekActive) "$recipeId:${recipes.firstOrNull{it.id==recipeId}?.ingredients?.size?.plus(initialStep?:0)}:" else anchor.substringBeforeLast(':')+":"
            val offset=if(seekActive)0 else anchor.substringAfterLast(':').toIntOrNull()?:0
            val containing=leaves.withIndex().flatMap{(index,leaf)->leaf.blocks.filter{it.startsWith(target)}.mapNotNull{key->key.substringAfterLast(':').toIntOrNull()?.let{start->Triple(index,start,key)}}}.filter{it.second<=offset}.maxByOrNull{it.second}?.first
            page=leaves.indexOfFirst { it.anchor==anchor && !seekActive }.takeIf { it>=0 }
                ?: containing
                ?: leaves.indexOfFirst { it.anchor.startsWith(recipeId+":") }.coerceAtLeast(0)
            if(seekActive){
                val targetRecipe=recipes.first{it.id==recipeId}
                anchor=initialStep?.takeIf { it in targetRecipe.timeline.indices }?.let { "$recipeId:${targetRecipe.ingredients.size+it}:0" }
                    ?:leaves.getOrNull(page)?.anchor?:anchor
                seekAnchor=anchor
                scrollRequest++
            }
            seekActive=false
        }
        fun move(index:Int) { page=index.coerceIn(0,(leaves.size-1).coerceAtLeast(0));leaves.getOrNull(page)?.let { anchor=it.anchor;seekAnchor=it.anchor };scrollRequest++; if(leaves.getOrNull(page)?.end==true)chrome=true }
        val currentPage by rememberUpdatedState(page)
        val pageCallback by rememberUpdatedState(onPageChanged)
        val bookmarkCallback by rememberUpdatedState(onBookmarkChanged)
        val leaf=leaves.getOrNull(page)
        val stepIndex=leaf?.lines?.firstNotNullOfOrNull { it.first.substringBefore(" · ").toIntOrNull()?.minus(1) }
        val detail=rememberUpdatedState(com.polinalinen.madre.navigation.BookBookmark(recipeId,page,
            if(leaf?.title==true)"Титул главы" else if(leaf?.end==true)"Конец главы" else if(stepIndex!=null)"Как печём" else "Ингредиенты",stepIndex))
        val hasPages by rememberUpdatedState(leaves.isNotEmpty())
        LaunchedEffect(page, leaves) { if(leaves.isNotEmpty()) { if(leaves.getOrNull(page)?.end==true)chrome=true;delay(500);pageCallback(page.coerceIn(0,leaves.lastIndex));bookmarkCallback?.invoke(detail.value) } }
        DisposableEffect(recipeId) { onDispose { if(hasPages){pageCallback(currentPage);bookmarkCallback?.invoke(detail.value)} } }
        LaunchedEffect(edgeMessage) { if(edgeMessage!=null){delay(1500);edgeMessage=null} }
        val current=leaves.getOrNull(page)
        val recipe=recipes.firstOrNull { it.id == recipeId }
        if(current!=null && recipe!=null) {
            if(preferences.mode==ReadingMode.SCROLL || density.fontScale>1.3f || (com.polinalinen.madre.config.FeatureFlags.RECIPE_EDIT && annotations)) {
                Box(Modifier.fillMaxSize().padding(top=headerHeight,bottom=footerHeight)) {
                    val navigation=ChapterScrollNavigation(anchor,scrollRequest,leaves.map{it.anchor},scrollCursor,seekAnchor) { visible ->
                        leaves.indexOfFirst{it.anchor==visible}.takeIf{it>=0}?.let{page=it;anchor=visible}
                    }
                    if(scrollContent!=null)scrollContent(recipe,navigation)
                    else RecipeChapterContent(recipe,factor,preferences.textSize,navigation=navigation,onPortions={onCalculator(recipe)})
                }
            } else PaperTurn(page,leaves.size,preferences,{move(it)},Modifier.testTag("paper-turn"),onCenter={chrome=!chrome},
                contentKey=listOf(recipeId,factor,width,height,preferences.textSize),onGestureStart={chrome=false},
                onEdge={ direction -> chrome=true;edgeMessage=if(direction>0)"Конец главы · «Испечь» — внизу" else "Это титул · назад — к оглавлению" }) { index ->
                val leaf=leaves[index];val r=recipes[leaf.chapter]
                Column(Modifier.fillMaxSize().background(p.bg).semantics { contentDescription = if(leaf.title)r.name else if(leaf.end)"Конец главы ${leaf.chapter+1}. Теперь — испечь." else leaf.lines.joinToString("\n"){it.first+"\n"+it.second} }.padding(top=if(leaf.title)0.dp else headerHeight,bottom=footerHeight)) {
                    if(leaf.title) TitleLeaf(r,leaf.chapter,recipes.size)
                    else if(leaf.end) Column(Modifier.fillMaxWidth().weight(1f).padding(26.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                        SmallLabel("КОНЕЦ ГЛАВЫ %02d".format(leaf.chapter+1))
                        BookText("Теперь — испечь.",28,Modifier.padding(vertical=20.dp),italic=true)
                        PaperNote("«Испечь» внизу · назад — к оглавлению")
                        Ribbon(Modifier.padding(top=28.dp))
                    }
                    else {
                        SmallLabel("ГЛАВА %02d · %s".format(leaf.chapter+1,r.name.uppercase()),Modifier.padding(horizontal=26.dp,vertical=8.dp))
                        leaf.lines.forEachIndexed { lineIndex,(heading,text) ->
                            val key=leaf.blocks.getOrNull(lineIndex).orEmpty().split(':')
                            val bi=key.getOrNull(1)?.toIntOrNull()
                            val first=key.lastOrNull()=="0"
                            LeafBlock(heading,text,preferences,r,factor,
                                onPortions=if(index==page)({onCalculator(r)}) else null,
                                ingredientIntro=bi==0 && first,stepsIntro=bi==r.ingredients.size && first)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            if(!chrome)Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(stableInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                PageFooter(page+1,page+1,leaves.size)
            }
            if(chrome) {
                edgeMessage?.let { Text(it,style=PaperType.note,color=p.acc,modifier=Modifier.align(Alignment.BottomCenter)
                    .padding(start=20.dp,end=20.dp,bottom=footerHeight+8.dp).testTag("chapter-edge").semantics { liveRegion=LiveRegionMode.Polite }) }
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(p.glass).testTag("chapter-header")
                    .onSizeChanged { savedHeader=floatArrayOf(maxWidth.value,density.density,density.fontScale,with(density){it.height.toDp().value}) }
                    .windowInsetsPadding(stableInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    PaperIconButton(onBack,label="В оглавление") { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"В оглавление") }
                    Column(Modifier.weight(1f)) { SmallLabel("ГЛАВА %02d ИЗ %02d".format(current.chapter+1,recipes.size));BookText(recipe.name,17) }
                    if(com.polinalinen.madre.config.FeatureFlags.RECIPE_EDIT)
                        PaperIconButton({if(com.polinalinen.madre.config.FeatureFlags.RECIPE_EDIT)annotations=!annotations},label="Пометки этой главы") { Icon(Icons.Outlined.Edit,"Пометки этой главы") }

                    PaperTextButton({settings=true;chrome=false},label="Как читать") { BookText("Aa",21) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.bg)
                    .onSizeChanged { savedFooter=floatArrayOf(maxWidth.value,density.density,density.fontScale,with(density){it.height.toDp().value}) }
                    .windowInsetsPadding(stableInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal=20.dp,vertical=8.dp)) {

                    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                        SmallLabel("Стр. ${page+1} из ${leaves.size}")
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val dotsScroll=rememberScrollState()
                            val viewport=with(density){maxWidth.roundToPx()}
                            val target=with(density){48.dp.roundToPx()}
                            LaunchedEffect(page,viewport,dotsScroll.maxValue) {
                                dotsScroll.scrollTo((page*target-viewport/2+target/2).coerceIn(0,dotsScroll.maxValue))
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(dotsScroll),horizontalArrangement=Arrangement.Center) {
                                leaves.forEachIndexed { index,_ -> Box(Modifier.size(48.dp).then(bookAction("Страница ${index+1}"){move(index)}).semantics{selected=index==page},contentAlignment=Alignment.Center) {
                                    Box(Modifier.size(if(index==page)18.dp else 6.dp,6.dp).background(if(index==page)p.rib else p.line,androidx.compose.foundation.shape.CircleShape))
                                } }
                            }
                        }
                    }
                    val active=activeSessions.firstOrNull{it.recipe.id==recipe.id}
                    if(active!=null)PaperButton(if(active.isCompleted)"Испечено · вписать" else "В печи · шаг ${active.currentStepIndex+1}",{onContinueBake(active.id)},Modifier.fillMaxWidth(),primary=true)
                    else {
                        val trusted=factor in 1.0..5.0 && factor==factor.toInt().toDouble()
                        val stepper: @Composable ()->Unit = { PaperCompactStepper("×${BookCatalog.number(factor)}",{setFactor((factor-1).coerceAtLeast(1.0))},
                            {setFactor((factor+1).coerceAtMost(5.0))},{onCalculator(recipe)},minusEnabled=factor>1,plusEnabled=factor<5) }
                        if(density.fontScale>1.3f)stepper()
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(density.fontScale<=1.3f)stepper()
                            PaperButton("Испечь · ×${BookCatalog.number(factor)}",{onBake(recipe)},Modifier.weight(1f),primary=true,enabled=trusted,icon=Icons.Outlined.LocalFireDepartment)
                            if(onSchedule!=null)PaperIconButton({onSchedule(recipe)},Modifier.clip(CircleShape).background(p.bg2),label="Испечь ко времени") { Icon(Icons.Outlined.Schedule,"Испечь ко времени",Modifier.size(24.dp)) }
                        }
                        if(!trusted)PaperNote("Для таймера выберите целые порции от 1 до 5")
                    }
                }
            }
        } else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            if(recipes.isEmpty())PaperProgress() else PaperEmpty("Такой главы в книге нет","Вернитесь к оглавлению") { PaperButton("В оглавление",onBack) }
        }
        if(settings)ReadingSettings(preferences){settings=false}
    }
}
@Composable internal fun TitleLeaf(recipe: Recipe, chapter: Int, count:Int) {
    val p=LocalPaper.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.46f)) {
            Image(painterResource(recipePhoto(recipe.id)),recipe.name,Modifier.fillMaxSize().drawWithContent { drawContent();drawRect(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,p.bg),startY=size.height*.65f)) },contentScale=ContentScale.Crop)

            Text("%02d".format(chapter+1),Modifier.align(Alignment.BottomEnd).offset(y=22.dp).padding(end=14.dp),style = PaperType.body.merge(TextStyle(drawStyle=Stroke(width=1.3f))),fontWeight=FontWeight.ExtraLight,fontSize=150.sp,letterSpacing=(-6).sp,color=p.ink)
        }
        Column(Modifier.padding(horizontal=26.dp)) {
            SmallLabel("ГЛАВА %02d ИЗ %02d · %s".format(chapter+1,count,recipe.totalTime()))
            BookText(recipe.name,36,Modifier.testTag("reader-title"),weight=FontWeight.Medium,lineHeight=38.sp)
            BookText(recipe.description,17,Modifier.padding(top=12.dp),italic=true,color=p.ink2)
            HairRule(Modifier.padding(top=18.dp))
            Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {BookText(recipe.totalTime(),22);SmallLabel("время")}
                Column {BookText("${recipe.timeline.size}",22);SmallLabel("шагов")}
                Column {BookText(difficultyLabel(recipe.difficulty),22);SmallLabel("сложность")}
            }
            HairRule()
        }
    }
}
@Composable internal fun PageFooter(number:Int,chapterPage:Int,chapterPages:Int) {
    Box(Modifier.fillMaxWidth().padding(horizontal=26.dp).heightIn(min=24.dp)) {
        SmallLabel("$number",Modifier.align(Alignment.Center).testTag("page-number"))
        SmallLabel("$chapterPage / $chapterPages",Modifier.align(Alignment.CenterEnd).testTag("chapter-page-count"))
    }
}
@Composable internal fun LeafBlock(heading:String,text:String,preferences:ReadingPreferences,recipe:Recipe?=null,
    factor:Double=1.0,onPortions:(()->Unit)?=null,ingredientIntro:Boolean=false,stepsIntro:Boolean=false) {
    if(ingredientIntro)Column(Modifier.padding(horizontal=26.dp)) {
        Text("Ингредиенты",style=PaperType.h2,color=LocalPaper.current.ink,modifier=Modifier.padding(bottom=12.dp))
        PaperPortionSummary("×${BookCatalog.number(factor)}",onPortions,Modifier.padding(bottom=12.dp))
    }
    if(stepsIntro)Text("Как печём",style=PaperType.h2,color=LocalPaper.current.ink,modifier=Modifier.padding(horizontal=26.dp,vertical=8.dp))
    val ingredient=recipe?.ingredients?.keys?.any { com.polinalinen.madre.utils.ingredientSectionTitle(it)==heading.substringBefore(" · продолжение") }==true
    if(ingredient) {
        Column(Modifier.padding(horizontal=26.dp,vertical=6.dp)) {
            SmallLabel(heading,Modifier.padding(bottom=6.dp),color=LocalPaper.current.ink2)
            text.lines().forEach { line -> val (name,amount)=ingredientDisplayParts(line)
                PaperIngredientLine(name,amount,textSize=preferences.textSize) }
        }
        return
    }
    val step=heading.substringBefore(" · ").toIntOrNull()
    val actual=step?.let{recipe?.timeline?.getOrNull(it-1)}
    Row(Modifier.padding(horizontal=26.dp,vertical=6.dp)) {
        if(step!=null)BookText("%02d".format(step),38,Modifier.width(54.dp),color=LocalPaper.current.mut,weight=FontWeight.ExtraLight)
        Column(Modifier.weight(1f)) {
            if(step!=null)SmallLabel(if(actual!=null && recipe!=null)stepOverline(recipe,step-1) else heading.substringAfterLast(" · "))
            BookText(actual?.title ?: if(step!=null)heading.substringAfter(" · ").substringBeforeLast(" · ") else heading,18,Modifier.padding(bottom=6.dp),italic=actual?.type==com.polinalinen.madre.model.StepType.WAIT,weight=FontWeight.Medium)
            Text(text,style = PaperType.body,fontWeight=FontWeight.Normal,fontSize=preferences.textSize.sp,lineHeight=(preferences.textSize*25f/16f).sp,color=LocalPaper.current.ink2)
        }
    }
}
