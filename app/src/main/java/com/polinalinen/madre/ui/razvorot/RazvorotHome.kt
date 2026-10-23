package com.polinalinen.madre.ui.razvorot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.viewmodel.BakingViewModel

/** The approved F shell; all data and mutations belong to existing repositories. */
@Composable fun RazvorotHome(
    recipes:List<Recipe>, sessions:List<BakingSession>, viewModel:BakingViewModel,
    starterName:String, day:Int, phase:GrowthPhase,lastFeedingMillis:Long?,hydration:Int?,
    preferences:ReadingPreferences,onOpenRecipe:(String)->Unit,onOpenTimer:(Long)->Unit,
    onFeed:()->Unit,onDiary:()->Unit,onSettings:()->Unit,shelf:@Composable ()->Unit,
) {
    var section by rememberSaveable{mutableIntStateOf(0)}
    var catalog by rememberSaveable{mutableStateOf(false)}
    var settings by remember{mutableStateOf(false)}
    val p=LocalPaper.current
    BackHandler(enabled=catalog||section!=0){catalog=false;section=0}
    Column(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            when(section) {
                0 -> if(catalog)ChapterCatalog(recipes,onOpenRecipe,{catalog=false},{settings=true})
                    else CoverPage(recipes,{catalog=true},onSettings,{settings=true}) {
                        sessions.forEach { s ->
                            val remaining by viewModel.remainingFor(s.id).collectAsState()
                            Row(Modifier.fillMaxWidth().padding(top=16.dp).background(p.card,androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).clickable{onOpenTimer(s.id)}.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                                BookText(timerDigits(remaining),30,color=p.acc)
                                Column(Modifier.padding(start=14.dp)) {SmallLabel("В ПЕЧИ · ШАГ ${s.currentStepIndex+1} ИЗ ${s.recipe.timeline.size}");BookText(s.recipe.name,18)}
                            }
                        }
                    }
                1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                    BookHeader("ПЕЧЬ",onAa={settings=true});BookText(if(sessions.isEmpty())"Пора испечь\nновую главу." else "Сейчас\nна кухне",42)
                    sessions.forEach { s ->
                        val remaining by viewModel.remainingFor(s.id).collectAsState()
                        Column(Modifier.fillMaxWidth().clickable{onOpenTimer(s.id)}.padding(vertical=20.dp)) {SmallLabel("ШАГ ${s.currentStepIndex+1} · ${s.currentStep.title}");BookText(s.recipe.name,26);BookText(timerDigits(remaining),48,color=p.acc);PaperButton("Продолжить выпечку",{onOpenTimer(s.id)},primary=true)}
                    }
                    PaperButton("Выбрать рецепт",{section=0;catalog=true},Modifier.padding(top=24.dp),true)
                }
                2 -> StarterPaper(starterName,day,phase,lastFeedingMillis?.let{com.polinalinen.madre.sourdough.hoursSinceFeeding(it)},hydration,onFeed,onDiary,{settings=true})
                3 -> shelf()
            }
        }
        Row(Modifier.fillMaxWidth().background(p.bg).height(70.dp).testTag("spine-navigation"),verticalAlignment=Alignment.CenterVertically) {
            val labels=listOf("Читать","Печь","Закваска","Полка")
            val icons=listOf(Icons.Outlined.MenuBook,Icons.Outlined.LocalFireDepartment,Icons.Outlined.Science,Icons.Outlined.CollectionsBookmark)
            labels.forEachIndexed { i,label ->
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel=label){section=i}.testTag("nav-$i"),contentAlignment=Alignment.Center) {
                    if(i==section)Ribbon(Modifier.align(Alignment.TopCenter).height(14.dp).width(22.dp))
                    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(top=8.dp)){Icon(icons[i],null,tint=if(i==section)p.ink else p.secondary);SmallLabel(label,color=if(i==section)p.ink else p.secondary)}
                }
            }
        }
    }
    if(settings)ReadingSettings(preferences){settings=false}
}
@Composable fun RazvorotTimer(sessionId:Long,viewModel:BakingViewModel,onBack:()->Unit,onComplete:()->Unit,onDetails:()->Unit) {
    val sessions by viewModel.sessions.collectAsState()
    val remaining by viewModel.remainingFor(sessionId).collectAsState()
    val session=sessions.find{it.id==sessionId}
    Box(Modifier.fillMaxSize().background(LocalPaper.current.bg).safeDrawingPadding()) {
        if(session==null)Column(Modifier.padding(22.dp)){BookText("Выпечка закрыта",32);PaperButton("К книге",onBack)}
        else BakingPaper(session,remaining,{viewModel.togglePause(sessionId)},{viewModel.advanceStep(sessionId);if(viewModel.session(sessionId)?.isCompleted==true)onComplete()},onDetails,onBack)
    }
}
