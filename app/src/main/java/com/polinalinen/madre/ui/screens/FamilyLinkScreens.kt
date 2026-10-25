package com.polinalinen.madre.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.integration.v2.*
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.viewmodel.FamilyLinkViewModel
import com.polinalinen.madre.viewmodel.FamilyOverlay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

val LocalFamilyLinkModel = staticCompositionLocalOf<FamilyLinkViewModel?> { null }

/** All callbacks are real actions at the route boundary; render fixtures provide explicit no-ops. */
data class FamilyActions(
    val back: () -> Unit, val refresh: () -> Unit, val signIn: () -> Unit,
    val show: (FamilyOverlay?) -> Unit, val create: (String) -> Unit, val rename: (String) -> Unit,
    val approval: (Boolean) -> Unit, val memberName: (String, String) -> Unit,
    val remove: (String, Boolean) -> Unit, val undo: () -> Unit, val transfer: (String) -> Unit,
    val decide: (String, Boolean) -> Unit, val leave: (String?) -> Unit, val delete: () -> Unit,
    val invite: (Boolean) -> Unit, val revoke: () -> Unit,
    val share: () -> Unit, val copy: () -> Unit,
)

internal fun familyShareIntent(invite: ActiveFamilyInvite, family: String, inviter: String): Intent =
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
        (if (inviter.isBlank()) "Вас зовут на семейную полку «$family» в Мадре.\n"
        else "$inviter зовёт вас на семейную полку «$family» в Мадре.\n") +
            invite.invitation.savedInviteUrl() + "\nСсылка действует до ${familyDate(invite.expiresAt)}.")

internal fun familyDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")).withZone(ZoneOffset.UTC).format(Instant.parse(value))
}.getOrDefault("")

@Composable fun FamilyLinkRoute(onBack: () -> Unit) {
    val model = LocalFamilyLinkModel.current ?: return
    val state by model.state.collectAsState()
    val overlay by model.overlay.collectAsState()
    val sdk = LocalYandexAccountUi.current
    val sdkState = sdk?.model?.state?.collectAsState()?.value
    val context = LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { model.refresh() }
    FamilyPanelContent(state, overlay, FamilyActions(
        onBack, { model.refresh() }, { sdk?.signIn?.invoke(false) }, model::show,
        { model.create(it) }, { model.rename(it) }, { model.approval(it) },
        { id, name -> model.renameMember(id, name) }, { id, rotate -> model.remove(id, rotate) },
        { model.undo() }, { model.transfer(it) }, { id, accept -> model.decide(id, accept) },
        { model.leave(it) }, { model.delete() }, { model.invite(it) }, { model.revoke() },
        {
            state.invite?.let { link -> state.family?.let { family ->
                context.startActivity(Intent.createChooser(familyShareIntent(link, family.name, state.accountName), "Отправить приглашение"))
            } }
        }, {
            state.invite?.let { link ->
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("Приглашение в семью", link.invitation.savedInviteUrl()))
            }
        }), sdkAvailable = sdk != null,
        sdkBusy = sdkState is YandexAccountState.SigningIn || sdkState is YandexAccountState.Binding,
        sdkError = if (sdkState is YandexAccountState.Error) "Вход не завершён. Повторите вход с Яндекс ID. Семья и своя книга не удалены." else null)
}

