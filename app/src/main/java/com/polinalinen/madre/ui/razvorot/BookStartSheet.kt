package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScale
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.navigation.BakePlanning
import com.polinalinen.madre.navigation.PlannedBake
import com.polinalinen.madre.ui.kit.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import java.time.Clock
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule

/** Common chapter/oven start entry. A plan is a reminder, never an automatically started timer. */
@Composable fun BookStartSheet(recipe: Recipe, initialPortions: Int, onStart: (Int) -> Unit,
    onDismiss: () -> Unit, onChapter: (() -> Unit)? = null,
    onPlan: ((Int,Long) -> Unit)? = null, initialMode: Int = 0, existingPlan: PlannedBake? = null,
    onRemovePlan: (() -> Unit)? = null, busy: Boolean = false, error: String? = null, nowMillis: Long? = null,
    clock:Clock=LocalBookClock.current, bakeNumber:Int?=existingPlan?.number) {
    var portions by rememberSaveable(recipe.id,existingPlan?.id) { mutableIntStateOf(initialPortions.coerceIn(1,5)) }
    var mode by rememberSaveable(recipe.id,existingPlan?.id) { mutableIntStateOf(if(existingPlan!=null)1 else initialMode.coerceIn(0,1)) }
    val actualClock=remember(clock,nowMillis){nowMillis?.let{Clock.fixed(Instant.ofEpochMilli(it),clock.zone)}?:clock}
    val now=rememberBookNow(actualClock)
    var late by remember { mutableStateOf(false) }
    val minutes=RecipeScale.totalMinutes(recipe,portions)
    val options=remember(recipe.id,portions,now,actualClock){BakePlanning.options(minutes,now,actualClock.zone)}
    var selectedReady by rememberSaveable(recipe.id,existingPlan?.id) { mutableStateOf(existingPlan?.let{Math.addExact(it.startAtMillis,minutes*60_000L)}?:options.firstOrNull{it.available}?.readyAtMillis) }
    val selected=options.indexOfFirst{it.readyAtMillis==selectedReady}
    val startAt=if(mode==0)now else selectedReady?.let{Math.subtractExact(it,minutes*60_000L)}
    val readyAt=startAt?.let{Math.addExact(it,Math.multiplyExact(minutes.toLong(),60_000L))}
    val valid=mode==0 || startAt!=null && startAt>=now
    val hands=recipe.timeline.filter{it.type==StepType.ACTION}.sumOf{it.durationMinutes}
    val p=LocalPaper.current
    PaperSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp,vertical=12.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SmallLabel("ИСПЕЧЬ")
            BookText(recipe.name,30)
            bakeNumber?.let { number -> Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                PaperBakeNumber(number,size=BakeNumberSize.Lg,planned=mode==1)
                Column { PaperNote("Получит номер $number");PaperNote("Алисе: «${com.polinalinen.madre.model.BakeNumbers.ordinal(number)}»") }
            } }
            PaperStepper("×$portions",{if(!busy)portions=(portions-1).coerceAtLeast(1)},
                {if(!busy)portions=(portions+1).coerceAtMost(5)},minusEnabled=!busy && portions>1,plusEnabled=!busy && portions<5,caption="семьи")
            RecipeScale.yieldText(recipe,portions)?.let { PaperNote(it) }
            RecipeScale.capacityNote(recipe,portions)?.substringAfter(": ")?.let { PaperNote(it) }
            if(onPlan!=null)PaperSegmented(listOf("Сейчас","Ко времени"),mode,{if(!busy)mode=it})
            if(mode==1 && onPlan!=null) {
                options.chunked(2).forEachIndexed { row,pair -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { col,option -> PaperChip(option.label,selected==row*2+col,{selectedReady=option.readyAtMillis;late=false},
                        Modifier.weight(1f),enabled=option.available && !busy) }
                } }
                if(selected<0 && selectedReady!=null)PaperChip("Готово ${relativeBookTime(selectedReady!!,now,actualClock)}",true,{},enabled=valid)
                if(!valid || late)PaperNote("На этот срок уже не успеть. Выберите другое время или начните сейчас.")
            }
            Column(Modifier.fillMaxWidth().background(p.bg2,RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(startAt!=null)PlanValue("Начать ",if(mode==0)"сейчас" else relativeBookTime(startAt,now,actualClock))
                if(readyAt!=null)PlanValue("Готово ",relativeBookTime(readyAt,now,actualClock))
                PlanValue("Руками ",BookCatalog.duration(hands),", остальное ждём · ${recipe.timeline.size} шагов")
                if(mode==1)PaperNote("Напомним за 10 мин до начала.")
            }
            if(error!=null)PaperNote(error)
            PaperButton(if(busy)"Сохраняем…" else if(mode==1)"Запланировать${bakeNumber?.let{" · №$it"}.orEmpty()}" else "Начать${bakeNumber?.let{" · №$it"}.orEmpty()} · ×$portions",{
                if(mode==0)onStart(portions) else startAt?.let{
                    if(it>=actualClock.millis())onPlan?.invoke(portions,it) else late=true
                }
            },Modifier.fillMaxWidth(),primary=true,enabled=!busy && valid,icon=if(mode==1)Icons.Outlined.Schedule else null)
            if(existingPlan!=null && onRemovePlan!=null)PaperButton("Убрать план",onRemovePlan,Modifier.fillMaxWidth(),enabled=!busy)
            if(onChapter!=null)PaperTextAction("ОТКРЫТЬ ГЛАВУ",onChapter,Modifier.fillMaxWidth())
        }
    }
}
