package com.polinalinen.madre.account.yandex

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.kit.*
import androidx.activity.compose.BackHandler

/** Optional Activity-owned wiring. Null preserves all SignedOut/baseline routes. */
class YandexAccountUi(val model:YandexAccountViewModel,val signIn:(Boolean)->Unit)
val LocalYandexAccountUi=staticCompositionLocalOf<YandexAccountUi?>{null}
private const val PRIVACY="Мадре получит только имя, портрет и номер аккаунта. Пароль Яндекс нам не показывает. Книга остаётся на телефоне."

@Composable fun YandexAccountPanel(state:YandexAccountState,pendingUnbind:Boolean,
    onSignIn:(Boolean)->Unit,onProfile:()->Unit,onRetryUnbind:()->Unit,
    size:YandexIdButtonSize=YandexIdButtonSize.M) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        when {
            pendingUnbind -> {
                PaperNote("На телефоне отключено. Отзыв доступа на сервере ещё не подтверждён.")
                PaperButton("Повторить отключение",onRetryUnbind,variant=PaperButtonVariant.SECONDARY)
            }
            state is YandexAccountState.SignedIn -> {
                PaperListRow(state.profile.displayName.ifBlank{state.profile.login},
                    note="${state.profile.login} · Яндекс ID",onClick=onProfile,
                    trailing={YandexAccountPortrait(state.profile)})
            }
            state is YandexAccountState.SigningIn -> {
                PaperButton("Открываем Яндекс ID…",{},enabled=false,variant=PaperButtonVariant.SECONDARY)
                if(!state.browser)PaperTextAction("ВОЙТИ ЧЕРЕЗ БРАУЗЕР",{onSignIn(true)})
                PaperNote("Книга работает и без входа.")
            }
            state is YandexAccountState.Binding -> {
                PaperButton("Узнаём вас…",{},enabled=false,variant=PaperButtonVariant.SECONDARY)
                PaperNote("Проверяем вход на сервере Мадре. Книга остаётся на телефоне.")
            }
            else -> {
                if(state is YandexAccountState.Error)Text(accountErrorCopy(state.kind),style=PaperType.note,color=LocalPaper.current.rib)
                YandexIdButton({onSignIn(false)},size=size)
                Text(PRIVACY,style=PaperType.note,color=LocalPaper.current.ink2)
            }
        }
    }
}

@Composable fun YandexSettingsSection(myName:String,onMyNameChange:(String)->Unit,onOpenAlice:()->Unit) {
    val owner=LocalYandexAccountUi.current ?: return
    val state by owner.model.state.collectAsState()
    val pending by owner.model.pendingUnbind.collectAsState()
    val notice by owner.model.offerError.collectAsState()
    var profileOpen by rememberSaveable{mutableStateOf(false)}
    var confirmOut by rememberSaveable{mutableStateOf(false)}
    val profile=(state as? YandexAccountState.SignedIn)?.profile
    PaperSection("ЯНДЕКС ID",Modifier.padding(horizontal=22.dp,vertical=18.dp)) {
        PaperCard {
            if(profile==null) {
                Text("Подключите Яндекс ID",style=PaperType.h2,color=LocalPaper.current.ink)
                PaperNote("Алиса сможет узнать ваш аккаунт. Книга, печь и закваска работают и без входа.")
                Spacer(Modifier.height(16.dp))
            }
            YandexAccountPanel(state,pending,owner.signIn,{profileOpen=true},owner.model::retryPendingUnbind)
            notice?.let{PaperNote(it)}
            if(state is YandexAccountState.Error && !pending) {
                PaperTextAction("Отключить аккаунт на этом телефоне",{confirmOut=true},destructive=true)
            }
            if(profile!=null) {
                PaperListRow("Алиса",note="Открыть настоящую связку навыка",onClick=onOpenAlice)
                profile.bookNameSuggestion(myName)?.let{suggestion->
                    PaperTextAction("Использовать имя «$suggestion»",{if(myName.isBlank())onMyNameChange(suggestion)})
                }
            }
        }
    }
    if(profileOpen && profile!=null)YandexAccountSheet(profile,
        onDismiss={profileOpen=false},onSwitch={profileOpen=false;owner.signIn(false)},
        onSignOut={profileOpen=false;confirmOut=true})
    if(confirmOut)PaperConfirmDialog("Выйти из Яндекс ID?",
        "Книга, закваска, дневник, выпечки и фото останутся на этом телефоне. Алиса перестанет узнавать вас. Аккаунт и семья Полки не меняются.",
        "Выйти","Остаться",onConfirm={confirmOut=false;owner.model.signOut()},onDismiss={confirmOut=false},destructive=true)
}

