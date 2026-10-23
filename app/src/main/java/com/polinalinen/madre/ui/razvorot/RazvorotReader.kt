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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.model.BakingSession

internal data class ReadingLeaf(val chapter: Int, val title: Boolean = false, val lines: List<Pair<String,String>> = emptyList(), val anchor: String = "title", val blocks: List<String> = emptyList())

@Composable
fun RazvorotReader(recipes: List<Recipe>, recipeId: String, preferences: ReadingPreferences, onBack: () -> Unit, onBake: (Recipe) -> Unit, onCalculator: (Recipe) -> Unit, initialStep: Int? = null, activeSessions: List<BakingSession> = emptyList(), onContinueBake: (Long) -> Unit = {}) {
    var settings by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(false) }
    var anchor by rememberSaveable(recipeId) { mutableStateOf("$recipeId:title") }
    var seekActive by rememberSaveable(recipeId,initialStep) { mutableStateOf(initialStep != null) }
    val p = LocalPaper.current
    Immersive(barsRevealed = chrome)
    val stableInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val stablePadding = stableInsets.asPaddingValues()
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg)) {
        val measurer = rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val width = with(density) { (maxWidth-52.dp-stablePadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)-stablePadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)).roundToPx() }.coerceAtLeast(1)
        val height = with(density) { (maxHeight-160.dp-stablePadding.calculateTopPadding()-stablePadding.calculateBottomPadding()).roundToPx() }.coerceAtLeast(200)
        val style = PaperType.body.copy(fontSize=preferences.textSize.sp,lineHeight=(preferences.textSize*25f/16f).sp)
        val leaves = remember(recipes,width,height,preferences.textSize,preferences.portions,density.fontScale) {
            buildList {
                recipes.forEachIndexed { ci,r ->
                    add(ReadingLeaf(ci,true,anchor="${r.id}:title"))
                    var used=0;var lines=mutableListOf<Pair<String,String>>();var first="ingredients";var fragmentIds=mutableListOf<String>()
                    fun flush() { if(lines.isNotEmpty()) { add(ReadingLeaf(ci,lines=lines.toList(),anchor="${r.id}:$first",blocks=fragmentIds.toList()));lines=mutableListOf();fragmentIds=mutableListOf();used=0 } }
                    val bindings=RecipeScaler.scalableBindings(r)
                    val blocks=buildList {
                        r.ingredients.forEach { (section,items) -> add(section to items.joinToString("\n") { RecipeScaler.scaledDisplayText(it,preferences.portions.toDouble()) }) }
                        r.timeline.forEachIndexed { si,step -> add("%02d · %s · %d мин".format(si+1,step.title,step.durationMinutes) to RecipeScaler.scaledStepText(step.description,preferences.portions.toDouble(),bindings)) }
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
                            fun measured(n:Int)=measurer.measure(heading,style.copy(fontSize=18.sp,lineHeight=25.sp),constraints=Constraints(maxWidth=blockWidth)).size.height+measurer.measure(rest.take(n).joinToString(" "),style,constraints=Constraints(maxWidth=blockWidth)).size.height+with(density){36.dp.roundToPx()}
                            if(used+measured(1)>height)flush()
                            if(used+measured(take)>height) {
                                var lo=1;var hi=take
                                while(lo<hi){val mid=(lo+hi+1)/2;if(used+measured(mid)<=height)lo=mid else hi=mid-1}
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
                }
            }
        }
        var page by rememberSaveable(recipeId) { mutableIntStateOf(0) }
        LaunchedEffect(leaves) {
            val target=if(seekActive) "$recipeId:${recipes.firstOrNull{it.id==recipeId}?.ingredients?.size?.plus(initialStep?:0)}:" else anchor.substringBeforeLast(':')+":"
            val offset=if(seekActive)0 else anchor.substringAfterLast(':').toIntOrNull()?:0
            val containing=leaves.withIndex().flatMap{(index,leaf)->leaf.blocks.filter{it.startsWith(target)}.mapNotNull{key->key.substringAfterLast(':').toIntOrNull()?.let{start->Triple(index,start,key)}}}.filter{it.second<=offset}.maxByOrNull{it.second}?.first
            page=leaves.indexOfFirst { it.anchor==anchor && !seekActive }.takeIf { it>=0 }
                ?: containing
                ?: leaves.indexOfFirst { it.anchor.startsWith(recipeId+":") }.coerceAtLeast(0)
            if(seekActive)anchor=leaves.getOrNull(page)?.anchor?:anchor
            seekActive=false
        }
        fun move(index:Int) { page=index.coerceIn(0,(leaves.size-1).coerceAtLeast(0));leaves.getOrNull(page)?.let { anchor=it.anchor } }
        val current=leaves.getOrNull(page)
        val recipe=recipes.getOrNull(current?.chapter?:0)
        if(current!=null && recipe!=null) {
            if(preferences.mode==ReadingMode.SCROLL) {
                LazyColumn(Modifier.fillMaxSize().padding(top=56.dp,bottom=110.dp).testTag("reader-scroll")) {
                    recipes.forEachIndexed { ci,r ->
                        item(key="${r.id}:title") { TitleLeaf(r,ci,recipes.size) }
                        r.ingredients.forEach { (section,items) -> item { LeafBlock(section,items.joinToString("\n") { it.displayText() },preferences) } }
                        r.timeline.forEachIndexed { i,step -> item { LeafBlock("%02d · %s · %d мин".format(i+1,step.title,step.durationMinutes),step.description,preferences,r) } }
                    }
                }
            } else PaperTurn(page,leaves.size,preferences,{move(it)},Modifier.testTag("paper-turn"),onCenter={chrome=!chrome}) { index ->
                val leaf=leaves[index];val r=recipes[leaf.chapter]
                Column(Modifier.fillMaxSize().background(p.bg).clearAndSetSemantics { contentDescription = if(leaf.title)r.name else leaf.lines.joinToString("\n"){it.first+"\n"+it.second} }.then(if (leaf.title) Modifier else Modifier.windowInsetsPadding(stableInsets)).padding(top=if(leaf.title)0.dp else 46.dp,bottom=44.dp)) {
                    if(leaf.title) TitleLeaf(r,leaf.chapter,recipes.size)
                    else {
                        SmallLabel("ГЛАВА %02d · %s".format(leaf.chapter+1,r.name.uppercase()),Modifier.padding(horizontal=26.dp,vertical=8.dp))
                        leaf.lines.forEach { (heading,text) -> LeafBlock(heading,text,preferences,r) }
                    }
                    Spacer(Modifier.weight(1f))
                    PageFooter(index+1,leaves.take(index+1).count{it.chapter==leaf.chapter},leaves.count{it.chapter==leaf.chapter})
                }
            }
            if(chrome) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(p.bg).windowInsetsPadding(stableInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal=10.dp)) { BookHeader(recipe.name,onBack,{settings=true}) }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.bg).windowInsetsPadding(stableInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal=20.dp,vertical=8.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { SmallLabel("${page+1} / ${leaves.size}");SmallLabel("ГЛАВА ${current.chapter+1}") }
                    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                        drawLine(p.bg2,Offset(0f,size.height/2),Offset(size.width,size.height/2),3.dp.toPx())
                        drawLine(p.ribbon,Offset(0f,size.height/2),Offset(size.width*(page+1)/leaves.size,size.height/2),3.dp.toPx())
                        leaves.forEachIndexed { i,leaf -> if(leaf.title) {val x=size.width*i/leaves.size;drawLine(p.mut,Offset(x,3.dp.toPx()),Offset(x,size.height-3.dp.toPx()),1.dp.toPx())} }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        PaperButton("Оглавление",onBack,Modifier.weight(1f))
                        val active=activeSessions.firstOrNull{it.recipe.id==recipe.id}
                        PaperButton(active?.let{"Печь · шаг ${it.currentStepIndex+1}"}?:"Печь · ×${preferences.portions.toInt()}",{if(active!=null)onContinueBake(active.id)else onBake(recipe)},Modifier.weight(1f),primary=true)
                    }
                    PaperTextButton({onCalculator(recipe)},Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Расчёт и заметки",fontSize=12.sp)}
                }
            }
        } else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { PaperProgress() }
        if(settings)ReadingSettings(preferences){settings=false;chrome=false}
    }
}
@Composable internal fun TitleLeaf(recipe: Recipe, chapter: Int, count:Int) {
    val p=LocalPaper.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.46f)) {
            Image(painterResource(recipePhoto(recipe.id)),recipe.name,Modifier.fillMaxSize().drawWithContent { drawContent();drawRect(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,p.bg),startY=size.height*.65f)) },contentScale=ContentScale.Crop)
            Ribbon(Modifier.align(Alignment.TopEnd).padding(end=48.dp))
            Text("%02d".format(chapter+1),Modifier.align(Alignment.BottomEnd).offset(y=22.dp).padding(end=14.dp),style = PaperType.body.merge(TextStyle(drawStyle=Stroke(width=1.3f))),fontWeight=FontWeight.ExtraLight,fontSize=150.sp,letterSpacing=(-6).sp,color=p.ink)
        }
        Column(Modifier.padding(horizontal=26.dp)) {
            SmallLabel("ГЛАВА %02d ИЗ %02d · %s".format(chapter+1,count,recipe.totalTime()))
            BookText(recipe.name,36,Modifier.testTag("reader-title"),weight=FontWeight.Medium,lineHeight=38.sp)
            BookText(recipe.description,17,Modifier.padding(top=12.dp),italic=true,color=p.ink2)
            Row(Modifier.fillMaxWidth().padding(top=18.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {BookText(recipe.totalTime(),22);SmallLabel("время")}
                Column {BookText("${recipe.timeline.size}",22);SmallLabel("шагов")}
                Column {BookText(difficultyLabel(recipe.difficulty),22);SmallLabel("сложность")}
            }
        }
    }
}
@Composable internal fun PageFooter(number:Int,chapterPage:Int,chapterPages:Int) {
    Box(Modifier.fillMaxWidth().padding(horizontal=26.dp).heightIn(min=24.dp)) {
        SmallLabel("$number",Modifier.align(Alignment.Center).testTag("page-number"))
        SmallLabel("$chapterPage / $chapterPages",Modifier.align(Alignment.CenterEnd).testTag("chapter-page-count"))
    }
}
@Composable internal fun LeafBlock(heading:String,text:String,preferences:ReadingPreferences,recipe:Recipe?=null) {
    val step=heading.substringBefore(" · ").toIntOrNull()
    val actual=step?.let{recipe?.timeline?.getOrNull(it-1)}
    Row(Modifier.padding(horizontal=26.dp,vertical=10.dp)) {
        if(step!=null)BookText("%02d".format(step),38,Modifier.width(54.dp),color=LocalPaper.current.mut,weight=FontWeight.ExtraLight)
        Column(Modifier.weight(1f)) {
            if(step!=null)SmallLabel(if(actual!=null && recipe!=null)stepOverline(recipe,step-1) else heading.substringAfterLast(" · "))
            BookText(actual?.title ?: if(step!=null)heading.substringAfter(" · ").substringBeforeLast(" · ") else heading,18,Modifier.padding(bottom=6.dp),italic=actual?.type==com.polinalinen.madre.model.StepType.WAIT,weight=FontWeight.Medium)
            Text(text,style = PaperType.body,fontWeight=FontWeight.Normal,fontSize=preferences.textSize.sp,lineHeight=(preferences.textSize*25f/16f).sp,color=LocalPaper.current.ink2)
        }
    }
}
