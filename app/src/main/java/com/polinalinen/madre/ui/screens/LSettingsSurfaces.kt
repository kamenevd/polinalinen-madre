package com.polinalinen.madre.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.BuildConfig
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.model.BookDisplaySettings
import com.polinalinen.madre.notifications.MadreNotifier
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.voice.VoiceStatus

/** L1 uses production state and callbacks. Fixture parameters never create an auth authority. */
@Composable internal fun LSettingsContent(
    myName:String,onMyNameChange:(String)->Unit,onBack:()->Unit,onFamily:()->Unit,onAlice:()->Unit,
    starterName:String,onStarterNameChange:(String)->Unit,intervalHours:Int,onInterval:(Int)->Unit,
    reminders:Boolean,onReminders:(Boolean)->Unit,bakes:Int,feedings:Int,preferences:ReadingPreferences,
    legacyFamily:FamilyBookState,display:BookDisplaySettings,onKeepScreen:(Boolean)->Unit,onLockProgress:(Boolean)->Unit,
    displayError:String?,initialPage:String="root",onStarterSettings:(()->Unit)?=null,
    accountOverride:YandexAccountState?=null,aliceStatusOverride:String?=null,
) {
    val owner=LocalYandexAccountUi.current
    val account=accountOverride ?: owner?.model?.state?.collectAsState()?.value ?: YandexAccountState.SignedOut
    val pending=owner?.model?.pendingUnbind?.collectAsState()?.value ?: false
    val notice=owner?.model?.offerError?.collectAsState()?.value
    val profile=(account as? YandexAccountState.SignedIn)?.profile
    val context=LocalContext.current
    val app=context.applicationContext as? MadreApplication
    val voice=app?.voiceCoordinator?.state?.collectAsState()?.value
    val family=LocalFamilyLinkModel.current?.state?.collectAsState()?.value
    val familyTitle=family?.family?.name ?: legacyFamily.account?.takeIf{it.hasFamily}?.familyName ?: "Семейная полка"
    val familyStatus=when {
        family?.busy==true -> "Проверяем семейную полку…"
        family?.failure!=null -> "Не удалось обновить · откройте для повтора"
        family?.family!=null -> "${family.family.members.size} из 8 · ${if(family.owner)"вы владелец" else "вы в семье"}"
        legacyFamily.account?.hasFamily==true -> "Семья подключена · открыть полку"
        legacyFamily.account!=null -> "Прежний аккаунт · доступен перенос"
        profile==null -> "Нужен вход с Яндекс ID"
        else -> "Общая полка для своих"
    }
    val aliceStatus=aliceStatusOverride ?: when(voice?.status) {
        VoiceStatus.CONNECTED -> "Подключена"
        else -> "Нет связи · открыть подключение"
    }
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var starter by rememberSaveable { mutableStateOf(false) }
    var confirmOut by rememberSaveable { mutableStateOf(false) }
    val back={if(page=="root")onBack() else page="root"}
    BackHandler(page!="root") { page="root" }
    LaunchedEffect(profile?.uidHash) {
        if(myName.isBlank()) profile?.displayName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf{it.isNotBlank()}?.let {
            onMyNameChange(it.replaceFirstChar(Char::titlecase))
        }
    }
    LSettingsPage(when(page){"profile"->"Профиль";"look"->"Внешний вид";"notify"->"Уведомления и вибрация";"about"->"О приложении";"dev"->"Для разработчиков";else->"Настройки"},back) {
        displayError?.let{PaperNote(it)}
        when(page) {
            "root" -> {
                if(profile!=null) PaperCard(contentPadding=PaddingValues(0.dp)) {
                    Row(Modifier.fillMaxWidth().then(bookAction("Профиль"){page="profile"}).padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                        PaperMonogram(myName.ifBlank{profile.displayName},size=64.dp)
                        Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                            Text(myName.ifBlank{profile.displayName.ifBlank{profile.login}},style=PaperType.h2,color=LocalPaper.current.ink)
                            Text("Яндекс ID · ${profile.displayName.ifBlank{profile.login}}",style=PaperType.note,color=LocalPaper.current.ink2)
                        }
                        Icon(Icons.Outlined.ChevronRight,null,tint=LocalPaper.current.mut)
                    }
                } else PaperCard {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(64.dp).background(LocalPaper.current.bg2,CircleShape),contentAlignment=Alignment.Center) {
                            Icon(Icons.Outlined.PersonOutline,null,Modifier.size(32.dp),tint=LocalPaper.current.ink2)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Войдите с Яндекс ID",style=PaperType.h2,color=LocalPaper.current.ink)
                            Text("Для семейной полки и Алисы. Книга работает и без входа.",style=PaperType.caption,color=LocalPaper.current.ink2)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if(owner!=null) YandexAccountPanel(account,pending,owner.signIn,{page="profile"},owner.model::retryPendingUnbind)
                    else { YandexIdButton({},enabled=false);PaperNote("Вход недоступен в этой сборке. Книга работает без аккаунта.") }
                }
                LSettingsGroup {
                    LSettingsRow(Icons.Outlined.PeopleOutline,familyTitle,familyStatus,onFamily,true)
                    LSettingsRow(Icons.Outlined.Mic,"Алиса",aliceStatus,onAlice)
                }
                LSettingsGroup {
                    LSettingsRow(Icons.Outlined.Science,"Закваска","$starterName · ${feedingRhythmStatus(intervalHours)}",{onStarterSettings?.invoke() ?: run{starter=true}})
                    LSettingsRow(Icons.Outlined.LightMode,"Внешний вид","${preferences.atmosphere.label} · текст ${preferences.textSize.toInt()}",{page="look"})
                    val allowed=notificationAccess()
                    LSettingsRow(Icons.Outlined.NotificationsNone,"Уведомления и вибрация",
                        if(!allowed)"Уведомления выключены в телефоне" else "напоминания ${if(reminders)"вкл." else "выкл."} · вибрация ${if(preferences.haptics)"вкл." else "выкл."}",{page="notify"})
                }
                LSettingsGroup { LSettingsRow(Icons.Outlined.Info,"О приложении","Мадре ${BuildConfig.VERSION_NAME}",{page="about"}) }
            }
            "profile" -> {
                PaperCard {
                    PaperMonogram(myName.ifBlank{profile?.displayName.orEmpty()},size=64.dp)
                    if(profile!=null) PaperNote("Яндекс ID · ${profile.displayName.ifBlank{profile.login}}")
                    Text("Как вас подписать",style=PaperType.title,color=LocalPaper.current.ink)
                    var draft by rememberSaveable { mutableStateOf(myName) }
                    PaperField(draft,{draft=it.take(80)},label="Ваше имя",keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences))
                    PaperButton("Сохранить имя",{onMyNameChange(draft.trim().replaceFirstChar(Char::titlecase))},variant=PaperButtonVariant.SECONDARY)
                    PaperNote("Это имя появится в книге и на семейной полке.")
                }
                if(profile!=null && owner!=null) PaperTextAction("Выйти из Яндекс ID",{confirmOut=true},destructive=true)
                else if(owner!=null)YandexAccountPanel(account,pending,owner.signIn,{ },owner.model::retryPendingUnbind)
                notice?.let{PaperNote(it)}
            }
            "look" -> LLookContent(preferences,display,onKeepScreen)
            "notify" -> LNotifyContent(preferences,reminders,onReminders,display,onLockProgress)
            "about" -> {
                PaperCard {
                    Text("Мадре",style=PaperType.h1,color=LocalPaper.current.ink)
                    PaperNote("Версия ${BuildConfig.VERSION_NAME}")
                    PaperNote("Книга рецептов на закваске. Ваша книга работает без аккаунта и сети.")
                    PaperListRow("В журнале",note="$bakes выпечек · $feedings кормлений")
                }
                if(BuildConfig.QA_DIAGNOSTICS)LSettingsGroup { LSettingsRow(Icons.Outlined.Code,"Для разработчиков","Только QA-сборка",{page="dev"}) }
            }
            "dev" -> if(BuildConfig.QA_DIAGNOSTICS)LDeveloperContent(account,aliceStatus)
        }
    }
    if(starter)StarterSettingsSheet(starterName,onStarterNameChange,intervalHours,onInterval,reminders,onReminders){starter=false}
    if(confirmOut && owner!=null)YandexSignOutDialog({confirmOut=false;page="root";owner.model.signOut()},{confirmOut=false})
}

