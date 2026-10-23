package com.polinalinen.madre.ui.razvorot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.sourdough.GrowthPhase
import kotlin.math.sin

fun timerDigits(seconds:Long):String { val s=seconds.coerceAtLeast(0);return if(s>=3600) "%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%02d:%02d".format(s/60,s%60) }
@Composable fun BakingPaper(session: BakingSession, remaining:Long, onPause:()->Unit, onNext:()->Unit, onDetails:()->Unit, onBack:()->Unit) {
    var full by rememberSaveable { mutableStateOf(false) }
    if(full) { FullTimer(session,remaining){full=false};return }
    val p=LocalPaper.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
        BookHeader("ПЕЧЬ СЕЙЧАС · ×${session.scaleFactor}",onBack,{})
        BookText(session.recipe.name,31)
        Row(Modifier.fillMaxWidth().padding(top=26.dp),verticalAlignment=Alignment.Bottom) {
            BookText("%02d".format(session.currentStepIndex+1),100)
            BookText("/${session.recipe.timeline.size}",28,Modifier.padding(bottom=12.dp),color=p.secondary)
            Spacer(Modifier.weight(1f));Column(horizontalAlignment=Alignment.End) {SmallLabel("ШАГ");BookText(session.currentStep.title,20,italic=true)}
        }
        val duration=(session.currentStep.durationMinutes*60L).coerceAtLeast(1)
        val progress=(1f-remaining.toFloat()/duration).coerceIn(0f,1f)
        Text(timerDigits(remaining),Modifier.fillMaxWidth().clickable(onClickLabel="Открыть большой таймер"){full=true}.padding(top=12.dp),fontFamily=Manrope,fontWeight=FontWeight((200+progress*500).toInt()),fontSize=72.sp,letterSpacing=(-3).sp,color=p.ink,maxLines=1)
        LinearProgressIndicator(progress={progress},Modifier.fillMaxWidth().padding(vertical=12.dp),color=p.acc,trackColor=p.bg2)
        SmallLabel(if(remaining==0L) "ВРЕМЯ ВЫШЛО" else "ОСТАЛОСЬ ИЗ ${session.currentStep.durationMinutes} МИН")
        val bindings=remember(session.recipe){RecipeScaler.scalableBindings(session.recipe)}
        BookText(RecipeScaler.scaledStepText(session.currentStep.description,session.scaleFactor,bindings),21,Modifier.padding(top=24.dp))
        session.recipe.timeline.getOrNull(session.currentStepIndex+1)?.let { SmallLabel("ДАЛЬШЕ · ${it.title} · ${it.durationMinutes} МИН",Modifier.padding(top=16.dp)) }
        Row(Modifier.fillMaxWidth().padding(top=24.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            PaperButton(if(session.isPaused)"Продолжить" else "Пауза",onPause,Modifier.weight(1f))
            PaperButton(if(session.isLastStep)"Испечено" else "Дальше",onNext,Modifier.weight(1f),true)
        }
        TextButton({full=true},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Большой таймер")}
        TextButton(onDetails,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Все действия · отменить выпечку")}
        Spacer(Modifier.height(16.dp))
    }
}
@Composable fun FullTimer(session:BakingSession,remaining:Long,onClose:()->Unit) {
    val view=LocalView.current
    DisposableEffect(view){val old=view.keepScreenOn;view.keepScreenOn=true;onDispose{view.keepScreenOn=old}}
    BackHandler(onBack=onClose)
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0807)).clickable(onClickLabel="Свернуть таймер",onClick=onClose).safeDrawingPadding()) {
        Canvas(Modifier.fillMaxSize()) {drawRect(Brush.radialGradient(listOf(Color(0x66552A12),Color.Transparent),Offset(size.width*.5f,size.height),size.height*.7f))}
        Column(Modifier.align(Alignment.CenterStart).padding(26.dp)) {
            SmallLabel("${session.currentStep.title.uppercase()} · ШАГ ${session.currentStepIndex+1} ИЗ ${session.recipe.timeline.size}",color=NightPaper.acc)
            Text(if(remaining>=3600)"${remaining/3600}ч" else "%02d".format(remaining/60),fontFamily=Manrope,fontWeight=FontWeight.ExtraLight,fontSize=150.sp,letterSpacing=(-8).sp,color=NightPaper.ink)
            Text(if(remaining>=3600)"%02d:%02d".format(remaining/60%60,remaining%60) else "%02d".format(remaining%60),fontFamily=Manrope,fontWeight=FontWeight.Light,fontSize=100.sp,color=NightPaper.acc)
            BookText(if(session.isPaused)"Пауза" else if(remaining==0L)"Время вышло" else session.recipe.name,24,Modifier.padding(top=20.dp),italic=true,color=NightPaper.ink)
            SmallLabel("ЭКРАН НЕ ГАСНЕТ · КОСНИТЕСЬ, ЧТОБЫ СВЕРНУТЬ",Modifier.padding(top=24.dp),color=NightPaper.secondary)
        }
    }
}
@Composable fun StarterPaper(name:String,day:Int,phase:GrowthPhase,hours:Float?,hydration:Int?,onFeed:()->Unit,onDiary:()->Unit,onSettings:()->Unit) {
    val p=LocalPaper.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
        BookHeader("ЗАКВАСКА · ДЕНЬ $day",onAa=onSettings)
        BookText(name,42);BookText("живая",42,italic=true,color=p.acc)
        SmallLabel("ЛЕВИТО МАДРЕ · ГИДРАТАЦИЯ ${hydration?.let{"$it%"}?:"—"}",Modifier.padding(top=8.dp))
        StarterJar(phase!=GrowthPhase.EMPTY)
        // The jar is an illustration, not a fabricated rise measurement.
        SmallLabel("БАНКА — ИЛЛЮСТРАЦИЯ · ПОДЪЁМ НЕ ИЗМЕРЯЕТСЯ")
        BookText(when(phase){GrowthPhase.EMPTY->"Первое кормление — начало дневника.";GrowthPhase.LAG->"Набираюсь сил после кормления.";GrowthPhase.GROWING->"Время расти. Скоро можно печь.";GrowthPhase.PEAK->"По времени кормления — пора проверить закваску.";else->"Пора проверить, не нужно ли кормление."},22,Modifier.padding(top=14.dp),italic=true)
        SmallLabel(hours?.let{"ПОСЛЕ КОРМЛЕНИЯ · %.1f Ч".format(it)}?:"КОРМЛЕНИЙ ПОКА НЕТ",Modifier.padding(top=20.dp))
        Row(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {PaperButton("Покормить",onFeed,Modifier.weight(1f),true);PaperButton("Дневник",onDiary,Modifier.weight(1f))}
    }
}
@Composable fun StarterJar(filled:Boolean) {
    val p=LocalPaper.current
    Canvas(Modifier.fillMaxWidth().height(265.dp).padding(horizontal=38.dp,vertical=16.dp)) {
        val w=size.width;val h=size.height
        drawRoundRect(Color(0xFF806044),Offset(w*.08f,0f),Size(w*.84f,24.dp.toPx()),CornerRadius(8.dp.toPx()))
        drawRoundRect(p.secondary.copy(alpha=.3f),Offset(w*.1f,28.dp.toPx()),Size(w*.8f,h-28.dp.toPx()),CornerRadius(22.dp.toPx()),style=Stroke(2.dp.toPx()))
        if(filled){
            val dough=Path().apply{moveTo(w*.13f,h*.5f);for(i in 0..30){val x=w*.13f+w*.74f*i/30;lineTo(x,h*.5f+sin(i*.7f)*5.dp.toPx())};lineTo(w*.87f,h*.94f);quadraticTo(w*.5f,h*1.02f,w*.13f,h*.94f);close()}
            drawPath(dough,Brush.verticalGradient(listOf(Color(0xFFE4CFAD),Color(0xFFB49468))))
            repeat(36){i->val x=w*(.18f+(i*37%67)/100f);val y=h*(.55f+(i*19%35)/100f);drawCircle(Color(0xFFF8ECD7).copy(alpha=.65f),(2+i%4).dp.toPx(),Offset(x,y),style=Stroke(1.dp.toPx()))}
        }
        drawRoundRect(Color.White.copy(alpha=.3f),Offset(w*.16f,h*.17f),Size(5.dp.toPx(),h*.65f),CornerRadius(3.dp.toPx()))
    }
}
