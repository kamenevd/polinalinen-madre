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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.polinalinen.madre.navigation.BookPreferences
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
    bookPreferences:BookPreferences?=null,onOpenChapter:((String,Int)->Unit)?=null,
    onStartRecipe:((Recipe)->Unit)?=null,onConfigureRecipe:((Recipe)->Unit)?=null,
    onConfigurePlan:((com.polinalinen.madre.navigation.PlannedBake)->Unit)?=null,
) {
    val account=com.polinalinen.madre.account.yandex.LocalYandexAccountUi.current
    if(account!=null) {
        val offer by account.model.offerPending.collectAsState()
        if(offer) {
            val state by account.model.state.collectAsState()
            val pending by account.model.pendingUnbind.collectAsState()
            com.polinalinen.madre.account.yandex.YandexOfferContent(state,pending,account.signIn,
                account.model::skipOffer,account.model::retryPendingUnbind)
            return
        }
    }
    var section by rememberSaveable{mutableIntStateOf(initialSection)}
    LaunchedEffect(initialSection){section=initialSection}
    val tabStates=rememberSaveableStateHolder()
    var settings by remember{mutableStateOf(false)}
    val p=LocalPaper.current
    val pendingAbandons by viewModel.pendingAbandons.collectAsState()
    val visibleSessions=sessions.filterNot{pendingAbandons.containsKey(it.id)}
    BackHandler(enabled=section!=0){section=0;onSectionChanged(0)}
    Column(Modifier.fillMaxSize().paperBackground()) {
        Box(Modifier.weight(1f).safeDrawingPadding()) {
          tabStates.SaveableStateProvider(section) {
            when(section) {
                0 -> BookTocContent(recipes,visibleSessions,bookPreferences?.bookmark,
                    {id,page->onOpenChapter?.invoke(id,page)?:onOpenRecipe(id)},onSettings,onAa={settings=true})
                1 -> {
                    val completions by viewModel.completions.collectAsState()
                    BookOvenContent(recipes,visibleSessions,bookPreferences?.plans.orEmpty(),onOpenTimer,
                        {recipe->onConfigureRecipe?.invoke(recipe)?:onOpenRecipe(recipe.id)},
                        {recipe->onStartRecipe?.invoke(recipe)?:onOpenRecipe(recipe.id)},
                        {plan->onConfigurePlan?.invoke(plan)?:recipes.firstOrNull{it.id==plan.recipeId}?.let{onConfigureRecipe?.invoke(it)}},
                        onSettings,onAa={settings=true},pendingFinished=completions.filterKeys{id->sessions.none{it.id==id}}.toList())
                }
                2 -> StarterPaper(starterName,day,phase,lastFeedingMillis?.let{com.polinalinen.madre.sourdough.hoursSinceFeeding(it)},hydration,onFeed,onDiary,{settings=true},intervalHours)
                3 -> shelf()
            }
          }
        }
        SpineBar(section){if(it==3&&onOpenShelf!=null)onOpenShelf()else {section=it;onSectionChanged(it)}}
    }
    if(settings)ReadingSettings(preferences){settings=false}
}
@Composable internal fun SpineBar(section:Int,onSelect:(Int)->Unit) {
    val p=LocalPaper.current
    Row(Modifier.fillMaxWidth().background(p.glass).drawBehind{drawLine(p.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.navigationBarsPadding().height(IntrinsicSize.Min).heightIn(min=70.dp).testTag("spine-navigation"),verticalAlignment=Alignment.CenterVertically) {
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
@Composable fun RazvorotTimer(sessionId:Long,viewModel:BakingViewModel,onBack:()->Unit,onComplete:()->Unit,onDetails:()->Unit={},onOpenAlice:()->Unit={},
    onOpenChapter:(BakingSession)->Unit={},onAbandon:(Long)->Unit={},onSwitchSession:(Long)->Unit={}) {
    var more by rememberSaveable(sessionId){mutableStateOf(false)}
    var keepScreenOn by rememberSaveable(sessionId){mutableStateOf(true)}
    Immersive(keepScreenOn = keepScreenOn)
    val sessions by viewModel.sessions.collectAsState()
    val remaining by viewModel.remainingFor(sessionId).collectAsState()
    val session=sessions.find{it.id==sessionId}
    Box(Modifier.fillMaxSize().paperBackground()) {
        if(session==null)Column(Modifier.padding(22.dp)){BookText("Выпечка закрыта",32);PaperButton("К книге",onBack)}
        else BakingPaper(session,remaining,{viewModel.togglePause(sessionId)},{viewModel.advanceStepWithUndo(sessionId, onComplete)},
            {more=true},onBack,onOpenAlice=onOpenAlice,onConfirmPortions={viewModel.confirmPortions(sessionId,it)},
            onAdjustMinutes={viewModel.adjustTime(sessionId,it)},sessions=sessions.filterNot{it.isCompleted},onSwitchSession=onSwitchSession)
    }
    if(more && session!=null)BookBakeMoreSheet(session,keepScreenOn,{keepScreenOn=it},
        {onOpenChapter(session)},{onAbandon(sessionId)},{more=false})
}
