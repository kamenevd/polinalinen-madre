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

/** Common chapter/oven start entry. A plan is a reminder, never an automatically started timer. */
@Composable fun BookStartSheet(recipe: Recipe, initialPortions: Int, onStart: (Int) -> Unit,
    onDismiss: () -> Unit, onChapter: (() -> Unit)? = null,
    onPlan: ((Int,Long) -> Unit)? = null, initialMode: Int = 0, existingPlan: PlannedBake? = null,
    onRemovePlan: (() -> Unit)? = null, busy: Boolean = false, error: String? = null, nowMillis: Long? = null) {
    var portions by rememberSaveable(recipe.id,existingPlan?.id) { mutableIntStateOf(initialPortions.coerceIn(1,5)) }
    var mode by rememberSaveable(recipe.id,existingPlan?.id) { mutableIntStateOf(if(existingPlan!=null)1 else initialMode.coerceIn(0,1)) }
    var now by remember(recipe.id,existingPlan?.id,nowMillis){mutableLongStateOf(nowMillis?:System.currentTimeMillis())}
    LaunchedEffect(recipe.id,existingPlan?.id,nowMillis) {
        if(nowMillis==null)while(true){delay(30_000);now=System.currentTimeMillis()}
    }
    val minutes=RecipeScale.totalMinutes(recipe,portions)
    val options=remember(recipe.id,portions,now){BakePlanning.options(minutes,now)}
    var selectedReady by rememberSaveable(recipe.id,existingPlan?.id) { mutableStateOf(existingPlan?.let{Math.addExact(it.startAtMillis,minutes*60_000L)}?:options.firstOrNull{it.available}?.readyAtMillis) }
    val selected=options.indexOfFirst{it.readyAtMillis==selectedReady}
    val startAt=if(mode==0)now else selectedReady?.let{Math.subtractExact(it,minutes*60_000L)}
    val readyAt=startAt?.let{Math.addExact(it,Math.multiplyExact(minutes.toLong(),60_000L))}
    val valid=mode==0 || startAt!=null && startAt>=now
    val formatter=remember{DateTimeFormatter.ofPattern("d MMM, HH:mm",Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault())}
    val hands=recipe.timeline.filter{it.type==StepType.ACTION}.sumOf{it.durationMinutes}
    PaperSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SmallLabel("ИСПЕЧЬ")
            BookText(recipe.name,30)
            PaperStepper("×$portions",{if(!busy)portions=(portions-1).coerceAtLeast(1)},
                {if(!busy)portions=(portions+1).coerceAtMost(5)},minusEnabled=!busy && portions>1,plusEnabled=!busy && portions<5)
            RecipeScale.yieldText(recipe,portions)?.let { PaperNote(it) }
            RecipeScale.capacityNote(recipe,portions)?.let { PaperNote(it) }
            if(onPlan!=null)PaperSegmented(listOf("Сейчас","Ко времени"),mode,{if(!busy)mode=it})
            if(mode==1 && onPlan!=null) {
                options.chunked(2).forEachIndexed { row,pair -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { col,option -> PaperChip(option.label,selected==row*2+col,{selectedReady=option.readyAtMillis},
                        Modifier.weight(1f),enabled=option.available && !busy) }
                } }
                if(!valid)PaperNote("На этот срок уже не успеть. Выберите другое время или начните сейчас.")
            }
            PaperCard(Modifier.fillMaxWidth()) {
                if(startAt!=null)BookText(if(mode==0)"Начать сейчас" else "Начать ${formatter.format(Instant.ofEpochMilli(startAt))}",22)
                if(readyAt!=null)PaperNote("Готово ${formatter.format(Instant.ofEpochMilli(readyAt))}")
                PaperNote("Руками ${BookCatalog.duration(hands)}, остальное ждём · ${recipe.timeline.size} шагов")
                if(mode==1)PaperNote("Напомним за 10 мин до начала. Таймер начнётся только после «Начать».")
            }
            PaperNote(RecipeScale.TIMING_NOTE)
            if(error!=null)PaperNote(error)
            PaperButton(if(busy)"Сохраняем…" else if(mode==1)"Запланировать" else "Начать · ×$portions",{
                if(mode==0)onStart(portions) else startAt?.let{onPlan?.invoke(portions,it)}
            },Modifier.fillMaxWidth(),primary=true,enabled=!busy && valid)
            if(existingPlan!=null && onRemovePlan!=null)PaperButton("Убрать план",onRemovePlan,Modifier.fillMaxWidth(),enabled=!busy)
            if(onChapter!=null)PaperTextAction("Открыть главу",onChapter,Modifier.fillMaxWidth())
        }
    }
}
