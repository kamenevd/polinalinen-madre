package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.ui.components.bookAction

/** The approved F shell; all data and mutations belong to existing repositories. */
@Composable fun RazvorotHome(
    recipes:List<Recipe>, sessions:List<BakingSession>, viewModel:BakingViewModel,
    starterName:String, day:Int, phase:GrowthPhase,lastFeedingMillis:Long?,hydration:Int?,
    preferences:ReadingPreferences,onOpenRecipe:(String)->Unit,onOpenTimer:(Long)->Unit,
    onFeed:()->Unit,onDiary:()->Unit,onSettings:()->Unit,shelf:@Composable ()->Unit,
    intervalHours:Int=24,onContinueReading:(BakingSession)->Unit={},
    onOpenShelf:(()->Unit)?=null,initialSection:Int=0,onSectionChanged:(Int)->Unit={},
) {
    var section by rememberSaveable{mutableIntStateOf(initialSection)}
    LaunchedEffect(initialSection){section=initialSection}
    var catalog by rememberSaveable{mutableStateOf(false)}
    var settings by remember{mutableStateOf(false)}
    val p=LocalPaper.current
    BackHandler(enabled=catalog||section!=0){catalog=false;section=0;onSectionChanged(0)}
    Column(Modifier.fillMaxSize().paperBackground().safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            when(section) {
                0 -> if(catalog)ChapterCatalog(recipes,onOpenRecipe,{catalog=false},{settings=true})
                    else CoverPage(recipes,{catalog=true},onSettings,{settings=true}) {
                        sessions.forEach { s ->
                            val remaining by viewModel.remainingFor(s.id).collectAsState()
                            Column(Modifier.fillMaxWidth().padding(top=16.dp).background(p.card,androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).padding(14.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    BookText(timerDigits(remaining),30,color=p.acc)
                                    Column(Modifier.padding(start=14.dp)) {SmallLabel("В ПЕЧИ · ШАГ ${s.currentStepIndex+1} ИЗ ${s.recipe.timeline.size}");BookText(s.recipe.name,18)}
                                }
                                Row {PaperTextButton({onContinueReading(s)},Modifier.weight(1f).heightIn(min=48.dp)){Text("Продолжить чтение")};PaperTextButton({onOpenTimer(s.id)},Modifier.weight(1f).heightIn(min=48.dp)){Text("К таймеру")}}
                            }
                        }
                    }
                1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                    BookHeader("ПЕЧЬ",onAa={settings=true});BookText(if(sessions.isEmpty())"Пора испечь\nновую главу." else "Сейчас\nна кухне",42)
                    sessions.forEach { s ->
                        val remaining by viewModel.remainingFor(s.id).collectAsState()
                        Column(Modifier.fillMaxWidth().clickable{onOpenTimer(s.id)}.padding(vertical=20.dp)) {SmallLabel("ШАГ ${s.currentStepIndex+1} · ${s.currentStep.title}");BookText(s.recipe.name,26);BookText(timerDigits(remaining),48,color=p.acc);PaperButton("Продолжить выпечку",{onOpenTimer(s.id)},primary=true)}
                    }
                    PaperButton("Выбрать рецепт",{section=0;onSectionChanged(0);catalog=true},Modifier.padding(top=24.dp),true)
                }
                2 -> StarterPaper(starterName,day,phase,lastFeedingMillis?.let{com.polinalinen.madre.sourdough.hoursSinceFeeding(it)},hydration,onFeed,onDiary,{settings=true},intervalHours)
                3 -> shelf()
            }
        }
        SpineBar(section){if(it==3&&onOpenShelf!=null)onOpenShelf()else {section=it;onSectionChanged(it)}}
    }
    if(settings)ReadingSettings(preferences){settings=false}
}
@Composable internal fun SpineBar(section:Int,onSelect:(Int)->Unit) {
    val p=LocalPaper.current
    Row(Modifier.fillMaxWidth().background(p.glass).drawBehind{drawLine(p.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.height(IntrinsicSize.Min).heightIn(min=70.dp).testTag("spine-navigation"),verticalAlignment=Alignment.CenterVertically) {
            val labels=listOf("Читать","Печь","Закваска","Полка")
            val icons=listOf(Icons.Outlined.MenuBook,Icons.Outlined.LocalFireDepartment,Icons.Outlined.Science,Icons.Outlined.CollectionsBookmark)
            labels.forEachIndexed { i,label ->
                Box(Modifier.weight(1f).fillMaxHeight().then(bookAction(label){onSelect(i)}).testTag("nav-$i").semantics{selected=i==section},contentAlignment=Alignment.Center) {
                    if(i==section)Ribbon(Modifier.align(Alignment.TopCenter).height(14.dp).width(22.dp))
                    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(top=18.dp,bottom=8.dp)){
                        val tint=if(i==section)p.ink else p.ink2
                        if(i==2)Canvas(Modifier.size(24.dp)) {
                            drawRoundRect(tint,Offset(4.dp.toPx(),6.dp.toPx()),Size(16.dp.toPx(),16.dp.toPx()),CornerRadius(4.dp.toPx()),style=Stroke(1.8.dp.toPx()))
                            drawRoundRect(tint,Offset(3.dp.toPx(),2.dp.toPx()),Size(18.dp.toPx(),4.dp.toPx()),CornerRadius(1.dp.toPx()))
                            drawLine(tint,Offset(5.dp.toPx(),15.dp.toPx()),Offset(19.dp.toPx(),15.dp.toPx()),1.2.dp.toPx())
                        } else Icon(icons[i],null,tint=tint)
                        SmallLabel(label,color=tint)
                    }
                }
            }
    }
}
@Composable fun RazvorotTimer(sessionId:Long,viewModel:BakingViewModel,onBack:()->Unit,onComplete:()->Unit,onDetails:()->Unit) {
    val sessions by viewModel.sessions.collectAsState()
    val remaining by viewModel.remainingFor(sessionId).collectAsState()
    val session=sessions.find{it.id==sessionId}
    Box(Modifier.fillMaxSize().paperBackground().safeDrawingPadding()) {
        if(session==null)Column(Modifier.padding(22.dp)){BookText("Выпечка закрыта",32);PaperButton("К книге",onBack)}
        else BakingPaper(session,remaining,{viewModel.togglePause(sessionId)},{viewModel.advanceStep(sessionId);if(viewModel.session(sessionId)?.isCompleted==true)onComplete()},onDetails,onBack)
    }
}