@Composable fun FamilyPanelContent(state: FamilyPanelState, overlay: FamilyOverlay?, actions: FamilyActions,
    sdkAvailable: Boolean = true, sdkBusy: Boolean = false, sdkError: String? = null) {
    val p = LocalPaper.current
    val family = state.family
    BackHandler(overlay != null) { actions.show(null) }
    PaperScaffold(header = {
        PaperHeader("Семейная полка", actions.back, "", Modifier.padding(horizontal = 22.dp), actions = {
            if (state.owner) PaperIconButton({ actions.show(FamilyOverlay.Rename) }, label = "Название семьи") {
                Icon(Icons.Outlined.Edit, null)
            }
        })
    }, bottom = {
        state.undoName?.let { name -> PaperFamilyUndo("Участник убран из семьи: $name", actions.undo, !state.busy,
            Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) }
    }) {
        item {
            if (state.busy) {
                PaperProgress(modifier = Modifier.padding(vertical = 24.dp))
                PaperNote("Проверяем семью…")
            } else if (state.failure != null) {
                FamilyFailure(state.failure, actions.refresh)
                if (state.failure == ContractFailure.Kind.UNAUTHORIZED) {
                    Spacer(Modifier.height(16.dp)); YandexIdButton(actions.signIn, enabled = sdkAvailable)
                }
            }
            else if (family == null) FamilyEmptyContent(state, actions.signIn, { actions.create(suggestedFamilyName()) }, sdkAvailable = sdkAvailable, sdkBusy = sdkBusy, sdkError = sdkError)
            else {
                SmallLabel("${family.members.size} из 8 · ${if (state.owner) "вы владелец" else "вы участник"}")
                Text(family.name, style = PaperType.h1, color = p.ink, modifier = Modifier.padding(top = 12.dp))
                PaperNote("Все вошли с Яндекс ID. Видят выпечки друг друга на полке.", Modifier.padding(top = 8.dp, bottom = 24.dp))
                PaperCard(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    family.members.forEach { member ->
                        val self = member.id == state.userId
                        val editable = state.owner || self
                        PaperListRow(member.name.ifBlank { "Без имени" },
                            note = "с ${familyDate(member.joinedAt)} · ${member.bakesCount} выпечек",
                            onClick = if (editable) ({ actions.show(FamilyOverlay.Member(member.id)) }) else null,
                            leading = { FamilyMonogram(member.name) }, trailing = {
                                Column(horizontalAlignment = Alignment.End) {
                                    if (self) Text("вы", style = PaperType.caption, color = p.ink2)
                                    if (member.role == FamilyRole.OWNER) Text("ВЛАДЕЛЕЦ", style = PaperType.caption, color = p.acc)
                                    if (editable) Icon(Icons.Outlined.MoreHoriz, "Действия участника", tint = p.ink2)
                                }
                            })
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (family.members.size == 1) PaperNote("Пока на полке только вы.", Modifier.padding(bottom = 16.dp))
                PaperButton("Позвать по ссылке", { actions.invite(false) }, variant = PaperButtonVariant.ACCENT, icon = Icons.Outlined.Link)
                state.invite?.let { PaperNote("Ссылка действует до ${familyDate(it.expiresAt)} · многоразовая", Modifier.padding(top = 12.dp)) }
                if (state.owner) {

                    family.pending.forEach { request ->
                        Column(Modifier.padding(top = 16.dp)) {
                            Text("${request.name} просит войти", style = PaperType.title, color = p.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PaperTextAction("Принять", { actions.decide(request.id, true) })
                                PaperTextAction("Отклонить", { actions.decide(request.id, false) }, destructive = true)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
                PaperTextAction(when {
                    !state.owner -> "УЙТИ ИЗ СЕМЬИ"
                    family.members.size == 1 -> "УДАЛИТЬ СЕМЬЮ"
                    else -> "ПЕРЕДАТЬ СЕМЬЮ И УЙТИ"
                }, { actions.show(FamilyOverlay.Leave) }, destructive = true)
            }
        }
    }
    // During network work do not offer actions against an unverified/old roster.
    if (!state.busy) when (overlay) {
        FamilyOverlay.Create -> Unit // Creation is a single explicit tap, without a name dialog.
        FamilyOverlay.Rename -> if (family != null && state.owner) FamilyNameDialog("Название семьи", "Название", family.name, actions.rename) { actions.show(null) }
        FamilyOverlay.Invite, FamilyOverlay.CreatedInvite -> FamilyInviteSheet(state, actions, overlay == FamilyOverlay.CreatedInvite)
        is FamilyOverlay.Member -> family?.members?.singleOrNull { it.id == overlay.id }?.let { member ->
            FamilyMemberSheet(member, state, actions)
        }
        is FamilyOverlay.Remove -> family?.members?.singleOrNull { it.id == overlay.id }?.let { member ->
            if (state.owner && member.id != state.userId) FamilyRemoveDialog(member, { actions.remove(member.id, it) }) { actions.show(null) }
        }
        is FamilyOverlay.Transfer -> family?.members?.singleOrNull { it.id == overlay.id }?.let { member ->
            if (state.owner && member.id != state.userId) PaperConfirmDialog("Сделать ${member.name} владельцем?",
                "Вы станете участником. Править семью и приглашения сможет новый владелец.", "Передать", "Отмена",
                { actions.transfer(member.id) }, { actions.show(null) })
        }
        FamilyOverlay.Leave -> if (family != null) FamilyLeaveDialog(state, actions)
        null -> Unit
    }
}

@Composable fun FamilyEmptyContent(state: FamilyPanelState, onSignIn: () -> Unit, onCreate: () -> Unit,
    sdkAvailable: Boolean = true, localBakes: Int? = null, onLocalBook: (() -> Unit)? = null,
    sdkBusy: Boolean = false, sdkError: String? = null) {
    val p = LocalPaper.current
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        state.removedFamilyName?.let { PaperCard { Text("Вас убрали из семьи «$it». Ваши выпечки — в вашей книге.", style = PaperType.body, color = p.ink) } }
        FamilyAccentTitle("Общая полка", "для своих")
        PaperFamilyBooks(Modifier.fillMaxWidth().height(100.dp))
        Text("Видно, кто что испёк — выпечки и кадры всей семьи. Позвать — одна ссылка в любой мессенджер или QR-код.", style = PaperType.body, color = p.ink2)
        if (state.migration) PaperCard {
            Text("Перейдите на Яндекс ID — семья сохранится", style = PaperType.title, color = p.ink)
            PaperNote("Войдите с Яндекс ID: свяжем его с вашим существующим аккаунтом. Семья и выпечки останутся.")
        } else PaperCard { PaperNote("Только для вошедших с Яндекс ID · до 8 человек. Закваска и дневник остаются вашими.") }
        if (state.signedIn) {
            PaperNote("Вы вошли: ${state.accountName.ifBlank { "имя не указано" }} · Яндекс ID")
            PaperButton("Создать семейную полку", onCreate, variant = PaperButtonVariant.ACCENT)
            PaperNote("Вас уже позвали? Откройте ссылку из сообщения — она приведёт прямо сюда.")
        } else {
            YandexIdButton(onSignIn, enabled = sdkAvailable && !sdkBusy, size = YandexIdButtonSize.L)
            if (sdkBusy) PaperNote("Узнаём вас…")
            sdkError?.let { Text(it, style = PaperType.note, color = p.rib) }
            PaperNote(if (sdkAvailable) "Потом — «Позвать по ссылке»" else "Вход с Яндекс ID сейчас недоступен. Своя книга работает без входа.")
        }
        localBakes?.let { SmallLabel("Своя полка · $it выпечек") }
        onLocalBook?.let { PaperButton("Своя книга", it, variant = PaperButtonVariant.SECONDARY) }
    }
}

@Composable private fun FamilyInviteSheet(state: FamilyPanelState, actions: FamilyActions, created: Boolean = false) {
    val p = LocalPaper.current
    var qr by remember(state.invite?.invitation?.savedInviteUrl()) { mutableStateOf(false) }
    val snackbar = remember { PaperSnackbarState() }
    val scope = rememberCoroutineScope()
    val copy = {
        actions.copy.invoke()
        scope.launch {
            if (snackbar.showSnackbar("Ссылка скопирована · до ${state.invite?.let { familyDate(it.expiresAt) }.orEmpty()}", "ОТПРАВИТЬ")) actions.share()
        }
        Unit
    }
    BackHandler(qr) { qr = false }
    PaperSheet({ actions.show(null) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SmallLabel(if (created) "Полка создана" else "Позвать по ссылке")
            Text(state.family?.name ?: "Приглашение в семью", style = PaperType.h2, color = p.ink)
            PaperNote("Теперь позовите своих. Кто откроет ссылку — войдёт с Яндекс ID и присоединится.")
            val link = state.invite
            if (link != null) {
                if (qr) {
                    val bitmap = remember(link.invitation.savedInviteUrl()) { familyInviteQr(link.invitation).asImageBitmap() }
                    Image(bitmap, "QR-код приглашения в семью", Modifier.align(Alignment.CenterHorizontally).size(256.dp).background(Color.White).padding(16.dp).testTag("family-invite-qr"))
                    PaperTextAction("К ССЫЛКЕ", { qr = false })
                }
                Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(p.bg2).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Link, null, tint = p.acc)
                    Text(link.invitation.savedInviteUrl().removePrefix("https://"), Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = PaperType.ui, color = p.ink)
                    PaperIconButton(copy, label = "Скопировать ссылку") { Icon(Icons.Outlined.ContentCopy, null) }
                }
                Text("до ${familyDate(link.expiresAt)}${link.seatsLeft?.let { " · ещё $it мест" }.orEmpty()} · многоразовая", style = PaperType.caption, color = p.ink2)
                PaperButton("Поделиться ссылкой", actions.share, variant = PaperButtonVariant.ACCENT, icon = Icons.Outlined.Share)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { PaperButton("Копировать", copy, variant = PaperButtonVariant.SECONDARY, icon = Icons.Outlined.ContentCopy) }
                    Box(Modifier.weight(1f)) { PaperButton("QR-код", { qr = true }, variant = PaperButtonVariant.SECONDARY, icon = Icons.Outlined.QrCode) }
                }
            } else if (state.failure != null) {
                PaperNote("${if (created) "Полка уже создана. " else ""}Не удалось загрузить ссылку. Повторим только приглашение, без создания новой семьи.")
                FamilyFailure(state.failure, { actions.invite(false) })
            } else PaperNote("Активной ссылки нет. Попросите владельца создать новую.")
            if (state.owner) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PaperTextAction("ОТОЗВАТЬ", actions.revoke, enabled = link != null, destructive = true)
                PaperTextAction("НОВАЯ ССЫЛКА", { actions.invite(true) })
            }
            PaperNote("Ссылка утекла? «Отозвать» — старая перестанет работать сразу.")
            PaperTextAction("ПОЗЖЕ", { actions.show(null) }, Modifier.align(Alignment.CenterHorizontally))
            PaperSnackbar(snackbar)
        }
    }
}

@Composable private fun FamilyMemberSheet(member: FamilyMember, state: FamilyPanelState, actions: FamilyActions) {
    val p = LocalPaper.current
    var name by remember(member.id, member.name) { mutableStateOf(member.name) }
    PaperSheet({ actions.show(null) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FamilyMonogram(member.name)
                Column { Text(member.name, style = PaperType.h2, color = p.ink)
                    PaperNote("${if (member.id == state.userId) "Вы · " else ""}${if (member.role == FamilyRole.OWNER) "Владелец" else "Участник"} · с ${familyDate(member.joinedAt)}") }
            }
            PaperField(name, { name = it }, label = "Как подписан(а) в семье", modifier = Modifier.testTag("family-member-name"), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            PaperNote("Подпись видна всей семье. Её можно сменить самостоятельно.")
            PaperButton("Сохранить подпись", { actions.memberName(member.id, familyEditedName(name)) }, enabled = name.isNotBlank() && name.length <= 120)
            if (state.owner && member.id != state.userId) {
                PaperListRow("Сделать владельцем", note = "вы станете участником", onClick = { actions.show(FamilyOverlay.Transfer(member.id)) }, leading = { Icon(Icons.Outlined.ManageAccounts, null, tint = p.acc) })
                PaperListRow("Убрать из семьи", note = "выпечки уйдут с полки вместе с участником", onClick = { actions.show(FamilyOverlay.Remove(member.id)) }, leading = { Icon(Icons.Outlined.PersonRemove, null, tint = p.rib) })
            }
        }
    }
}

@Composable private fun FamilyRemoveDialog(member: FamilyMember, onRemove: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var rotate by remember(member.id) { mutableStateOf(true) }
    val p = LocalPaper.current
    PaperDialog(onDismiss, title = { Text("Убрать из семьи «${member.name}»?", style = PaperType.h2, color = p.ink) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Выпечки (${member.bakesCount}) пропадут с полки семьи. На телефоне всё останется. Вернуться можно только с вашего подтверждения.", style = PaperType.body, color = p.ink2)
            PaperListRow("Заодно сменить ссылку приглашения", trailing = { PaperSwitch(rotate, { rotate = it }, Modifier.testTag("family-remove-rotate")) })
        }
    }, confirmButton = { PaperTextAction("УБРАТЬ", { onRemove(rotate) }, destructive = true) }, dismissButton = { PaperTextAction("ОТМЕНА", onDismiss) })
}