@Composable private fun YandexAccountSheet(profile:VerifiedYandexProfile,onDismiss:()->Unit,onSwitch:()->Unit,onSignOut:()->Unit) {
    val context=LocalContext.current
    var linkError by remember{mutableStateOf(false)}
    PaperSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp,vertical=16.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                YandexAccountPortrait(profile)
                Column {Text(profile.displayName.ifBlank{profile.login},style=PaperType.h2,color=LocalPaper.current.ink)
                    PaperNote("${profile.login} · Яндекс ID")}
            }
            Text("Что Мадре знает о вас",style=PaperType.h2,color=LocalPaper.current.ink)
            PaperNote("Имя и портрет — только на телефоне. Номер аккаунта — на сервере Мадре, чтобы Алиса узнала вас.")
            PaperNote("Почту, телефон и пароль не запрашиваем. Доступ можно отозвать в настройках Яндекс ID.")
            PaperButton("Сменить аккаунт",onSwitch,variant=PaperButtonVariant.SECONDARY)
            PaperButton("Открыть Яндекс ID",{linkError=runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://id.yandex.ru"))) }.isFailure},
                variant=PaperButtonVariant.SECONDARY)
            if(linkError)PaperNote("Не удалось открыть браузер. Настройки аккаунта доступны на id.yandex.ru.")
            PaperTextAction("ВЫЙТИ ИЗ ЯНДЕКС ID",onSignOut,destructive=true)
        }
    }
}

/** Optional first-offer content; declining never changes the book, starter, shelf or timers. */
@Composable fun YandexOfferContent(state:YandexAccountState,pendingUnbind:Boolean,onSignIn:(Boolean)->Unit,
    onSkip:()->Unit,onRetryUnbind:()->Unit) {
    BackHandler { onSkip() }
    PaperScaffold(header={PaperHeader("Яндекс ID",onBack=onSkip,modifier=Modifier.padding(horizontal=22.dp))},bottom={
        Column(Modifier.padding(horizontal=22.dp,vertical=16.dp)) {
            PaperButton("Продолжить без входа",onSkip,variant=PaperButtonVariant.SECONDARY)
        }
    }) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
                PaperTextAction("Позже",onSkip)
                Text("Войдите — и Алиса узнает ваш аккаунт",style=PaperType.h1,color=LocalPaper.current.ink)
                PaperNote("Вход необязателен. Рецепты, выпечки, закваска и уведомления работают без него.")
                YandexAccountPanel(state,pendingUnbind,onSignIn,{},onRetryUnbind,size=YandexIdButtonSize.L)
            }
        }
    }
}

private fun accountErrorCopy(kind:YandexAccountError)=when(kind) {
    YandexAccountError.NETWORK->"Нет связи. Войти можно позже — книга, таймеры и закваска работают и так."
    YandexAccountError.YANDEX->"Яндекс ID не ответил. Попробуйте ещё раз — книга работает и без входа."
    YandexAccountError.SERVER->"Не удалось завершить вход в Мадре. Повторите вход в приложении, когда появится связь."
    YandexAccountError.FOREIGN_CLIENT->"Яндекс ID не подтвердил вход для Мадре. Повторите попытку."
    YandexAccountError.STORAGE->"Не удалось сохранить настройки входа. Не закрывайте приложение и повторите попытку."
}

/** Login is optional and never substitutes for the existing explicit bake/voice consent. */
@Composable fun YandexAliceAccountStep(onOpenSettings:()->Unit) {
    val owner=LocalYandexAccountUi.current ?: return
    val state by owner.model.state.collectAsState()
    val pending by owner.model.pendingUnbind.collectAsState()
    PaperNote(if(state is YandexAccountState.SignedIn)"Шаг 1 · вход в Яндекс ID подтверждён"
        else "Шаг 1 · войдите с Яндекс ID, чтобы Алиса могла узнать ваш аккаунт")
    YandexAccountPanel(state,pending,owner.signIn,onOpenSettings,owner.model::retryPendingUnbind)
    PaperNote("Вход в аккаунт не означает, что навык уже связан. Подтверждение на телефоне остаётся доступным.")
}

/** Works without account/voice linking. It does not send a command or invent a Station name. */
@Composable fun StationTimerSheet(remainingSeconds:Long,onDismiss:()->Unit) {
    val phrase=StationTimerPrompt.phrase(remainingSeconds)
    val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(phrase){mutableStateOf(false)}
    PaperSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Таймер на Станции",style=PaperType.h2,color=LocalPaper.current.ink)
            Text(phrase ?: StationTimerPrompt.EXPIRED,style=PaperType.body,color=LocalPaper.current.ink)
            PaperNote(StationTimerPrompt.EXPLANATION)
            if(phrase!=null)PaperButton("Скопировать фразу",{
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(phrase));copied=true
            },variant=PaperButtonVariant.SECONDARY)
            if(copied)PaperNote("Фраза скопирована")
        }
    }
}
