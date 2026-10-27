package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.integration.v2.*
import com.polinalinen.madre.ui.kit.*

private fun word(number: Int) = listOf("первая", "вторая", "третья", "четвёртая", "пятая", "шестая", "седьмая", "восьмая", "девятая").getOrNull(number - 1).orEmpty()
private fun status(state: RosterStatus) = when (state) {
    RosterStatus.CONNECTED -> "Алиса подключена · навык «Голос закваски»"
    RosterStatus.ATTACHING -> "Проверяем разрешение…"
    RosterStatus.ATTACHED -> "Разрешение получено · связь ещё не подтверждена"
    RosterStatus.REVOKE_PENDING -> "На телефоне отключено · ждём отзыв доступа"
    RosterStatus.OFFLINE -> "НЕТ СВЯЗИ · откройте Мадре на телефоне"
    RosterStatus.ERROR -> "Подключение не подтверждено"
    RosterStatus.UNLINKED -> "Не подключено"
}

@Composable private fun RosterRows(bakes: List<RosterBake>, current: Int? = null) {
    val p = LocalPaper.current
    if (bakes.isEmpty()) Text("Сейчас ничего не печётся. Начните выпечку на телефоне.", style = PaperType.note, color = p.ink2)
    bakes.forEach { bake ->
        Row(Modifier.fillMaxWidth().background(if (bake.bakeNumber == current) p.acc2 else p.bg)
            .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperBakeNumber(bake.bakeNumber, size = BakeNumberSize.Sm, planned = bake.state == RosterBakeState.SCHEDULED)
            Column(Modifier.weight(1f)) {
                Text(bake.recipeName, style = PaperType.title, color = p.ink)
                Text(when (bake.state) {
                    RosterBakeState.RUNNING -> bake.stepTitle
                    RosterBakeState.PAUSED -> "На паузе · ${bake.stepTitle}"
                    RosterBakeState.SCHEDULED -> "ЗАПЛАНИРОВАНО · " + java.time.Instant.ofEpochMilli(requireNotNull(bake.startAt)).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    RosterBakeState.FINALE -> "Испечено · впишите в книгу на телефоне"
                }, style = PaperType.caption, color = p.ink2)
            }
            Text(word(bake.bakeNumber), style = PaperType.caption, color = p.acc)
        }
    }
}