@Composable private fun FamilyLeaveDialog(state: FamilyPanelState, actions: FamilyActions) {
    val family = state.family ?: return
    val candidates = family.members.filter { it.id != state.userId }
    var selected by remember { mutableStateOf<String?>(null) }
    val solo = state.owner && candidates.isEmpty()
    val title = when { solo -> "Удалить семью «${family.name}»?"; state.owner -> "Передать семью и уйти"; else -> "Уйти из семьи «${family.name}»?" }
    PaperDialog({ actions.show(null) }, title = { Text(title, style = PaperType.h2, color = LocalPaper.current.ink) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (solo) "Семья будет удалена, ссылка перестанет работать. Ваши выпечки останутся в вашей книге."
                else "Ваши выпечки уйдут с полки семьи, на телефоне всё останется. Вернуться — по ссылке от семьи.", style = PaperType.body, color = LocalPaper.current.ink2)
            if (state.owner && !solo) {
                PaperNote("Сначала выберите нового владельца. Молчаливой передачи не будет.")
                candidates.forEach { member -> PaperListRow(member.name,
                    modifier = Modifier.testTag("family-successor-${member.id}"), onClick = { selected = member.id },
                    trailing = { Icon(if (selected == member.id) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                        null, tint = if (selected == member.id) LocalPaper.current.acc else LocalPaper.current.ink2) }) }
            }
        }
    }, confirmButton = { PaperTextAction(if (solo) "УДАЛИТЬ" else if (state.owner) "ПЕРЕДАТЬ И УЙТИ" else "УЙТИ",
        { if (solo) actions.delete() else actions.leave(selected) }, destructive = true, enabled = !state.owner || solo || selected != null) },
        dismissButton = { PaperTextAction("ОСТАТЬСЯ", { actions.show(null) }) })
}

