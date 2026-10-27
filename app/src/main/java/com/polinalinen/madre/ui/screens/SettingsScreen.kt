package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.notifications.MadreNotifier
import com.polinalinen.madre.sourdough.FeedingInterval
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.account.PasswordReset
import com.polinalinen.madre.account.PasswordResetNotice
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.Bookplate
import com.polinalinen.madre.ui.components.MinTouchTarget
import com.polinalinen.madre.sync.SyncStatus
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.TapCycle
import com.polinalinen.madre.ui.components.TapCycleRow
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.kit.CalmModeSetting
import com.polinalinen.madre.viewmodel.FamilySettingsViewModel
import com.polinalinen.madre.viewmodel.FamilyBookViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Настройки — «Выходные данные» (колофон книги, DESIGN-V4.md экран 8).
 *
 * «Ваше имя» — SharedPreferences (см. MadreNavHost — тот же механизм, что уже
 * держит избранное). Оно подставляется в автограф на титульной и в подпись
 * корешка на Полке.
 *
 * Cycle 11: «Кормить» и «Напоминания» перестали быть UI-стейтом этого экрана и
 * пишут в sourdough_configs (intervalHours / remindersEnabled) — тот же конфиг,
 * от которого считается фаза закваски и планируется напоминание. «Вид» —
 * спокойный режим, madre_prefs (CalmModeSetting).
 *
 * Cycle 19: пять разделов вместо одной ленты. До этого страница была
 * четырнадцатью строками подряд — имя, экслибрис, почта и пароль, закваска,
 * кормление, напоминания, оформление, тираж, версия, корешок, — а в самом
 * конце ещё эссе про уведомления, спорившее со строкой «Напоминания: вкл» на
 * семь экранов выше. Ни одной новой настройки здесь не появилось: это
 * перекладка, а не рост.
 *
 *  1. Я — как зовут читателя и как подписана книга
 *  2. Закваска — имя, ритм кормления, напоминания
 *  3. Полка — одна строка с названием, дальше свой экран
 *  4. Вид — спокойное или живое оформление
 *  5. О книге — журнал и версия
 */
@Composable
fun SettingsScreen(
    myName: String,
    onMyNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenShelfSettings: () -> Unit = {},
    starterName: String = StarterName.DEFAULT,
    onStarterNameChange: (String) -> Unit = {},
    bakeCount: Int = 0,
    feedingCount: Int = 0,
    intervalHours: Int = 24,
    onIntervalHoursChange: (Int) -> Unit = {},
    remindersEnabled: Boolean = true,
    onRemindersEnabledChange: (Boolean) -> Unit = {},
    calmMode: Boolean = CalmModeSetting.DEFAULT,
    onCalmModeChange: (Boolean) -> Unit = {},
    familySettingsViewModel: FamilySettingsViewModel = viewModel(),
    familyBookViewModel: FamilyBookViewModel = viewModel(),
    onOpenAlice: () -> Unit = {},
    readingPreferences: ReadingPreferences = rememberReadingPreferences(),
    onOpenStarterSettings:(()->Unit)?=null,
) {
    val colors = LocalPaper.current
    val familyName by familySettingsViewModel.familyName.collectAsState()
    val displaySettings by familySettingsViewModel.bookDisplaySettings.collectAsState()
    val displayError by familySettingsViewModel.displayError.collectAsState()
    val familyBookState by familyBookViewModel.state.collectAsState()
    LaunchedEffect(Unit) { familyBookViewModel.restore() }
    // Книга в семье — значит, её имя живёт в карточке общей книги, и второму
    // полю для того же имени тут делать нечего.
    val inFamily = familyBookState.account?.hasFamily == true

    SettingsContent(myName, onMyNameChange, onBack, onOpenShelfSettings, starterName,
        onStarterNameChange, bakeCount, feedingCount, intervalHours, onIntervalHoursChange,
        remindersEnabled, onRemindersEnabledChange, calmMode, onCalmModeChange,
        familyName, familyBookState, familySettingsViewModel::setFamilyName, familyBookViewModel::signOut, onOpenAlice,
        readingPreferences, displaySettings = displaySettings,
        onKeepScreenOnChange = { familySettingsViewModel.setKeepScreenOn(it) },
        onLockScreenProgressChange = { familySettingsViewModel.setLockScreenProgress(it) }, displayError = displayError,
        onOpenStarterSettings=onOpenStarterSettings)
}

