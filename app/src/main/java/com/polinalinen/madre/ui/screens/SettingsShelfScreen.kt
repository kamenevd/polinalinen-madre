package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.account.InviteCode
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.shelf.ShelfMember
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.Stamp
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.viewmodel.FamilyBookViewModel
import com.polinalinen.madre.viewmodel.ShelfViewModel

/**
 * Полка в колофоне: имя, люди, код приглашения и уход с полки.
 * Вход и заведение живут здесь же — отдельных экранов под почту нет.
 */
@Composable
fun SettingsShelfScreen(
    myName: String,
    localRecords: List<BakeRecordEntity> = emptyList(),
    onBack: () -> Unit,
    familyBookViewModel: FamilyBookViewModel = viewModel(),
    shelfViewModel: ShelfViewModel = viewModel(),
) {
    val colors = LocalPaper.current
    val context = LocalContext.current
    val familyBookState by familyBookViewModel.state.collectAsState()
    val passwordResetNotice by familyBookViewModel.passwordReset.collectAsState()
    val members by shelfViewModel.members.collectAsState()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { familyBookViewModel.restore() }
    val account = familyBookState.account
    LaunchedEffect(account?.familyId) {
        if (account?.hasFamily == true) {
            familyBookViewModel.refreshFamily()
        }
    }
    LaunchedEffect(account?.familyId, account?.familyName, myName, localRecords) {
        shelfViewModel.refresh(account, myName, localRecords)
    }
    DisposableEffect(Unit) {
        onDispose { familyBookViewModel.clearInviteCode() }
    }

    val failed = familyBookState as? FamilyBookState.Failed
    val loading = familyBookState is FamilyBookState.Loading
    val inFamily = account?.hasFamily == true

    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier

                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            PaperHeader("Полка", onBack = onBack, overline = "Настройки", modifier = Modifier.padding(horizontal = 22.dp))

            if (inFamily) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stamp("ваша полка", colors.ink2)
                }
            }
            when {
                account == null || !account.hasFamily -> {
                    FamilyBookSection(
                        state = familyBookState,
                        onSignIn = familyBookViewModel::signIn,
                        onRegister = familyBookViewModel::register,
                        onCreateFamily = familyBookViewModel::createFamily,
                        onJoinFamily = familyBookViewModel::joinFamily,
                        passwordResetNotice = passwordResetNotice,
                        onRequestPasswordReset = familyBookViewModel::requestPasswordReset,
                    )
                }
                else -> {
                    if (failed != null) {
                        Text(
                            failed.failure.message,
                            color = colors.error,style = PaperType.body,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                        )
                    }
                    if (loading) {
                        Text(
                            "проверяем полку",
                            color = colors.ink2,style = PaperType.body,
                            fontStyle = FontStyle.Italic,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                        )
                    }
                    val familyName = account.familyName.orEmpty().ifBlank { "полка" }
                    Text(
                        familyName,
                        color = colors.ink,style = PaperType.body,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                    if (account.isFamilyOwner) {
                        TextAction(
                            "Переименовать полку",
                            onClick = {
                                draftName = familyName
                                renaming = true
                            },
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                    HairRule(Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                    ShelfPeopleList(
                        members = members,
                        familyOwnerId = account.familyOwnerId,
                    )
                    val code = account.inviteCode
                    if (code != null) {
                        val printedCode = InviteCode.format(code)
                        Text(
                            printedCode,
                            color = colors.acc,style = PaperType.caption,
                            fontSize = 17.sp,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                            TextAction("Скопировать", onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Код полки", printedCode))
                                familyBookViewModel.clearInviteCode()
                            }, modifier = Modifier.weight(1f))
                            TextAction("Отправить", onClick = {
                                val share = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Вступите на полку «${account.familyName ?: "Мадре"}»: $printedCode",
                                    )
                                context.startActivity(Intent.createChooser(share, "Отправить приглашение"))
                                familyBookViewModel.clearInviteCode()
                            }, modifier = Modifier.weight(1f))
                        }
                    }
                    if (account.isFamilyOwner) {
                        Spacer(Modifier.height(8.dp))
                        PaperButton(
                            label = "Обновить код приглашения",
                            variant = PaperButtonVariant.SECONDARY,
                            onClick = familyBookViewModel::rotateInviteCode,
                            modifier = Modifier.padding(horizontal = 22.dp),
                        )
                    }
                    HairRule(Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
                    TextAction(
                        "Уйти с полки · можно вернуться",
                        onClick = familyBookViewModel::leaveFamily,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
            SyncStatusLine(Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        }
    }

    if (renaming && account?.isFamilyOwner == true) {
        PaperDialog(
            onDismissRequest = { renaming = false },
            title = {
                Text("Название полки", color = colors.ink,style = PaperType.body, fontSize = 20.sp)
            },
            text = {
                FamilyBookField("Название полки", draftName) { draftName = it }
            },
            confirmButton = {
                PaperButton(
                    label = "Сохранить",
                    enabled = draftName.isNotBlank(),
                    onClick = {
                        familyBookViewModel.renameFamily(draftName)
                        renaming = false
                    },
                )
            },
            dismissButton = { TextAction("Не сейчас", onClick = { renaming = false }) },
        )
    }
}

@Composable
internal fun ShelfPeopleList(members: List<ShelfMember>, familyOwnerId: String?) {
    val colors = LocalPaper.current
    Text(
        "Книги на полке",
        color = colors.ink2,style = PaperType.body,
        fontStyle = FontStyle.Italic,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 22.dp),
    )
    members.forEach { member ->
        val founded = familyOwnerId == member.userId
        Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
            Text(
                member.displayName,
                color = colors.ink,style = PaperType.body,
                fontSize = 16.sp,
            )
            if (founded) {
                Text(
                    "кто завёл полку",
                    color = colors.ink2,style = PaperType.body,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
