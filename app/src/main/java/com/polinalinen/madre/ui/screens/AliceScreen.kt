package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.ui.components.*
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.voice.*
import com.polinalinen.madre.viewmodel.BakingViewModel
import kotlinx.coroutines.launch

/** Published skill identity is pinned at delivery; not a fictional connection indicator. */
object AliceCopy {
    const val SKILL_NAME = "Голос закваски"
    const val SHARED_WARNING = "Любой рядом с общей колонкой сможет узнать ход и управлять только выбранной выпечкой. Другие выпечки, дневник, полка и фото не передаются."
    const val AVAILABILITY = "Нужны интернет и работающая книга на телефоне. Если Android остановит книгу, команды не выполнятся. Открой Мадре снова; старые команды не повторятся."
}

@Composable
fun AliceScreen(viewModel: BakingViewModel, coordinator: VoiceCoordinator, onBack: () -> Unit) {
    val state by coordinator.state.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(coordinator) { coordinator.load() }
    AlicePage(state, sessions.filterNot { it.isCompleted }, onBack,
        onSelect = { scope.launch { coordinator.select(it) } },
        onPreview = { scope.launch { coordinator.previewPair(it) } },
        onApprove = { scope.launch { coordinator.approvePair() } },
        onDisconnect = { coordinator.revokeLocally(); scope.launch { coordinator.disconnect() } },
    )
}

@Composable
internal fun AlicePage(state: VoiceUiState, sessions: List<BakingSession>, onBack: () -> Unit,
    onSelect: (String) -> Unit, onPreview: (String) -> Unit, onApprove: () -> Unit, onDisconnect: () -> Unit) {
    val colors = AppColors.current
    var candidateKey by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") } // pairing code is never saved in instance state/history
    val candidate = sessions.find { it.bakeKey == candidateKey }
    val selected = sessions.find { it.bakeKey == state.selectedBake }
    val busy = state.status == VoiceStatus.LOADING
    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BackLabel("К книге", onClick = onBack)
            Text("Алиса на кухне", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 26.sp)
            HeavyRule()
            Text(when (state.status) {
                VoiceStatus.UNLINKED -> "Не подключено"
                VoiceStatus.LOADING -> "Проверяем подключение…"
                VoiceStatus.WAITING_LINK -> "Выпечка выбрана · закончи подключение навыка"
                VoiceStatus.CONNECTED -> "Телефон на связи · выпечка доступна Алисе"
                VoiceStatus.OFFLINE -> "Телефон не подтвердил связь"
                VoiceStatus.REVOKE_PENDING -> "На телефоне отключено · ждём отзыв доступа"
                VoiceStatus.ERROR -> "Подключение не подтверждено"
            }, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 18.sp)
            state.message?.let { Text(it, color = colors.terracotta, fontFamily = FontFamily.Serif) }
            if (selected != null) {
                Text("${selected.recipe.name} · выпечка №${selected.id}\n${selected.currentStep.title}", color = colors.espresso, fontFamily = FontFamily.Serif)
            }
            Text(AliceCopy.AVAILABILITY, color = colors.cocoa, fontFamily = FontFamily.Serif)
            HairRule()
            if (state.selectedBake == null && state.status != VoiceStatus.REVOKE_PENDING) {
                Text("1. Выбери выпечку", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 19.sp)
                if (sessions.isEmpty()) Text("Сначала начни выпечку в рецепте. Ничего не выбрано автоматически.", color = colors.cocoa)
                else {
                    TapCycleRow(label = "Выпечка", value = candidate?.let { "${it.recipe.name} · №${it.id}" } ?: "не выбрана",
                        onClick = {
                            val index = sessions.indexOfFirst { it.bakeKey == candidateKey }
                            candidateKey = sessions[(index + 1) % sessions.size].bakeKey
                        })
                    candidate?.let { Text("Шаг: ${it.currentStep.title}", color = colors.cocoa, fontFamily = FontFamily.Serif) }
                    Text(AliceCopy.SHARED_WARNING, color = colors.cocoa, fontFamily = FontFamily.Serif)
                    if (candidate != null && !busy) {
                        BookButton("Разрешить для этой выпечки", onClick = { onSelect(candidate.bakeKey) })
                    } else if (!busy) {
                        Text("Сначала выбери выпечку в строке выше.", color = colors.cocoa, fontFamily = FontFamily.Serif)
                    }
                }
            }
            if (state.selectedBake != null && state.status != VoiceStatus.CONNECTED) {
                Text("2. Свяжи аккаунт Алисы", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 19.sp)
                Text("Скажи: «Алиса, запусти навык ${AliceCopy.SKILL_NAME}», затем «подключить аккаунт». Открой предложенное окно входа в приложении Яндекса и введи показанный код здесь — не произноси его колонке.", color = colors.cocoa, fontFamily = FontFamily.Serif)
                Text("Это доступ к одной выпечке, не вход в семейную полку. Почта и пароль Мадре не нужны.", color = colors.cocoa, fontFamily = FontFamily.Serif)
                OutlinedTextField(code, onValueChange = { if (it.length <= 10) code = it.uppercase(java.util.Locale.ROOT) },
                    label = { Text("Код из окна подключения") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BookButton("Проверить код", onClick = { onPreview(code) }, enabled = code.matches(Regex("[A-Z2-7]{10}")) && !busy,
                    variant = BookButtonVariant.SECONDARY)
                if (state.preview != null) {
                    Text(AliceCopy.SHARED_WARNING, color = colors.espresso, fontFamily = FontFamily.Serif)
                    BookButton("Подтвердить чтение и управление", onClick = { code = ""; onApprove() })
                }
            }
            if (state.selectedBake != null || state.status == VoiceStatus.REVOKE_PENDING) {
                BookButton(if (state.status == VoiceStatus.REVOKE_PENDING) "Повторить отзыв доступа" else "Отключить Алису",
                    onClick = { code = ""; onDisconnect() }, variant = BookButtonVariant.SECONDARY, enabled = !busy)
                Text("Для другой выпечки сначала отключи эту и свяжи навык заново.", color = colors.cocoa, fontFamily = FontFamily.Serif)
            }
            HairRule()
            Text("Что можно сказать", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 19.sp)
            Text("«Что готовится?» · «Повтори шаг» · «Сколько осталось?» · «Что дальше?» · «Граммовки»", color = colors.cocoa, fontFamily = FontFamily.Serif)
            Text("«Пауза» · «Продолжи» · «Следующий шаг» · «Предыдущий шаг». Алиса сначала назовёт выпечку и шаг и попросит подтверждение.", color = colors.cocoa, fontFamily = FontFamily.Serif)
            Text("Для отмены: «Отмени выпечку», затем «Подтверждаю отмену». «Стоп» только закрывает навык. «Что дальше?» читает следующий шаг, но не переключает его.", color = colors.cocoa, fontFamily = FontFamily.Serif)
            Text("«Испечено», фото и полка — только на телефоне. Если ответ о результате неизвестен, проверь таймер: не повторяй команду вслепую.", color = colors.cocoa, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(12.dp))
        }
    }
}
