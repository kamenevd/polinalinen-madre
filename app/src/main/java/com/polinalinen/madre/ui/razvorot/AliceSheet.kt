package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.screens.AliceCopy
import com.polinalinen.madre.voice.*
import com.polinalinen.madre.viewmodel.BakingViewModel
import kotlinx.coroutines.launch

enum class AliceSheetMode { UNLINKED, LISTENING, READING, CONFIRM, OFFLINE, ERROR, PHONE_REQUIRED }

internal fun aliceSheetMode(state: VoiceUiState): AliceSheetMode = when {
    state.phoneRequired -> AliceSheetMode.PHONE_REQUIRED
    state.preview != null -> AliceSheetMode.CONFIRM
    state.status == VoiceStatus.CONNECTED -> AliceSheetMode.LISTENING
    state.status == VoiceStatus.OFFLINE -> AliceSheetMode.OFFLINE
    state.status in setOf(VoiceStatus.ERROR, VoiceStatus.REVOKE_PENDING) -> AliceSheetMode.ERROR
    else -> AliceSheetMode.UNLINKED
}

/** Not a simulated voice agent: CONNECTED is only the coordinator's acknowledged poll state. */
@Composable fun AliceSheet(viewModel: BakingViewModel, coordinator: VoiceCoordinator,
    onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val state by coordinator.state.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val selected = sessions.find { it.bakeKey == state.selectedBake }
    val scope = rememberCoroutineScope()
    LaunchedEffect(coordinator) { coordinator.load() }
    var localRead by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    val remaining = if (selected != null) viewModel.remainingFor(selected.id).collectAsState().value else 0L
    val mode = if (localRead && selected != null) AliceSheetMode.READING else aliceSheetMode(state)
    val text = selected?.let { bake -> bake.trustedPortions?.let { portions ->
        RecipeScaler.scaledStepText(bake.currentStep.description, portions.toDouble(), RecipeScaler.scalableBindings(bake.recipe))
    } }
    PaperSheet(onDismiss) {
        AliceSheetContent(mode, state, selected?.recipe?.name ?: sessions.filterNot{it.isCompleted}.singleOrNull()?.recipe?.name, if (localRead) answer else text,
            onConnect = onOpenSettings, onRetry = { localRead = false; coordinator.retryConnection() },
            onRead = { answer = text; localRead = true },
            onRemaining = { answer = "Осталось: ${timerDigits(remaining)}"; localRead = true },
            onNext = { answer = selected?.let { it.recipe.timeline.getOrNull(it.currentStepIndex + 1)?.description ?: "Последний шаг · завершение на телефоне" }; localRead = true },
            paused = selected?.isPaused == true, onPause = { selected?.let { viewModel.togglePause(it.id) } },
            onApprove = { scope.launch { coordinator.approvePair() } }, onReject = coordinator::dismissPairPreview,
            accountStep = { com.polinalinen.madre.account.yandex.YandexAliceAccountStep(onOpenSettings) },hasActiveBakes=sessions.any{!it.isCompleted})
    }
}

/** Stateful data comes from the phone. This composable also serves explicit local screenshot fixtures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AliceSheetContent(mode: AliceSheetMode, state: VoiceUiState,
    recipeName: String? = null, stepText: String? = null,
    onConnect: () -> Unit = {}, onRetry: () -> Unit = {}, onRead: () -> Unit = {},
    onRemaining: () -> Unit = {}, onNext: () -> Unit = {}, paused: Boolean = false,
    onPause: () -> Unit = {}, onApprove: () -> Unit = {}, onReject: () -> Unit = {},
    accountStep: (@Composable () -> Unit)? = null,hasActiveBakes:Boolean=recipeName!=null,verifiedName:String?=null) {
    val p = LocalPaper.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SmallLabel("АЛИСА", color = p.acc)
        BookText(when (mode) {
            AliceSheetMode.UNLINKED -> "Подключить Алису"
            AliceSheetMode.LISTENING -> "Телефон на связи"
            AliceSheetMode.READING -> "Шаг на телефоне"
            AliceSheetMode.CONFIRM -> "Разрешить доступ?"
            AliceSheetMode.OFFLINE, AliceSheetMode.ERROR -> "Нет связи"
            AliceSheetMode.PHONE_REQUIRED -> "На телефоне"
        }, 28)
        if (recipeName != null) Text(recipeName, color = p.ink, style = PaperType.title)
        else Text(if(hasActiveBakes)"Выберите выпечку" else "Сейчас ничего не печётся",color=p.ink2,style=PaperType.note)
        state.message?.let { Text(it, color = p.error, style = PaperType.note) }
        when (mode) {
            AliceSheetMode.UNLINKED -> {
                com.polinalinen.madre.ui.screens.AliceConnectionSteps(
                    waiting=state.status==VoiceStatus.WAITING_LINK || state.status==VoiceStatus.LOADING,
                    onHelp=onConnect,accountStep=accountStep,verifiedName=verifiedName)
                PaperTextAction("ВЫБРАТЬ ВЫПЕЧКУ И РАЗРЕШИТЬ ДОСТУП",onConnect)
            }
            AliceSheetMode.CONFIRM -> {
                Text(AliceCopy.SHARED_WARNING, color = p.ink2, style = PaperType.body)
                PaperButton(label = "Да · разрешить чтение и управление", onClick = onApprove)
                PaperButton(label = "Нет", onClick = onReject, variant = PaperButtonVariant.SECONDARY)
            }
            AliceSheetMode.OFFLINE, AliceSheetMode.ERROR -> {
                Text("Подключение не подтверждено. Таймер и книга работают на телефоне.", color = p.ink2, style = PaperType.body)
                PaperButton(label = "Повторить", onClick = onRetry, variant = PaperButtonVariant.SECONDARY)
                PaperTextAction("Подключение и доступ", onConnect)
            }
            AliceSheetMode.PHONE_REQUIRED -> Text("«Испечено», фото и полка — только на телефоне.", color = p.ink2, style = PaperType.body)
            AliceSheetMode.READING -> {
                var shown by remember(stepText) { mutableIntStateOf(0) }
                LaunchedEffect(stepText) { for (i in 1..(stepText?.length ?: 0)) { shown = i; kotlinx.coroutines.delay(34) } }
                Text(stepText.orEmpty().take(shown), color = p.ink2, style = PaperType.body)
                Text("Это текст выбранного шага на телефоне, не подтверждение речи колонки.", color = p.mut, style = PaperType.note)
            }
            AliceSheetMode.LISTENING -> Text("Откройте навык «${AliceCopy.SKILL_NAME}» на колонке. Статус телефона не означает, что колонка сейчас слушает.", color = p.ink2, style = PaperType.body)
        }
        if (state.status == VoiceStatus.CONNECTED) {
            Text("Эти кнопки показывают шаг и управляют выпечкой на телефоне.", color = p.mut, style = PaperType.note)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperChip("Повтори шаг", false, onRead)
                PaperChip(if (paused) "Продолжить" else "Пауза", false, onPause)
                PaperChip("Сколько осталось?", false, onRemaining)
                PaperChip("Что дальше?", false, onNext)
            }
        }
        Text(AliceCopy.AVAILABILITY, color = p.mut, style = PaperType.note)
        Spacer(Modifier.height(12.dp))
    }
}
