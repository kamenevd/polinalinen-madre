package com.polinalinen.madre.ui.razvorot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import com.polinalinen.madre.model.Recipe

internal data class ReadingLeaf(val chapter: Int, val title: Boolean = false, val lines: List<Pair<String,String>> = emptyList(), val anchor: String = "title")

@Composable
fun RazvorotReader(recipes: List<Recipe>, recipeId: String, preferences: ReadingPreferences, onBack: () -> Unit, onBake: (Recipe) -> Unit, onCalculator: (Recipe) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var anchor by rememberSaveable(recipeId) { mutableStateOf("$recipeId:title") }
    val p = LocalPaper.current
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
        val measurer = rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val width = with(density) { (maxWidth-44.dp).roundToPx() }.coerceAtLeast(1)
        val height = with(density) { (maxHeight-210.dp).roundToPx() }.coerceAtLeast(200)
        val style = TextStyle(fontFamily=Literature,fontSize=preferences.textSize.sp,lineHeight=(preferences.textSize*1.5f).sp)
        val leaves = remember(recipes,width,height,preferences.textSize) {
            buildList {
                recipes.forEachIndexed { ci,r ->
                    add(ReadingLeaf(ci,true,anchor="${r.id}:title"))
                    var used=0;var lines=mutableListOf<Pair<String,String>>();var first="ingredients"
                    fun flush() { if(lines.isNotEmpty()) { add(ReadingLeaf(ci,lines=lines.toList(),anchor="${r.id}:$first"));lines=mutableListOf();used=0 } }
                    val blocks=buildList {
                        r.ingredients.forEach { (section,items) -> add(section to items.joinToString("\n") { it.displayText() }) }
                        r.timeline.forEachIndexed { si,step -> add("%02d · %s · %d мин".format(si+1,step.title,step.durationMinutes) to step.description) }
                        if(r.servingTips.isNotEmpty()) add("К столу" to r.servingTips.joinToString("\n") { it.text })
                        if(r.variations.isNotEmpty()) add("Варианты" to r.variations.joinToString("\n") { it.text })
                    }
                    blocks.forEachIndexed { bi,(heading,text) ->
                        // Split at words, measuring actual Cyrillic glyphs at the selected
                        // font scale. No step is truncated to fit a screenshot.
                        var rest=text.split(' ');var continuation=false
                        while(rest.isNotEmpty()) {
                            var take=rest.size
                            fun measured(n:Int)=measurer.measure(heading+"\n"+rest.take(n).joinToString(" "),style,constraints=Constraints(maxWidth=width)).size.height+with(density){30.dp.roundToPx()}
                            if(used+measured(1)>height)flush()
                            while(take>1&&used+measured(take)>height)take--
                            if(lines.isEmpty())first="$bi:${if(continuation)rest.size else 0}"
                            val part=rest.take(take).joinToString(" ")
                            lines.add((heading+if(continuation)" · продолжение" else "") to part)
                            used+=measured(take);rest=rest.drop(take);continuation=true
                            if(rest.isNotEmpty())flush()
                        }
                    };flush()
                }
            }
        }
        var page by rememberSaveable(recipeId) { mutableIntStateOf(0) }
        LaunchedEffect(leaves) { page=leaves.indexOfFirst { it.anchor==anchor }.takeIf { it>=0 } ?: leaves.indexOfFirst { it.anchor.startsWith(recipeId+":") }.coerceAtLeast(0) }
        fun move(index:Int) { page=index.coerceIn(0,(leaves.size-1).coerceAtLeast(0));leaves.getOrNull(page)?.let { anchor=it.anchor } }
        val current=leaves.getOrNull(page)
        val recipe=recipes.getOrNull(current?.chapter?:0)
        if(current!=null && recipe!=null) {
            if(preferences.mode==ReadingMode.SCROLL) {
                LazyColumn(Modifier.fillMaxSize().padding(top=56.dp,bottom=110.dp).testTag("reader-scroll")) {
                    recipes.forEachIndexed { ci,r ->
                        item(key="${r.id}:title") { TitleLeaf(r,ci,recipes.size) }
                        r.ingredients.forEach { (section,items) -> item { LeafBlock(section,items.joinToString("\n") { it.displayText() },preferences) } }
                        r.timeline.forEachIndexed { i,step -> item { LeafBlock("%02d · %s · %d мин".format(i+1,step.title,step.durationMinutes),step.description,preferences) } }
                    }
                }
            } else PaperTurn(page,leaves.size,preferences,{move(it)},Modifier.testTag("paper-turn")) { index ->
                val leaf=leaves[index];val r=recipes[leaf.chapter]
                Column(Modifier.fillMaxSize().background(p.bg).clickable { chrome=!chrome }.padding(top=if(leaf.title)0.dp else 62.dp,bottom=112.dp)) {
                    if(leaf.title) TitleLeaf(r,leaf.chapter,recipes.size)
                    else {
                        SmallLabel("ГЛАВА %02d · %s".format(leaf.chapter+1,r.name.uppercase()),Modifier.padding(horizontal=22.dp,vertical=8.dp))
                        leaf.lines.forEach { (heading,text) -> LeafBlock(heading,text,preferences) }
                    }
                }
            }
            if(chrome) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(p.bg).padding(horizontal=10.dp)) { BookHeader(recipe.name,onBack,{settings=true}) }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.bg).padding(horizontal=20.dp,vertical=8.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { SmallLabel("${page+1} / ${leaves.size}");SmallLabel("ГЛАВА ${current.chapter+1}") }
                    LinearProgressIndicator(progress={ (page+1).toFloat()/leaves.size },modifier=Modifier.fillMaxWidth().padding(vertical=7.dp),color=p.ribbon,trackColor=p.bg2)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        PaperButton("Оглавление",onBack,Modifier.weight(1f))
                        PaperButton("Печь · ×1",{onBake(recipe)},Modifier.weight(1f),primary=true)
                    }
                    TextButton({onCalculator(recipe)},Modifier.fillMaxWidth().heightIn(min=40.dp)) {Text("Порции, расчёт и заметки",fontSize=12.sp)}
                }
            } else Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton({move(page-1)},enabled=page>0) {Text("Назад")};SmallLabel("${page+1}",Modifier.padding(16.dp));TextButton({move(page+1)},enabled=page<leaves.lastIndex){Text("Дальше")}
            }
        } else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
        if(settings)ReadingSettings(preferences){settings=false}
    }
}
@Composable private fun TitleLeaf(recipe: Recipe, chapter: Int, count:Int) {
    val p=LocalPaper.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            Image(painterResource(recipePhoto(recipe.id)),recipe.name,Modifier.fillMaxSize().drawWithContent { drawContent();drawRect(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,p.bg),startY=size.height*.65f)) },contentScale=ContentScale.Crop)
            Ribbon(Modifier.align(Alignment.TopEnd).padding(end=48.dp))
            BookText("%02d".format(chapter+1),94,Modifier.align(Alignment.BottomEnd).padding(end=18.dp),color=p.ink.copy(alpha=.5f))
        }
        Column(Modifier.padding(horizontal=22.dp)) {
            SmallLabel("ГЛАВА %02d ИЗ %02d · %s".format(chapter+1,count,recipe.totalTime()))
            BookText(recipe.name,38,Modifier.testTag("reader-title"))
            BookText(recipe.description,17,Modifier.padding(top=12.dp),italic=true,color=p.secondary)
            Row(Modifier.fillMaxWidth().padding(top=18.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                BookText(recipe.totalTime(),22);BookText("${recipe.timeline.size} шагов",22)
            }
        }
    }
}
@Composable private fun LeafBlock(heading:String,text:String,preferences:ReadingPreferences) {
    Column(Modifier.padding(horizontal=22.dp,vertical=10.dp)) {
        BookText(heading,21,Modifier.padding(bottom=6.dp))
        Text(text,fontFamily=Literature,fontSize=preferences.textSize.sp,lineHeight=(preferences.textSize*1.5f).sp,color=LocalPaper.current.secondary)
    }
}
