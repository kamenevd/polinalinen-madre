package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.ui.components.*
import com.polinalinen.madre.voice.*
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.account.yandex.LocalYandexAccountUi
import com.polinalinen.madre.account.yandex.YandexAccountState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Published skill identity is pinned at delivery; not a fictional connection indicator. */
object AliceCopy {
    const val SKILL_NAME = "Голос закваски"
    const val LAUNCH_PHRASE = "Алиса, запусти навык $SKILL_NAME"
    const val READ_CONTROL_WARNING = "Алиса сможет читать текущий шаг выбранной выпечки и управлять её таймером."
    const val SHARED_WARNING = "Любой рядом с общей колонкой сможет узнать ход и управлять только выбранной выпечкой. Другие выпечки, дневник, полка и фото не передаются."
    const val AVAILABILITY = "Нужны интернет и работающая книга на телефоне. Если Android остановит книгу, откройте Мадре снова; старые команды не повторятся."
}

@Composable
fun AliceScreen(viewModel: BakingViewModel, coordinator: VoiceCoordinator, onBack: () -> Unit) {
    if (com.polinalinen.madre.config.FeatureFlags.FAMILY_ROSTER_V2) {
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.polinalinen.madre.MadreApplication
        val roster by app.rosterCoordinator.state.collectAsState()
        val scope = rememberCoroutineScope()
        val account = LocalYandexAccountUi.current
        val accountState = account?.model?.state?.collectAsState()?.value
        RosterAlicePage(roster, onBack, { scope.launch { app.rosterCoordinator.consentFromExplicitTap() } },
            app::disconnectVoice, { scope.launch { app.rosterCoordinator.checkFromExplicitTap() } },
            onContinue = { scope.launch { app.rosterCoordinator.continuePairFromExplicitTap() } },
            identityReady = accountState is YandexAccountState.SignedIn,
            accountStep = { com.polinalinen.madre.account.yandex.YandexAliceAccountStep(onBack) })
        return
    }
    val state by coordinator.state.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    val account = LocalYandexAccountUi.current
    val accountState = account?.model?.state?.collectAsState()?.value
    LaunchedEffect(coordinator) { coordinator.load() }
    AlicePage(state, sessions.filterNot { it.isCompleted }, onBack,
        onSelect = { scope.launch { coordinator.select(it) } },
        onPreview = { scope.launch { coordinator.previewPair(it) } },
        onApprove = { scope.launch { coordinator.approvePair() } },
        onDisconnect = { coordinator.revokeLocally(); scope.launch { coordinator.disconnect() } },
        yandexSignedIn = accountState is YandexAccountState.SignedIn,
        onYandexConnect = { key -> scope.launch { account?.model?.connectAliceFromExplicitTap(key) } },
        verifiedName = (accountState as? YandexAccountState.SignedIn)?.profile?.let { it.displayName.ifBlank { it.login } },
    )
}