@Composable private fun FamilyNameDialog(title: String, label: String, initial: String, confirm: (String) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    PaperDialog(dismiss, title = { Text(title, color = LocalPaper.current.ink, style = PaperType.h2) },
        text = { PaperField(name, { name = it }, label = label, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
        confirmButton = { PaperButton("Сохранить", { confirm(familyEditedName(name)) }, enabled = name.isNotBlank() && name.length <= 120) },
        dismissButton = { PaperTextAction("Отмена", dismiss) })
}

@Composable private fun FamilyAccentTitle(first: String, last: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(first, style = PaperType.display, color = LocalPaper.current.ink)
        Text(last, style = PaperType.display.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = LocalPaper.current.acc)
    }
}

@Composable private fun FamilyMonogram(name: String) {
    val p = LocalPaper.current
    Box(Modifier.size(44.dp).clip(CircleShape).background(p.acc2), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "—", style = PaperType.h2, color = p.acc)
    }
}

@Composable fun FamilyFailure(kind: ContractFailure.Kind, retry: () -> Unit, showRetry: Boolean = true) {
    val text = when (kind) {
        ContractFailure.Kind.DEAD_INVITE -> "Ссылка устарела. Её отозвали или прошло 7 дней. Попросите новую ссылку у того, кто звал."
        ContractFailure.Kind.FAMILY_FULL -> "В семье уже 8 человек. Попросите владельца освободить место."
        ContractFailure.Kind.UNAUTHORIZED -> "Доступ к семье не подтверждён. Войдите с Яндекс ID и обновите семью."
        ContractFailure.Kind.OTHER_FAMILY -> "Вы уже в другой семье. Переход требует вашего подтверждения."
        ContractFailure.Kind.CONFLICT -> "Семья изменилась. Обновите данные и повторите действие."
        ContractFailure.Kind.RATE_LIMITED -> "Слишком много попыток. Попробуйте позже."
        ContractFailure.Kind.UNSUPPORTED_PROTOCOL, ContractFailure.Kind.DISABLED -> "Семья по ссылке пока недоступна. Своя книга работает без входа."
        ContractFailure.Kind.NETWORK -> "Нет связи. Доступ к семье не подтверждён. Своя книга остаётся на телефоне."
        else -> "Не удалось проверить семью. Обновите данные и попробуйте ещё раз."
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(text, style = PaperType.body, color = LocalPaper.current.ink2)
        if (showRetry) PaperButton("Повторить", retry, variant = PaperButtonVariant.SECONDARY)
    }
}

