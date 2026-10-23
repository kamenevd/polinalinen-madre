package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.ui.kit.*
import kotlinx.coroutines.delay

@Composable fun BookBakeSessionChips(sessions: List<BakingSession>, selected: Long, onSelect: (Long) -> Unit) {
    if(sessions.isEmpty() || sessions.size==1 && sessions.single().id==selected)return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        sessions.forEach { session -> key(session.id) {
            var remaining by remember(session.id,session.revision) { mutableLongStateOf(session.remainingSeconds(BakingClock.elapsed())) }
            LaunchedEffect(session.id,session.revision) {
                while(true){remaining=session.remainingSeconds(BakingClock.elapsed());delay(1000)}
            }
            PaperChip("${session.recipe.name} · ${if(session.isCompleted)"Испечено" else timerDigits(remaining)}",session.id==selected,{onSelect(session.id)})
        } }
    }
}

@Composable fun BookBakeMoreSheet(session: BakingSession, keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit,
    onChapter: () -> Unit, onAbandon: () -> Unit, onDismiss: () -> Unit) {
    PaperSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SmallLabel("ВЫПЕЧКА")
            BookText(session.recipe.name,28)
            PaperListRow("Открыть главу на этом шаге",onClick={onDismiss();onChapter()})
            PaperListRow("Не гасить экран",onClick={onKeepScreenOn(!keepScreenOn)},
                trailing={PaperSwitch(keepScreenOn,onKeepScreenOn)})
            SmallLabel("ВСЕ ШАГИ · ТОЛЬКО ПРОСМОТР")
            session.recipe.timeline.forEachIndexed { index,step ->
                PaperListRow("${index+1}. ${step.title}",note="${step.durationMinutes} мин",value=if(index==session.currentStepIndex)"Сейчас" else null)
            }
            PaperTextAction("Бросить выпечку",{onDismiss();onAbandon()},destructive=true)
            PaperNote("8 секунд, чтобы вернуть. До этого таймер продолжает идти, клякса не появляется.")
        }
    }
}