@Composable
internal fun AlicePage(state: VoiceUiState, sessions: List<BakingSession>, onBack: () -> Unit,
    onSelect: (String) -> Unit, onPreview: (String) -> Unit, onApprove: () -> Unit, onDisconnect: () -> Unit,
    yandexSignedIn: Boolean = false, onYandexConnect: (String) -> Unit = {}, verifiedName:String?=null) {
    val colors = LocalPaper.current
    var candidateKey by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") } // pairing code is never saved in instance state/history
    var showHelp by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    val candidate = sessions.find { it.bakeKey == candidateKey }
    val selected = sessions.find { it.bakeKey == state.selectedBake }
    val busy = state.status == VoiceStatus.LOADING
    val expiry = state.yandexConsentExpiresAt
    var consentReady by remember(expiry) { mutableStateOf(expiry != null && expiry > System.currentTimeMillis()) }
    LaunchedEffect(expiry) {
        if (expiry != null) { delay((expiry - System.currentTimeMillis()).coerceAtLeast(0L)); consentReady = false }
        // Expiry only changes the visible explanation. It never creates or retries consent.
    }
    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PaperHeader("Алиса на кухне", onBack, overline = "Голос закваски")
            HeavyRule()
            Text(when (state.status) {
                VoiceStatus.UNLINKED -> "Не подключено"
                VoiceStatus.LOADING -> "Проверяем подключение…"
                VoiceStatus.WAITING_LINK -> "Не подключено · выпечка выбрана, завершите подключение навыка"
                VoiceStatus.CONNECTED -> "Телефон на связи · выпечка доступна Алисе"
                VoiceStatus.OFFLINE -> "Телефон не подтвердил связь"
                VoiceStatus.REVOKE_PENDING -> "На телефоне отключено · ждём отзыв доступа"
                VoiceStatus.ERROR -> "Подключение не подтверждено"
            }, color = colors.ink,style = PaperType.body, fontSize = 18.sp)
            if (state.status == VoiceStatus.OFFLINE || state.status == VoiceStatus.ERROR) SmallLabel("НЕТ СВЯЗИ", color = colors.mut)
            state.message?.let { Text(it, color = colors.rib,style = PaperType.body) }
            if (selected != null) {
                PaperBakeNumber(selected.bakeNumber)
                Text("${com.polinalinen.madre.model.BakeNumbers.label(selected.bakeNumber)} · ${selected.recipe.name}\n${selected.currentStep.title}", color = colors.ink,style = PaperType.body)
            }
            if(state.status!=VoiceStatus.CONNECTED && state.status!=VoiceStatus.REVOKE_PENDING)
                AliceConnectionSteps(waiting=state.status==VoiceStatus.WAITING_LINK || busy,
                    onHelp={showHelp=true},verifiedName=verifiedName)
            Text("Что Алиса может",style=PaperType.title,color=colors.ink)
            Text(AliceCopy.READ_CONTROL_WARNING,style=PaperType.note,color=colors.ink2)
            Text("Кто слышит",style=PaperType.title,color=colors.ink)
            Text(AliceCopy.SHARED_WARNING,style=PaperType.note,color=colors.ink2)
            if (state.selectedBake == null && state.status != VoiceStatus.REVOKE_PENDING) {
                Text("Выберите выпечку", color = colors.ink,style = PaperType.title)
                if (sessions.isEmpty()) Text("Сейчас ничего не печётся", color = colors.ink2,style=PaperType.note)
                else {
                    candidate?.let { PaperBakeNumber(it.bakeNumber,size=BakeNumberSize.Sm) }
                    TapCycleRow(label = "Выпечка", value = candidate?.let { "${com.polinalinen.madre.model.BakeNumbers.label(it.bakeNumber)} · ${it.recipe.name}" } ?: "не выбрана",
                        onClick = {
                            val index = sessions.indexOfFirst { it.bakeKey == candidateKey }
                            candidateKey = sessions[(index + 1) % sessions.size].bakeKey
                        })
                    candidate?.let { Text("Шаг: ${it.currentStep.title}", color = colors.ink2,style = PaperType.body) }

                    if (candidate != null && !busy) {
                        if (yandexSignedIn) PaperButton("Подключить Алису", onClick = { onYandexConnect(candidate.bakeKey) })
                        else PaperButton("Разрешить для этой выпечки", onClick = { onSelect(candidate.bakeKey) })
                    } else if (!busy) {
                        Text("Сначала выберите выпечку в строке выше.", color = colors.ink2,style = PaperType.note)
                    }
                }
            }
            if (state.selectedBake != null && state.status != VoiceStatus.CONNECTED) {
                if (yandexSignedIn) {
                    if (consentReady) {
                        PaperNote("Подключение подготовлено на пять минут. Это ещё не связь с Алисой.")
                    } else {
                        PaperButton("Подключить Алису", onClick = { onYandexConnect(state.selectedBake) }, enabled = !busy)
                        if (expiry != null) PaperNote("Время подключения истекло. Для новой попытки нажмите «Подключить Алису».")
                    }
                }
                if(!yandexSignedIn || showHelp || state.preview!=null) {
                Text("Если появился код",style=PaperType.h2,color=colors.ink)
                Text("Откройте окно подключения навыка в приложении Яндекс и введите код здесь. Не произносите код колонке.",style=PaperType.note,color=colors.ink2)
                PaperField(code, onValueChange = { if (it.length <= 10) code = it.uppercase(java.util.Locale.ROOT) },
                    label = "Код из окна подключения", modifier = Modifier.fillMaxWidth())
                PaperButton("Проверить код", onClick = { onPreview(code) }, enabled = code.matches(Regex("[A-Z2-7]{10}")) && !busy,
                    variant = PaperButtonVariant.SECONDARY)
                if (state.preview != null) {
                    Text(AliceCopy.SHARED_WARNING, color = colors.ink,style = PaperType.body)
                    PaperButton("Подтвердить чтение и управление", onClick = { code = ""; onApprove() })
                }
                }
            }
            if (state.selectedBake != null || state.status == VoiceStatus.REVOKE_PENDING) {
                PaperButton(if (state.status == VoiceStatus.REVOKE_PENDING) "Повторить отзыв доступа" else "Отключить Алису",
                    onClick = { code = ""; onDisconnect() }, variant = PaperButtonVariant.SECONDARY, enabled = !busy)
                Text("Для другой выпечки сначала отключите эту и подключите навык заново.", color = colors.ink2,style = PaperType.note)
            }
            Text(AliceCopy.AVAILABILITY,style=PaperType.note,color=colors.ink2)
            PaperTextAction("ЧТО МОЖНО СКАЗАТЬ",{showCommands=!showCommands})
            if(showCommands) {
            Text("Что можно сказать", color = colors.ink,style = PaperType.body, fontSize = 19.sp)
            Text("«Что готовится?» · «Повтори шаг» · «Сколько осталось?» · «Что дальше?» · «Граммовки»", color = colors.ink2,style = PaperType.body)
            Text("«Пауза» · «Продолжи» · «Следующий шаг» · «Предыдущий шаг». Алиса сначала назовёт выпечку и шаг и попросит подтверждение.", color = colors.ink2,style = PaperType.body)
            Text("Для отмены: «Отмени выпечку», затем «Подтверждаю отмену». «Стоп» только закрывает навык. «Что дальше?» читает следующий шаг, но не переключает его.", color = colors.ink2,style = PaperType.body)
            Text("«Испечено», фото и полка — только на телефоне. Если ответ о результате неизвестен, проверьте таймер: не повторяйте команду вслепую.", color = colors.ink2,style = PaperType.body)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