/** Same production layout; fixtures can supply state without touching accounts/network. */
@Composable internal fun SettingsContent(
    myName: String, onMyNameChange: (String) -> Unit, onBack: () -> Unit,
    onOpenShelfSettings: () -> Unit, starterName: String, onStarterNameChange: (String) -> Unit,
    bakeCount: Int, feedingCount: Int, intervalHours: Int, onIntervalHoursChange: (Int) -> Unit,
    remindersEnabled: Boolean, onRemindersEnabledChange: (Boolean) -> Unit,
    calmMode: Boolean, onCalmModeChange: (Boolean) -> Unit,
    familyName: String?, familyBookState: FamilyBookState,
    onSetFamilyName: (String) -> Unit, onSignOut: () -> Unit, onOpenAlice: () -> Unit = {},
    preferences: ReadingPreferences = rememberReadingPreferences(), initialSearch: String = "",
    displaySettings: com.polinalinen.madre.model.BookDisplaySettings = LocalBookDisplaySettings.current,
    onKeepScreenOnChange: (Boolean) -> Unit = {}, onLockScreenProgressChange: (Boolean) -> Unit = {},
    displayError: String? = null,accountContent:(@Composable ()->Unit)?=null,
    initialPage:String="root",onOpenStarterSettings:(()->Unit)?=null,
    accountOverride:com.polinalinen.madre.account.yandex.YandexAccountState?=null,aliceStatusOverride:String?=null,
) {
    LSettingsContent(myName,onMyNameChange,onBack,onOpenShelfSettings,onOpenAlice,
        starterName,onStarterNameChange,intervalHours,onIntervalHoursChange,remindersEnabled,onRemindersEnabledChange,
        bakeCount,feedingCount,preferences,familyBookState,displaySettings,onKeepScreenOnChange,onLockScreenProgressChange,
        displayError,initialPage=initialPage,onStarterSettings=onOpenStarterSettings,
        accountOverride=accountOverride,aliceStatusOverride=aliceStatusOverride)
}

/**
 * Названия разделов — в одном месте, потому что их читает не только экран.
 * Тест на пять заголовков сверяется с этими же строками: разъехаться списку и
 * проверке нельзя, иначе проверка перестанет что-либо значить.
 */
internal object Sections {
    const val ME = "Я"
    const val STARTER = "Закваска"
    const val FAMILY = "Полка"
    const val LOOK = "Чтение"
    const val NOTIFICATIONS = "Уведомления"
    const val ABOUT = "О книге"

    val ALL = listOf(ME, STARTER, FAMILY, LOOK, NOTIFICATIONS, ABOUT)
}

/**
 * Раздел колофона: тяжёлая линейка, надзаголовок, содержимое. Первый раздел
 * идёт без своей линейки — над ним уже стоит та, что отбивает заголовок
 * страницы.
 */
@Composable
private fun SettingsSection(
    title: String,
    first: Boolean = false,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    PaperSection(title, headingModifier = Modifier.padding(horizontal = 22.dp)) {
        Column(Modifier.padding(horizontal = 22.dp)) { content() }
    }
}

/** Every option is visible; choosing a value is not a hidden tap-cycle. */
@Composable
private fun FeedingRhythmRow(intervalHours: Int, onChange: (Int) -> Unit) {
    val hours = listOf(12, 24, 48, 72, 168)
    SettingsCaption("Ритм кормления")
    PaperSegmented(listOf("12 ч", "сутки", "2 дня", "3 дня", "неделя"), hours.indexOf(intervalHours),
        { onChange(hours[it]) }, label = "Ритм кормления")
    SettingsCaption(
        "Руководство Levito Madre: при хранении на средней полке холодильника 4–6°C — каждые 3–5 дней. Это ориентир, не замена вашему ритму.",
    )
}

/**
 * Cycle 19: напоминания и запрет телефона — одной строкой.
 *
 * Раньше «Напоминания: вкл» стояло здесь, а внизу страницы отдельная секция
 * сообщала, что уведомления вообще запрещены. Две правды в одном колофоне, и
 * ближняя к переключателю — ложная. Теперь если телефон молчит, это сказано
 * ровно там, где человек ищет переключатель, и там же дорога обратно.
 *
 * Состояние перечитывается на каждое возвращение на экран: человек мог
 * поменять его в системных настройках и вернуться.
 */
@Composable
private fun RemindersRow(remindersEnabled: Boolean, onRemindersEnabledChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(MadreNotifier(context).canPost()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = MadreNotifier(context).canPost()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (allowed) {
        PaperSettingSwitch("Напоминания", remindersEnabled, onRemindersEnabledChange)
    } else {
        // Переключать нечего: телефон запретил книге писать, и «вкл» здесь было
        // бы обещанием, которого книга не сдержит.
        PaperListRow("Напоминания",note="не разрешены телефоном",onClick=null,compact=true)
        SettingsCaption(
            "книга не разбудит вас, когда закончится расстойка, не напомнит про закваску " +
                "и не покажет ход выпечки в шторке. Таймер на экране при этом работает как прежде."
        )
        PaperButton(
            label = "Разрешить уведомления",
            variant = PaperButtonVariant.SECONDARY,
            modifier = Modifier.padding(vertical = 4.dp),
            onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Экран настроек уведомлений есть не на всех прошивках —
                // если его нет, книга не должна падать из-за этого.
                runCatching { context.startActivity(intent) }
            },
        )
    }
}

/** Same persisted calm-mode callback, exposed as a real switch. */
@Composable
private fun CalmModeRow(calmMode: Boolean, onCalmModeChange: (Boolean) -> Unit) {
    PaperSettingSwitch("Спокойный режим", calmMode, onCalmModeChange, note = CalmModeSetting.label(calmMode))
}

