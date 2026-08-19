package com.polinalinen.madre.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.polinalinen.madre.shelf.ShelfShareMode
import com.polinalinen.madre.shelf.ShelfSharePolicy
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BookButtonVariant
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.Stamp
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.FamilyBookViewModel
import com.polinalinen.madre.viewmodel.ShelfViewModel

/**
 * Полка в колофоне: имя, люди, код, как ставить выпечку, уйти.
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
    val colors = AppColors.current
    val context = LocalContext.current
    val familyBookState by familyBookViewModel.state.collectAsState()
    val members by shelfViewModel.members.collectAsState()
    val prefs = remember { context.getSharedPreferences(ShelfSharePolicy.PREFS, android.content.Context.MODE_PRIVATE) }
    var shareMode by remember { mutableStateOf(ShelfSharePolicy.read(prefs)) }
    var pickingShare by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { familyBookViewModel.restore() }
    LaunchedEffect(familyBookState, myName, localRecords) {
        shelfViewModel.refresh(myName, localRecords)
    }

    val account = familyBookState.account
    val inFamily = account?.hasFamily == true

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            BackLabel("Настройки", onClick = onBack, modifier = Modifier.padding(horizontal = 22.dp))
            Text(
                "Полка",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            if (inFamily) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stamp("ваша полка", colors.cocoa)
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
                        onRotateInvite = familyBookViewModel::rotateInviteCode,
                        onSignOut = familyBookViewModel::signOut,
                        onCodeHandled = familyBookViewModel::clearInviteCode,
                    )
                }
                else -> {
                val familyName = account.familyName.orEmpty().ifBlank { "полка" }
                Text(
                    familyName,
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
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
                Text(
                    "Книги на полке",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
                members.forEach { member ->
                    val founded = account.familyOwnerId == member.userId
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                        Text(
                            member.displayName,
                            color = colors.espresso,
                            fontFamily = FontFamily.Serif,
                            fontSize = 16.sp,
                        )
                        if (founded) {
                            Text(
                                "кто завёл полку",
                                color = colors.cocoa,
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                val code = account.inviteCode
                if (code != null) {
                    val printedCode = InviteCode.format(code)
                    Text(
                        printedCode,
                        color = colors.crust,
                        fontFamily = FontFamily.Monospace,
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
                    BookButton(
                        label = "Обновить код приглашения",
                        variant = BookButtonVariant.SECONDARY,
                        onClick = familyBookViewModel::rotateInviteCode,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                HairRule(Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
                SettingsShelfShareRow(
                    mode = shareMode,
                    onClick = { pickingShare = true },
                )
                HairRule(Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
                TextAction(
                    "Уйти с полки · книга на телефоне останется",
                    onClick = familyBookViewModel::leaveFamily,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                TextAction(
                    "Выйти · книга на телефоне останется",
                    onClick = familyBookViewModel::signOut,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                }
            }
            SyncStatusLine(Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        }
    }

    if (pickingShare) {
        AlertDialog(
            onDismissRequest = { pickingShare = false },
            shape = RoundedCornerShape(4.dp),
            containerColor = colors.cream,
            title = {
                Text(ShelfSharePolicy.SETTING_LABEL, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 20.sp)
            },
            text = {
                Column {
                    listOf(ShelfShareMode.ALWAYS, ShelfShareMode.ASK).forEach { mode ->
                        val label = ShelfSharePolicy.labelOf(mode)
                        Box(
                            Modifier.fillMaxWidth().then(bookAction(label) {
                                shareMode = mode
                                ShelfSharePolicy.write(prefs, mode)
                                pickingShare = false
                            }),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                label,
                                color = if (mode == shareMode) colors.crust else colors.espresso,
                                fontFamily = FontFamily.Serif,
                                fontSize = 16.sp,
                                fontWeight = if (mode == shareMode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextAction("Оставить как есть", onClick = { pickingShare = false }) },
        )
    }

    if (renaming && account?.isFamilyOwner == true) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            shape = RoundedCornerShape(4.dp),
            containerColor = colors.cream,
            title = {
                Text("Название полки", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 20.sp)
            },
            text = {
                FamilyBookField("Название полки", draftName) { draftName = it }
            },
            confirmButton = {
                BookButton(
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
private fun SettingsShelfShareRow(mode: ShelfShareMode, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(bookAction("${ShelfSharePolicy.SETTING_LABEL}: ${ShelfSharePolicy.labelOf(mode)}", onClick = onClick))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ShelfSharePolicy.SETTING_LABEL, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 15.sp)
        Text(
            ShelfSharePolicy.labelOf(mode),
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
        )
    }
}