@Composable fun FamilyInvitationRoute() {
    val model = LocalFamilyLinkModel.current ?: return
    val invite by model.invitation.collectAsState()
    val panel by model.state.collectAsState()
    val sdk = LocalYandexAccountUi.current
    val sdkState = sdk?.model?.state?.collectAsState()?.value
    val provisioning = sdk?.model?.provisioning?.collectAsState()?.value
    LaunchedEffect(provisioning?.phase) {
        if (provisioning?.phase == ForegroundProvisioningState.Phase.COMPLETE) model.signedInReturned()
    }
    if (invite.phase == InvitationFlow.Phase.EMPTY) return
    BackHandler { model.closeInvitation() }
    FamilyInvitationContent(invite, panel.accountName, { sdk?.signIn?.invoke(false) },
        { model.join() }, { model.join(true) }, { model.refreshPending() }, model::retryInvitation, model::closeInvitation,
        sdkAvailable = sdk != null, sdkBusy = sdkState is YandexAccountState.SigningIn || sdkState is YandexAccountState.Binding,
        sdkError = (sdkState as? YandexAccountState.Error)?.kind?.let { "Вход не завершён. Повторите вход с Яндекс ID — приглашение сохранено." })
}

@Composable fun FamilyInvitationContent(state: InvitationFlow.State, accountName: String,
    signIn: () -> Unit, join: () -> Unit, switch: () -> Unit, refreshPending: () -> Unit,
    retry: () -> Unit, close: () -> Unit, sdkAvailable: Boolean = true, sdkBusy: Boolean = false, sdkError: String? = null) {
    val p = LocalPaper.current
    val preview = state.preview
    PaperScaffold(header = { PaperHeader("", close, "Приглашение", Modifier.padding(horizontal = 22.dp)) }, bottom = {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.phase) {
                InvitationFlow.Phase.SIGN_IN -> YandexIdButton(signIn, size = YandexIdButtonSize.L, enabled = sdkAvailable && !sdkBusy)
                InvitationFlow.Phase.READY -> PaperButton("Присоединиться", join, variant = PaperButtonVariant.ACCENT, icon = Icons.Outlined.GroupAdd)
                InvitationFlow.Phase.PENDING -> PaperButton("Проверить подтверждение", refreshPending, variant = PaperButtonVariant.SECONDARY)
                InvitationFlow.Phase.MEMBER -> PaperButton("К семье", close, variant = PaperButtonVariant.ACCENT)
                InvitationFlow.Phase.ERROR -> if (state.failure != ContractFailure.Kind.DEAD_INVITE) PaperButton("Повторить", retry, variant = PaperButtonVariant.SECONDARY)
                else -> Unit
            }
            PaperTextAction(if (state.phase in listOf(InvitationFlow.Phase.ERROR, InvitationFlow.Phase.DECLINED)) "ПОНЯТНО" else "НЕ СЕЙЧАС", close, Modifier.align(Alignment.CenterHorizontally))
        }
    }) {
        item {
            when (state.phase) {
                InvitationFlow.Phase.LOADING, InvitationFlow.Phase.JOINING, InvitationFlow.Phase.VERIFYING -> {
                    PaperProgress(); PaperNote("Проверяем приглашение…")
                }
                InvitationFlow.Phase.ERROR -> {
                    Text(if (state.failure == ContractFailure.Kind.DEAD_INVITE) "Ссылка устарела" else if (state.failure == ContractFailure.Kind.FAMILY_FULL) "Мест нет" else "Приглашение недоступно", style = PaperType.display, color = p.ink)
                    Spacer(Modifier.height(24.dp)); FamilyFailure(state.failure ?: ContractFailure.Kind.INVALID, retry, showRetry = false)
                }
                InvitationFlow.Phase.DECLINED -> { Text("Заявка отклонена", style = PaperType.h1, color = p.ink); PaperNote("Попросите владельца семьи прислать новое приглашение.") }
                InvitationFlow.Phase.MEMBER -> { Text("Вы в семье «${state.family?.name.orEmpty()}»", style = PaperType.h1, color = p.ink); PaperNote("Доступ подтверждён сервером. Своя книга остаётся вашей.") }
                else -> if (preview != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { preview.initials.forEach { FamilyMonogram(it) } }
                    Spacer(Modifier.height(24.dp))
                    SmallLabel("Семья · ${preview.membersCount} человека · ${preview.bakesCount} выпечек")
                    FamilyAccentTitle("Семья", preview.familyName, Modifier.padding(top = 14.dp, bottom = 14.dp))
                    Text("Вас приглашает ${preview.inviterName}.", style = PaperType.body, color = p.ink2)
                    Spacer(Modifier.height(24.dp))
                    if (state.phase == InvitationFlow.Phase.PENDING) PaperCard {
                        Text("Ждём подтверждения", style = PaperType.h2, color = p.ink)
                        PaperNote("Владелец должен принять заявку. До подтверждения участники и выпечки недоступны. Прежняя семья пока не меняется.")
                    } else if (state.phase == InvitationFlow.Phase.SIGN_IN) {
                        PaperCard { PaperNote("Чтобы присоединиться, войдите с Яндекс ID. Семья увидит, кто присоединяется.") }
                        PaperNote("После входа вернёмся к этому приглашению.", Modifier.padding(top = 12.dp))
                        if (sdkBusy) PaperNote("Узнаём вас…")
                        sdkError?.let { Text(it, style = PaperType.note, color = p.rib) }
                    } else {
                        PaperCard {
                            SmallLabel("Семья увидит")
                            Text("Ваше имя — ${accountName.ifBlank { "имя не указано" }} (можно сменить).\nВыпечки, которые вы поставите на полку.\nЗакваску и дневник — нет, они остаются вашими.", style = PaperType.body, color = p.ink2, modifier = Modifier.padding(top = 12.dp))
                        }
                        PaperNote("Вы вошли: ${accountName.ifBlank { "имя не указано" }} · Яндекс ID", Modifier.padding(top = 16.dp))
                        if (preview.approvalRequired) PaperNote("Вход — после подтверждения владельца.")
                    }
                }
            }
        }
    }
    if (state.phase == InvitationFlow.Phase.SWITCH_CONFIRMATION) PaperConfirmDialog("Перейти в другую семью?",
        "Вы уже в семье. Уйти оттуда и присоединиться к «${preview?.familyName.orEmpty()}»? Выпечки останутся у вас. Если нужна заявка, прежняя семья сохранится до подтверждения.",
        "Перейти", "Остаться", switch, close)
}
