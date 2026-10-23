@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.sourdough.GrowthPhase
import kotlin.math.sin
import kotlin.math.roundToInt

fun timerDigits(seconds:Long):String { val s=seconds.coerceAtLeast(0);return if(s>=3600) "%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%02d:%02d".format(s/60,s%60) }
@Composable fun BakingPaper(session: BakingSession, remaining:Long, onPause:()->Unit, onNext:()->Unit, onDetails:()->Unit, onBack:()->Unit, onOpenAlice:(()->Unit)?=null, onConfirmPortions:(Int)->Unit={},
    onAdjustMinutes:(Int)->Unit={},sessions:List<BakingSession> = emptyList(),onSwitchSession:(Long)->Unit={}) {
    var full by rememberSaveable { mutableStateOf(false) }
    var stationTimer by rememberSaveable(session.id) { mutableStateOf(false) }
    if(full) { FullTimer(session,remaining){full=false};return }
    val p=LocalPaper.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            PaperIconButton(onBack,label="Назад") { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад") }
            Box(Modifier.weight(1f)){BookBakeSessionChips(sessions,session.id,onSwitchSession)}
            PaperIconButton(onDetails,label="Действия выпечки") { Icon(Icons.Outlined.MoreHoriz,"Действия выпечки") }
        }
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
        SmallLabel(session.trustedPortions?.let { "${session.recipe.name} · ×$it СЕМЬИ" } ?: "${session.recipe.name} · ПОРЦИИ НЕ СОХРАНЕНЫ")
        PaperStepRail(session.recipe.timeline.size,session.currentStepIndex,Modifier.fillMaxWidth().padding(top=14.dp))
        Row(Modifier.fillMaxWidth().padding(top=26.dp),verticalAlignment=Alignment.Bottom) {
            BookText("%02d".format(session.currentStepIndex+1),100,weight=FontWeight.ExtraLight)
            BookText("/${session.recipe.timeline.size}",28,Modifier.padding(bottom=12.dp),color=p.mut)
            Spacer(Modifier.weight(1f));Column(Modifier.weight(1f),horizontalAlignment=Alignment.End) {SmallLabel(if(session.currentStep.type==StepType.WAIT)"ЖДЁМ" else "РУКАМИ");BookText(session.currentStep.title,20,italic=session.currentStep.type==StepType.WAIT,weight=FontWeight.Medium)}
        }
        val progress=timerProgress(session.currentStep.durationMinutes*60L,remaining)
        TimerNumber(timerDigits(remaining),progress,92.sp,(-5).sp,p.ink,Modifier.fillMaxWidth().clickable(onClickLabel="Открыть большой таймер"){full=true}.padding(top=12.dp))
        PaperProgress(progress, Modifier.fillMaxWidth().padding(vertical=12.dp))
        SmallLabel(if(remaining==0L) "ВРЕМЯ ВЫШЛО" else "ОСТАЛОСЬ ИЗ ${session.currentStep.durationMinutes} МИН")
        val bindings=remember(session.recipe){RecipeScaler.scalableBindings(session.recipe)}
        FlowRow(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(-5,5,15).forEach { minutes -> PaperChip(if(minutes>0)"+$minutes мин" else "−5 мин",false,{onAdjustMinutes(minutes)},enabled=session.restoredStepValid) }
            PaperChip("Крупно",false,{full=true})
        }
        PaperNote(com.polinalinen.madre.ui.screens.BakingTimerCopy.moodLine(session.currentStep.type==StepType.WAIT,session.isPaused,remaining))
        PaperCard(Modifier.padding(top=16.dp)) {
            PaperStepParagraph(session.trustedPortions?.takeIf { session.restoredStepValid }?.let { RecipeScaler.scaledStepText(session.currentStep.description,it.toDouble(),bindings) } ?: "Порции не сохранены — подтвердите количество для этой выпечки.")
        }
        if (session.trustedPortions == null) com.polinalinen.madre.ui.screens.UnknownPortionsConfirmation(session.id, onConfirmPortions)
        val temperatures=Regex("(\\d{2,3})\\s*[°º]\\s*[cсCС]?").findAll(session.currentStep.description).map{it.groupValues[1]+"°"}.distinct().toList()
        if(temperatures.isNotEmpty())Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {temperatures.forEach{Text(it,Modifier.background(p.bg2,RoundedCornerShape(16.dp)).padding(horizontal=12.dp,vertical=8.dp),style = PaperType.caption,fontWeight=FontWeight.SemiBold,color=p.ink2)}}
        session.recipe.timeline.getOrNull(session.currentStepIndex+1)?.let {
            PaperCard(Modifier.padding(top=16.dp)) {
                SmallLabel("ДАЛЬШЕ · ЧЕРЕЗ ${BookCatalog.duration((remaining/60).toInt())}")
                BookText("${it.title} · ${it.durationMinutes} мин",19)
                if(it.requiresButterPrep && session.currentStep.type==StepType.WAIT && remaining in 1..1800)
                    PaperNote("Скоро понадобится мягкое масло — достаньте его согреться")
            }
        }
        Spacer(Modifier.height(16.dp))
      }
      Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=10.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            PaperButton(if(session.isPaused)"Продолжить" else "Пауза",onPause,Modifier.weight(1f))
            PaperButton(if(session.isLastStep)"Испечено" else "Дальше",onNext,Modifier.weight(1f),true, enabled = !session.isLastStep || session.trustedPortions != null)
        }
        if (onOpenAlice != null && com.polinalinen.madre.config.FeatureFlags.ALICE) PaperAliceButton(onOpenAlice, Modifier.fillMaxWidth())
        if(com.polinalinen.madre.config.FeatureFlags.YANDEX_ID)PaperTextAction("Таймер на Станции",{stationTimer=true})
      }
    }
    if(stationTimer)com.polinalinen.madre.account.yandex.StationTimerSheet(remaining){stationTimer=false}
}
@Composable fun FullTimer(session:BakingSession,remaining:Long,onClose:()->Unit) {
    Immersive(keepScreenOn = true)
    val view=LocalView.current
    val progress=timerProgress(session.currentStep.durationMinutes*60L,remaining)
    DisposableEffect(view){val old=view.keepScreenOn;view.keepScreenOn=true;onDispose{view.keepScreenOn=old}}
    BackHandler(onBack=onClose)
    Box(Modifier.fillMaxSize().background(Illustration.OvenDark).clickable(onClickLabel="Свернуть таймер",onClick=onClose)) {
        Canvas(Modifier.fillMaxSize()) {drawRect(Brush.radialGradient(listOf(Illustration.OvenGlow,Color.Transparent),Offset(size.width*.5f,size.height),size.height*.7f))}
        Column(Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)).padding(26.dp)) {
            SmallLabel("${session.currentStep.title.uppercase()} · ШАГ ${session.currentStepIndex+1} ИЗ ${session.recipe.timeline.size}",color=NightPaper.acc)
            TimerNumber(if(remaining>=3600)"${remaining/3600}ч" else "%02d".format(remaining/60),progress,150.sp,(-8).sp,NightPaper.ink)
            TimerNumber(if(remaining>=3600)"%02d:%02d".format(remaining/60%60,remaining%60) else "%02d".format(remaining%60),progress,100.sp,0.sp,NightPaper.acc)
            BookText(if(session.isPaused)"Пауза" else if(remaining==0L)"Время вышло" else session.recipe.name,24,Modifier.padding(top=20.dp),italic=true,color=NightPaper.ink)
            SmallLabel("ЭКРАН НЕ ГАСНЕТ · КОСНИТЕСЬ, ЧТОБЫ СВЕРНУТЬ",Modifier.padding(top=24.dp),color=NightPaper.ink2)
        }
    }
}
@Composable fun StarterPaper(name:String,day:Int,phase:GrowthPhase,hours:Float?,hydration:Int?,onFeed:()->Unit,onDiary:()->Unit,onSettings:()->Unit,intervalHours:Int=24) {
    val p=LocalPaper.current
    // Returning to this overview starts at its jar, not the old below-fold CTA.
    val scroll=remember { ScrollState(0) }
    Column(Modifier.fillMaxSize().testTag("starter-phase-${phase.name}").verticalScroll(scroll).padding(horizontal=22.dp)) {
        BookHeader("ЗАКВАСКА · ДЕНЬ $day",onAa=onSettings)
        BookText(name,42);BookText("живая",42,italic=true,color=p.acc)
        SmallLabel("ЛЕВИТО МАДРЕ · ГИДРАТАЦИЯ ${hydration?.let{"$it%"}?:"—"}",Modifier.padding(top=8.dp))
        val profile=com.polinalinen.madre.sourdough.profileForInterval(intervalHours)
        val level=starterCycleLevel(hours,profile.peakHours,profile.cycleHours)
        StarterJar(phase!=GrowthPhase.EMPTY,level)
        SmallLabel("УРОВЕНЬ ПО ЦИКЛУ КОРМЛЕНИЯ · НЕ ИЗМЕРЕНИЕ")
        val phaseColor=if(phase==GrowthPhase.PEAK)p.sage else p.acc
        Box(Modifier.padding(top=10.dp).background(phaseColor.copy(alpha=.12f),RoundedCornerShape(18.dp)).padding(horizontal=14.dp,vertical=6.dp)) {
            BookText(when(phase){GrowthPhase.EMPTY->"Пока без кормлений";GrowthPhase.LAG->"Набирается сил";GrowthPhase.GROWING->"Растёт";GrowthPhase.PEAK->"На пике по времени";GrowthPhase.DECLINING->"Пик позади";GrowthPhase.HUNGRY->"Пора покормить"},18,color=phaseColor)
        }
        BookText(when(phase){GrowthPhase.EMPTY->"Первое кормление — начало дневника.";GrowthPhase.LAG->"Набираюсь сил после кормления.";GrowthPhase.GROWING->"Время расти. Скоро можно печь.";GrowthPhase.PEAK->"По времени кормления — пора проверить закваску.";else->"Пора проверить, не нужно ли кормление."},22,Modifier.padding(top=14.dp),italic=true)
        SmallLabel(hours?.let{"ПОСЛЕ КОРМЛЕНИЯ · %.1f Ч".format(it)}?:"КОРМЛЕНИЙ ПОКА НЕТ",Modifier.padding(top=20.dp))
        Row(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {PaperButton("Покормить",onFeed,Modifier.weight(1f),true);PaperButton("Дневник",onDiary,Modifier.weight(1f))}
    }
}
internal fun starterCycleLevel(hours:Float?,peak:Float,cycle:Float):Float {
    if(hours==null)return 0f
    return if(hours<=peak)(.25f+.55f*(hours/peak).coerceIn(0f,1f)) else (.8f-.45f*((hours-peak)/(cycle-peak)).coerceIn(0f,1f))
}
@Composable fun StarterJar(filled:Boolean,level:Float=.5f) {
    val p=LocalPaper.current
    Canvas(Modifier.fillMaxWidth().height(265.dp).testTag("starter-jar").semantics {
        contentDescription="Банка закваски"
        stateDescription="Уровень по циклу: ${(level*100).roundToInt()}%"
    }.padding(horizontal=38.dp,vertical=16.dp)) {
        val w=size.width;val h=size.height
        drawOval(Illustration.JarLid,Offset(w*.06f,0f),Size(w*.88f,20.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(Illustration.JarLid,Illustration.JarLidShadow)),Offset(w*.06f,8.dp.toPx()),Size(w*.88f,18.dp.toPx()),CornerRadius(5.dp.toPx()))
        drawOval(Illustration.JarLidTop,Offset(w*.06f,0f),Size(w*.88f,16.dp.toPx()))
        drawRoundRect(p.ink2.copy(alpha=.3f),Offset(w*.1f,28.dp.toPx()),Size(w*.8f,h-28.dp.toPx()),CornerRadius(22.dp.toPx()),style=Stroke(2.dp.toPx()))
        if(filled){
            val top=h*(.94f-.72f*level.coerceIn(0f,1f))
            val dough=Path().apply{moveTo(w*.13f,top);for(i in 0..30){val x=w*.13f+w*.74f*i/30;lineTo(x,top+sin(i*.7f)*5.dp.toPx())};lineTo(w*.87f,h*.94f);quadraticTo(w*.5f,h*1.02f,w*.13f,h*.94f);close()}
            drawPath(dough,Brush.verticalGradient(listOf(Illustration.JarDough,Illustration.JarDoughShadow)))
            drawLine(p.acc.copy(alpha=.6f),Offset(w*.08f,top),Offset(w*.92f,top),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),4.dp.toPx())))
            repeat(36){i->val x=w*(.18f+(i*37%67)/100f);val y=top+(h*.94f-top)*(.1f+(i*19%80)/100f);drawCircle(Illustration.JarBubble.copy(alpha=.65f),(2+i%4).dp.toPx(),Offset(x,y),style=Stroke(1.dp.toPx()))}
        }
        drawRoundRect(Color.White.copy(alpha=.3f),Offset(w*.16f,h*.17f),Size(5.dp.toPx(),h*.65f),CornerRadius(3.dp.toPx()))
    }
}