internal fun feedingRhythmStatus(hours:Int)=when(hours){12->"кормить каждые 12 ч";24->"кормить раз в сутки";48->"кормить раз в 2 дня";72->"кормить раз в 3 дня";168->"кормить раз в неделю";else->"кормить каждые $hours ч"}

@Composable internal fun LSettingsPage(title:String,onBack:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().paperBackground().paperContentInsets().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            PaperIconButton(onBack,label="Назад") { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад",tint=LocalPaper.current.ink) }
            Text(title,style=PaperType.h2,color=LocalPaper.current.ink,modifier=Modifier.weight(1f).padding(end=12.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable private fun LSettingsGroup(content:@Composable ColumnScope.()->Unit) {
    PaperCard(Modifier.testTag("settings-group"),contentPadding=PaddingValues(0.dp),content=content)
}
@Composable private fun LSettingsRow(icon:ImageVector,title:String,status:String,onClick:()->Unit,accent:Boolean=false) {
    val p=LocalPaper.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=62.dp).testTag("settings-row-$title")
            .then(bookAction(title,onClick=onClick)).padding(horizontal=14.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(36.dp).background(if(accent)p.acc2 else p.bg2,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                Icon(icon,null,Modifier.size(22.dp),tint=if(accent)p.acc else p.ink2)
            }
            Column(Modifier.weight(1f)) {
                Text(title,style=PaperType.ui,color=p.ink)
                Text(status,style=PaperType.caption,color=p.ink2,modifier=Modifier.padding(top=3.dp))
            }
            Icon(Icons.Outlined.ChevronRight,null,tint=p.mut,modifier=Modifier.size(20.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    }
}
@Composable internal fun LLookContent(preferences:ReadingPreferences,display:BookDisplaySettings,onKeepScreen:(Boolean)->Unit) {
    Text("Тема",style=PaperType.title,color=LocalPaper.current.ink)
    PaperAtmospherePicker(preferences)
    PaperNote("Авто — по тёмной теме телефона.")
    PaperCard {
        Text("Текст рецепта · ${preferences.textSize.toInt()}",style=PaperType.title,color=LocalPaper.current.ink)
        Row(verticalAlignment=Alignment.CenterVertically){Text("А",style=PaperType.body,color=LocalPaper.current.ink);PaperSlider(preferences.textSize,preferences::setSize,valueRange=14f..22f,steps=7,modifier=Modifier.weight(1f).testTag("look-text-size"));Text("А",style=PaperType.h2,color=LocalPaper.current.ink)}
        Text("Замесите тесто и оставьте на 30 минут под плёнкой.",style=PaperType.body.copy(fontSize=preferences.textSize.sp),color=LocalPaper.current.ink)
    }
    Text("Как листать рецепт",style=PaperType.title,color=LocalPaper.current.ink)
    PaperSegmented(listOf("Страницами","Прокруткой"),if(preferences.mode==ReadingMode.SCROLL)1 else 0,
        {preferences.chooseMode(if(it==0)ReadingMode.BOOK else ReadingMode.SCROLL)},label="Как читать рецепт")
    PaperCard {
        PaperSettingSwitch("Меньше движения",preferences.reducedMotion,preferences::setReduced,note="Без анимаций листания")
        PaperSettingSwitch("Не гасить экран",display.keepScreenOn,onKeepScreen,note="Пока открыт рецепт или таймер")
    }
}
@Composable private fun notificationAccess():Boolean {
    val context=LocalContext.current
    var allowed by remember{mutableStateOf(MadreNotifier(context).canPost())}
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){allowed=MadreNotifier(context).canPost()}
    return allowed
}
@Composable internal fun LNotifyContent(preferences:ReadingPreferences,reminders:Boolean,onReminders:(Boolean)->Unit,display:BookDisplaySettings,onLockProgress:(Boolean)->Unit) {
    val context=LocalContext.current
    val view=LocalView.current
    val allowed=notificationAccess()
    PaperCard {
        if(allowed) {
            PaperSettingSwitch("Напоминать о закваске",reminders,onReminders)
            PaperSettingSwitch("Ход выпечки на экране блокировки",display.lockScreenProgress,onLockProgress,note="Шаг и время без разблокировки")
        } else {
            Text("Уведомления выключены в телефоне",style=PaperType.title,color=LocalPaper.current.ink)
            PaperNote("Напоминания и ход выпечки появятся после разрешения. Таймер в книге работает как прежде.")
            PaperButton("Разрешить",{openNotificationSettings(context)},variant=PaperButtonVariant.SECONDARY)
        }
        PaperListRow("Звук таймера",note="В настройках телефона",onClick={openTimerSoundSettings(context)},trailing={Icon(Icons.Outlined.ChevronRight,null)})
    }
    PaperCard {
        PaperSettingSwitch("Вибрация при касаниях",preferences.haptics,preferences::chooseHaptics,note="Кнопки, таймер, листание")
        PaperNote("Как ощущается — короткий толчок")
        PaperButton("Проверить",{performPaperHaptic(context,view,enabled=true)},haptic=false,variant=PaperButtonVariant.TEXT)
    }
    PaperNote("Не чувствуете? Проверьте вибрацию касаний в настройках телефона. Запрос Android не гарантирует отклик мотора.")
}
private fun openNotificationSettings(context:Context) {
    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The exact same sheet is mounted by NavHost for both Settings and the starter tab. */
@Composable fun StarterSettingsSheet(name:String,onName:(String)->Unit,interval:Int,onInterval:(Int)->Unit,reminders:Boolean,onReminders:(Boolean)->Unit,onDismiss:()->Unit) {
    PaperSheet(onDismiss) { StarterSettingsContent(name,onName,interval,onInterval,reminders,onReminders,onDismiss) }
}
@Composable internal fun StarterSettingsContent(name:String,onName:(String)->Unit,interval:Int,onInterval:(Int)->Unit,reminders:Boolean,onReminders:(Boolean)->Unit,onDismiss:()->Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=22.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Закваска",style=PaperType.h2,color=LocalPaper.current.ink)
        Text("Как зовут закваску",style=PaperType.title,color=LocalPaper.current.ink)
        var draft by rememberSaveable { mutableStateOf(name) }
        StarterNameField(draft,{draft=it})
        Text("Кормить",style=PaperType.title,color=LocalPaper.current.ink)
        val hours=listOf(12,24,48,72,168)
        PaperSegmented(listOf("12 ч","сутки","2 дня","3 дня","неделя"),hours.indexOf(interval),{onInterval(hours[it])},label="Ритм кормления")
        PaperNote("Из холодильника (4–6 °C) — раз в 3–5 дней. Ориентир, не замена вашему ритму.")
        PaperSettingSwitch("Напоминать",reminders,onReminders)
        Text("В день кормления, если телефон разрешает уведомления",style=PaperType.caption,color=LocalPaper.current.ink2)
        PaperButton("Готово",{onName(draft.trim().replaceFirstChar(Char::titlecase));onDismiss()},variant=PaperButtonVariant.SECONDARY)
    }
}
@Composable private fun LDeveloperContent(account:YandexAccountState,alice:String) {
    val context=LocalContext.current
    LaunchedEffect(Unit){val probe=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){AndroidPaperHapticPlatform(context,null).probe()};val current=PaperHapticDiagnostics.state;PaperHapticDiagnostics.report(current.copy(hasVibrator=probe.hasVibrator,amplitudeControl=probe.amplitudeControl))}
    Text("Только QA-сборка. В релизе этого экрана нет.",style=PaperType.note,color=LocalPaper.current.ink2)
    PaperCard { Text("Вибрация · диагностика",style=PaperType.title,color=LocalPaper.current.ink);PaperHapticDiagnosticRows(PaperHapticDiagnostics.state,true) }
    PaperCard { val monitor=com.polinalinen.madre.performance.LocalQaFrameMonitor.current
        if(monitor!=null)PaperSettingSwitch("Показывать FPS",monitor.enabled,monitor::chooseEnabled,note="JankStats, локально")
        else PaperNote("JankStats недоступен в этом окружении")
    }
    var numbers by remember { mutableStateOf("Читаем локальные номера…") }
    LaunchedEffect(Unit) {
        val app=context.applicationContext as? MadreApplication
        if(app==null) {numbers="Локальное хранилище недоступно";return@LaunchedEffect}
        numbers=try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val active=app.activeBakeRepository.all().map{it.bakeNumber}
            com.polinalinen.madre.navigation.BookPreferences(context).use { book ->
                val held=(active+book.snapshotPlans().map{it.number}+app.bakeNumbers.finales().map{it.number}).filter{it in 1..9}.distinct().sorted()
                val now=System.currentTimeMillis()
                val cooling=app.bakeNumbers.freed().filter{(n,t)->n !in held && t!=Long.MIN_VALUE && (now<t || now-t<com.polinalinen.madre.model.BakeNumbers.COOLDOWN_MILLIS)}.keys.sorted()
                "Занятые: ${held.joinToString { "№$it" }.ifEmpty{"нет"}} · остывают: ${cooling.joinToString { "№$it" }.ifEmpty{"нет"}}"
            }
        } } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch(_:Exception){"Не удалось прочитать локальные номера"}
    }
    PaperCard {Text("Номера выпечек",style=PaperType.title,color=LocalPaper.current.ink);PaperNote(numbers)}
    PaperCard { Text("Синхронизация",style=PaperType.title,color=LocalPaper.current.ink);SyncStatusLine();PaperNote("Яндекс ID: ${if(account is YandexAccountState.SignedIn)"вход подтверждён" else "нет входа"} · Алиса: $alice");PaperNote("Сборка ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Room 12") }
}