/** J05: list and scope, never a selected-bake picker; no invented Station name. */
@Composable internal fun RosterAlicePage(state: RosterUiState, onBack: () -> Unit, onConsent: () -> Unit,
    onDisconnect: () -> Unit, onRetry: () -> Unit, onContinue: () -> Unit = {},
    identityReady: Boolean = false, accountStep: (@Composable () -> Unit)? = null) {
    val p = LocalPaper.current
    val hasAttempt = state.pairStatus != InstallationPairStatus.NONE || state.retryPair
    val canConsent = !hasAttempt && !state.consent && state.status in setOf(RosterStatus.UNLINKED, RosterStatus.ERROR)
    var warning by remember(canConsent) { mutableStateOf(false) }
    val expiry = state.pendingExpiresAt
    var expired by remember(expiry) { mutableStateOf(expiry != null && expiry <= System.currentTimeMillis()) }
    LaunchedEffect(expiry) {
        if (expiry != null) {
            kotlinx.coroutines.delay((expiry - System.currentTimeMillis()).coerceAtLeast(0L))
            expired = true // Explanation only: never renew, check, launch login or create consent.
        }
    }
    PaperSurface(color = p.bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PaperHeader("Алиса на кухне", onBack, overline = "Голос закваски")
            Text(status(state.status), style = PaperType.body, color = p.ink)
            state.message?.let { PaperNote(it) }
            SmallLabel("ЯНДЕКС ID")
            accountStep?.invoke()
            PaperNote("Войдите тем же аккаунтом, что на Станции. Проверенный вход — не подключение навыка и не согласие на доступ.")
            when (state.pairStatus) {
                InstallationPairStatus.NEEDS_IDENTITY -> {
                    PaperNote("После проверенного входа продолжите эту попытку отдельным нажатием.")
                    PaperButton("Продолжить подключение", onContinue, enabled = identityReady)
                }
                InstallationPairStatus.AUTHORIZATION_PENDING -> {
                    PaperNote(if (expired) "Время попытки истекло. Отмените её перед новым согласием."
                        else "Разрешение подготовлено до ${expiry?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) } ?: "окончания попытки"}. Это ещё не связь с Алисой.")
                    if (!expired) {
                        PaperNote("Скажите «${AliceCopy.LAUNCH_PHRASE}». В приложении Яндекс нажмите «Авторизоваться» и завершите привязку тем же аккаунтом. Вернувшись, проверьте подключение — само возвращение доступ не подтверждает.")
                        PaperButton("Проверить подключение", onRetry, variant = PaperButtonVariant.SECONDARY)
                    }
                }
                InstallationPairStatus.EXPIRED -> PaperNote("Время попытки истекло. Отмените её перед новым согласием.")
                InstallationPairStatus.INTERRUPTED -> PaperNote("Попытка прервана. Отмените её перед новым согласием.")
                InstallationPairStatus.NONE -> Unit
            }
            if (state.retryPair && !expired && state.pairStatus !in setOf(InstallationPairStatus.EXPIRED, InstallationPairStatus.INTERRUPTED))
                PaperButton("Повторить эту попытку", onContinue, variant = PaperButtonVariant.SECONDARY)
            if (hasAttempt) PaperTextAction("Отменить попытку", onDisconnect)
            SmallLabel("СЛЫШИТ ВСЕ ТЕКУЩИЕ ВЫПЕЧКИ · ${state.bakes.size}")
            RosterRows(state.bakes)
            PaperNote("Выбирать ничего не нужно. Назовите номер: первая, вторая, третья." +
                if (state.status == RosterStatus.CONNECTED) " Планы и финалы — только для чтения." else " Пока связь не подтверждена, список остаётся только на телефоне.")
            Text("Что можно сказать", style = PaperType.h2, color = p.ink)
            Text("«Что печётся?»\n«Что со второй?»\n«Пауза для номера один»", style = PaperType.body, color = p.ink2)
            Text("Управлять голосом", style = PaperType.title, color = p.ink)
            PaperSwitch(checked = state.consent, onCheckedChange = { if (it && canConsent) warning = true else if (!it) onDisconnect() },
                modifier = Modifier.semantics { contentDescription = "Управлять голосом · все текущие выпечки" },
                enabled = state.consent || canConsent)
            PaperNote("Чтение и управление всеми текущими выпечками на этой установке. Любой рядом с общей колонкой сможет узнать ход и управлять ими. Дневник, полка, фото и имена не передаются. Каждое действие — только после отдельного «да»; отмена — «подтверждаю отмену».")
            PaperNote("Планы и «Испечено» доступны только для чтения. Завершение, фото и полка — только на телефоне.")
            if (!hasAttempt && state.consent && state.status in setOf(RosterStatus.OFFLINE, RosterStatus.ERROR))
                PaperButton("Повторить связь", onRetry, variant = PaperButtonVariant.SECONDARY)
            if (!hasAttempt && (state.consent || state.status in setOf(RosterStatus.REVOKE_PENDING, RosterStatus.ERROR, RosterStatus.ATTACHING)))
                PaperTextAction(if (state.status == RosterStatus.REVOKE_PENDING) "Повторить отзыв доступа" else "ОТКЛЮЧИТЬ АЛИСУ", onDisconnect)
            PaperNote("Нужны интернет и открытая Мадре. Вход, согласие и возврат из приложения Яндекс сами по себе не подтверждают связь с навыком.")
        }
    }
    if (warning) PaperDialog(onDismissRequest = { warning = false },
        title = { Text("Разрешить для всех текущих выпечек?", style = PaperType.h2, color = p.ink) },
        text = { Text("Алиса сможет читать все текущие выпечки этой установки и предлагать управление ими. Любой рядом с общей колонкой получит этот доступ. Каждое действие выполняется только после отдельного подтверждения. Доступ можно отозвать в настройках. История, фото и полка не передаются. Согласие готовит попытку, но не подтверждает связь с навыком.", style = PaperType.body, color = p.ink2) },
        confirmButton = { PaperButton("Да · разрешить", { warning = false; onConsent() }) },
        dismissButton = { PaperButton("Нет", { warning = false }, variant = PaperButtonVariant.SECONDARY) })
}

/** J06: current is route context, NOT a selection or authority. Chips are spoken examples only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RosterAliceSheetContent(state: RosterUiState, current: Int?, onSettings: () -> Unit,
    onStation: (() -> Unit)? = null) {
    val p = LocalPaper.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallLabel("АЛИСА", color = p.acc)
        Text(if (state.status == RosterStatus.CONNECTED) "Алиса знает все ${state.bakes.size} выпечки" else "Связь не подтверждена", style = PaperType.h2, color = p.ink)
        RosterRows(state.bakes, current)
        val target = current?.let { "для выпечки номер $it" }.orEmpty()
        var example by remember(current) { mutableStateOf(current?.let { "что с выпечкой номер $it" } ?: "что печётся") }
        Text("«Алиса, спроси у Голоса закваски, $example»", style = PaperType.body, color = p.ink2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperChip("Что печётся?", example == "что печётся", { example = "что печётся" })
            PaperChip("Повтори шаг", example.startsWith("повтори"), { example = "повтори шаг $target".trim() })
            PaperChip("Сколько осталось?", example.startsWith("сколько"), { example = "сколько осталось $target".trim() })
        }
        PaperNote("Подсказки меняют пример фразы, но не отправляют команду колонке.")
        onStation?.let { PaperTextAction("Таймер на Станции", it) }
        PaperTextAction("Настройки Алисы", onSettings)
        PaperNote("Номер нужно назвать навыку. Встроенный таймер Станции его не знает. «Испечено», фото и полка — только на телефоне.")
    }
}