internal fun openTimerSoundSettings(context: Context) {
    val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, MadreNotifier.CHANNEL_BAKING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(channel) }.isFailure) runCatching { context.startActivity(app) }
}

/**
 * Cycle 17: чем кончилась последняя отправка в общую книгу.
 *
 * Отправка идёт из WorkManager, уже после того как человек ушёл с экрана
 * «Испечено», — сказать ему о неудаче в тот момент нечем, кроме тоста посреди
 * чужого приложения. Здесь же он и так читает, что в книге работает, а что
 * нет. Молчит всё остальное время: «доставлено» — не новость, а шум.
 *
 * Читается один раз на открытие колофона — это SharedPreferences, а не поток
 * событий, и обновлять строку раз в секунду не за чем следить.
 */
@Composable
internal fun SyncStatusLine(modifier: Modifier = Modifier) {
    val colors = LocalPaper.current
    val context = LocalContext.current
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        line = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(SyncStatus.PREFS, Context.MODE_PRIVATE)
            SyncStatus.line(SyncStatus.read(prefs))
        }
    }
    val text = line ?: return

    Text(
        text,
        color = colors.ink2,style = PaperType.body,
        fontStyle = FontStyle.Italic,
        fontSize = 12.sp,
        modifier = modifier,
    )
}

/** Optional online family book. The local Room book remains available in every state. */
@Composable
internal fun FamilyBookSection(
    state: FamilyBookState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onCreateFamily: (String) -> Unit,
    onJoinFamily: (String) -> Unit,
    passwordResetNotice: PasswordResetNotice = PasswordResetNotice.Idle,
    onRequestPasswordReset: (String) -> Unit = {},
) {
    val sdk = com.polinalinen.madre.account.yandex.LocalYandexAccountUi.current
    Column(Modifier.padding(horizontal=22.dp,vertical=16.dp)) {
        PaperNote(if(state.account!=null) "Прежний аккаунт сохранён. Войдите с Яндекс ID для переноса семейной полки. Книга и выпечки останутся на телефоне."
            else "Семейная полка доступна после входа с Яндекс ID. Книга работает и без входа.")
        if(sdk!=null) com.polinalinen.madre.ui.kit.YandexIdButton({sdk.signIn(false)})
        else PaperNote("Вход недоступен в этой сборке.")
    }
}

@Composable
internal fun FamilyBookField(
    label: String,
    value: String,
    masked: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    PaperField(value = value, onValueChange = onValueChange, label = label,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = when {
            masked->KeyboardOptions(keyboardType=KeyboardType.Password)
            label=="Название полки" || label=="Ваше имя"->KeyboardOptions(capitalization=androidx.compose.ui.text.input.KeyboardCapitalization.Words)
            else->KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

/** Пояснение под строкой настроек — тем же голосом, что подписи в колофоне. */
@Composable
private fun SettingsCaption(text: String) {
    val colors = LocalPaper.current
    Text(
        text,
        color = colors.ink2,style = PaperType.caption,
        maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingsField(label: String, caption: String, field: @Composable () -> Unit) {
    val colors = LocalPaper.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        PageLabel(label, color = colors.ink)
        Box(Modifier.padding(top = 4.dp)) { field() }
        Text(
            caption,
            color = colors.ink2,style = PaperType.body,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Cycle 14: поле имени закваски.
 *
 * Показывает СВОЙ черновик, а не то, что уже лежит в Room. Иначе стёртая под
 * ноль строка мгновенно возвращалась бы словом «Мадре» прямо под пальцем:
 * пустое имя книга не хранит, и сохранённое значение спорило бы с набираемым.
 * Наружу уходит каждое изменение — приведением к общему виду занимается
 * SourdoughRepository, поэтому в базе не окажется ни пустой строки, ни абзаца.
 */
@Composable
internal fun StarterNameField(persisted: String, onChange: (String) -> Unit) {
    // null — человек ещё не трогал поле, и в нём стоит сохранённое имя. Как
    // только он начал править, поле принадлежит ему целиком: строка, стёртая
    // под ноль, обязана остаться пустой, хотя в базе к этому моменту уже лежит
    // «Мадре» (пустого имени книга не хранит). Пересобирать черновик по
    // сохранённому значению значило бы подставлять «Мадре» прямо под палец.
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    NameField(
        value = draft ?: persisted,
        onChange = { typed ->
            val trimmed = typed.take(StarterName.MAX_LENGTH)
            draft = trimmed
            onChange(trimmed)
        },
        placeholder = StarterName.DEFAULT,
        capitalization=androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, placeholder: String,
    capitalization:androidx.compose.ui.text.input.KeyboardCapitalization=androidx.compose.ui.text.input.KeyboardCapitalization.Words) {
    PaperField(value, onChange, placeholder = placeholder, modifier = Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(capitalization=capitalization))
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: (() -> Unit)?, valueColor: androidx.compose.ui.graphics.Color? = null) {
    PaperListRow(label, value = value,
        valueColor = valueColor ?: LocalPaper.current.acc, onClick = onClick,compact=true)
}
