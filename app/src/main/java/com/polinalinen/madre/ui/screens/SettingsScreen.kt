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
        onLockScreenProgressChange = { familySettingsViewModel.setLockScreenProgress(it) }, displayError = displayError)
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
) {
    val inFamily = familyBookState.account?.hasFamily == true
    var query by rememberSaveable { mutableStateOf(initialSearch) }
    var showReadingSettings by remember { mutableStateOf(false) }
    val hasAccount = accountContent!=null || com.polinalinen.madre.account.yandex.LocalYandexAccountUi.current != null
    val visible = remember(query, hasAccount, inFamily) { SettingsEntry.entries.filter {
        (it != SettingsEntry.ACCOUNT || hasAccount) && (it != SettingsEntry.BOOKPLATE || !inFamily) && settingsMatches(it, query)
    }.toSet() }
    fun shown(vararg entries: SettingsEntry) = entries.any { it in visible }
    PaperScaffold(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        item {
        Column(Modifier.padding(bottom = 32.dp)) {
            PaperHeader("Настройки", onBack = onBack, overline = "Книга", modifier = Modifier.padding(horizontal = 22.dp))
            Text("выходные данные книги", color = LocalPaper.current.ink2, style = PaperType.note,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            PaperSearchField(query, { query = it }, Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
            if (displayError != null) PaperNote(displayError)
            if (shown(SettingsEntry.ACCOUNT)) {
                if(accountContent!=null)accountContent() else com.polinalinen.madre.account.yandex.YandexSettingsSection(myName,onMyNameChange,onOpenAlice)
            }
            if (visible.isEmpty()) PaperEmpty("Такой настройки нет", "Попробуй другое слово или очисти поиск.",
                action = { PaperButton("Очистить поиск", { query = "" }, Modifier.padding(horizontal = 22.dp)) })
            if (shown(SettingsEntry.NAME, SettingsEntry.BOOKPLATE)) SettingsSection(Sections.ME, first = true) {
                if (shown(SettingsEntry.NAME)) SettingsField("Ваше имя", "появится на титульной странице и на Полке") {
                    NameField(value = myName, onChange = onMyNameChange, placeholder = "впишите, как вас называть")
                }
                // Preserve the existing local ex libris; never replace the family's server-owned name.
                if (shown(SettingsEntry.BOOKPLATE)) {
                    Bookplate(familyName = familyName, onSetName = onSetFamilyName,
                        modifier = Modifier.padding(vertical = 18.dp), label = "Как подписана книга")
                }
            }
            if (shown(SettingsEntry.STARTER_NAME, SettingsEntry.RHYTHM, SettingsEntry.REMINDERS)) SettingsSection(Sections.STARTER) {
                if (shown(SettingsEntry.STARTER_NAME)) SettingsField("Имя закваски", "в дневнике и напоминаниях") {
                    StarterNameField(persisted = starterName, onChange = onStarterNameChange)
                }
                if (shown(SettingsEntry.RHYTHM)) FeedingRhythmRow(intervalHours, onIntervalHoursChange)
                if (shown(SettingsEntry.REMINDERS)) RemindersRow(remindersEnabled, onRemindersEnabledChange)
            }
            if (shown(SettingsEntry.SHELF)) SettingsSection(Sections.FAMILY) {
                val account = familyBookState.account
                val shelfLabel = account?.takeIf { it.hasFamily }?.familyName?.ifBlank { null }
                    ?: if (account != null) "Завести полку" else "Подключить полку"
                SettingsRow(shelfLabel, "›", onOpenShelfSettings)
                if (account != null) TextAction("Выйти · книга на телефоне останется", onClick = onSignOut)
            }
            if (shown(SettingsEntry.THEME, SettingsEntry.READING_MODE, SettingsEntry.TEXT_SIZE, SettingsEntry.KEEP_SCREEN, SettingsEntry.CALM)) SettingsSection(Sections.LOOK) {
                if (shown(SettingsEntry.THEME)) {
                    SettingsCaption("Тема · ${preferences.atmosphere.label}")
                    PaperAtmospherePicker(preferences)
                }
                if (shown(SettingsEntry.READING_MODE)) {
                    SettingsCaption("Как читать рецепт")
                    PaperSegmented(listOf("Листать страницы", "Прокрутка"), if (preferences.mode == ReadingMode.SCROLL) 1 else 0,
                        { preferences.chooseMode(if (it == 0) ReadingMode.BOOK else ReadingMode.SCROLL) }, label = "Как читать рецепт")
                }
                if (shown(SettingsEntry.TEXT_SIZE)) SettingsRow("Размер текста", "${preferences.textSize.toInt()} · Аа", { showReadingSettings = true })
                if (shown(SettingsEntry.KEEP_SCREEN)) PaperSettingSwitch("Не гасить экран", displaySettings.keepScreenOn,
                    onKeepScreenOnChange, note = "Пока открыты рецепт или таймер")
                if (shown(SettingsEntry.CALM)) {
                    CalmModeRow(calmMode, onCalmModeChange)
                    SettingsCaption("В спокойном режиме нет непрерывных декораций. Бумага, рамки и следы на страницах остаются.")
                }
            }
            if (shown(SettingsEntry.TIMER_SOUND, SettingsEntry.LOCK_PROGRESS)) SettingsSection(Sections.NOTIFICATIONS) {
                val context = LocalContext.current
                if (shown(SettingsEntry.LOCK_PROGRESS)) PaperSettingSwitch("Ход выпечки на экране блокировки",
                    displaySettings.lockScreenProgress, onLockScreenProgressChange,
                    note = "Текущий шаг и время без разблокировки. Если выключено — только «Выпечка идёт».")
                if (shown(SettingsEntry.TIMER_SOUND)) SettingsRow("Звук таймера", "Настройки телефона", { openTimerSoundSettings(context) })
            }
            if (shown(SettingsEntry.ALICE, SettingsEntry.JOURNAL, SettingsEntry.VERSION)) SettingsSection(Sections.ABOUT) {
                if (shown(SettingsEntry.ALICE)) SettingsRow("Алиса на кухне", "›", onOpenAlice)
                if (shown(SettingsEntry.JOURNAL)) SettingsRow("В журнале", "$bakeCount выпечек · $feedingCount кормлений", null)
                if (shown(SettingsEntry.VERSION)) SettingsRow("Версия", com.polinalinen.madre.BuildConfig.VERSION_NAME, null)
            }
        }
        }
    }
    if (showReadingSettings) com.polinalinen.madre.ui.razvorot.ReadingSettings(preferences) { showReadingSettings = false }
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
    Spacer(Modifier.height(if (first) 12.dp else 28.dp))
    PaperSection(title, headingModifier = Modifier.padding(horizontal = 22.dp)) {
        PaperCard(Modifier.padding(horizontal = 22.dp)) { content() }
    }
}

/** Every option is visible; choosing a value is not a hidden tap-cycle. */
@Composable
private fun FeedingRhythmRow(intervalHours: Int, onChange: (Int) -> Unit) {
    val hours = listOf(12, 24, 48, 72, 168)
    SettingsCaption("Ритм кормления")
    PaperSegmented(listOf("12 ч", "сутки", "2 дня", "3 дня", "неделя"), hours.indexOf(intervalHours),
        { onChange(hours[it]) }, label = "Ритм кормления")
    Text(
        "Руководство Levito Madre: при хранении на средней полке холодильника 4–6°C — каждые 3–5 дней. Это ориентир, не замена вашему ритму.",
        color = LocalPaper.current.ink2,style = PaperType.body,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 4.dp),
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
    val colors = LocalPaper.current
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
        SettingsRow(
            label = "Напоминания",
            value = "не разрешены телефоном",
            valueColor = colors.rib,
            onClick = null,
        )
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
    val colors = LocalPaper.current
    // rememberSaveable, а не remember: пересоздание Activity не должно стирать
    // набранные почту, пароль, подпись, название и код.
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var familyName by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    val failed = state as? FamilyBookState.Failed
    val account = state.account
    val loading = state is FamilyBookState.Loading
    // Cycle 19: пока семья не подключена, форма входа не разворачивается сама.
    // Почта, пароль и подпись занимали треть колофона у всех, включая тех, кто
    // общей книгой не пользуется и не собирается: это одна строка и кнопка.
    var showForm by rememberSaveable { mutableStateOf(false) }
    // Неудачная попытка входа — не повод свернуть набранное обратно: сообщение
    // об ошибке над пустым местом не объяснило бы ничего. Именно раскрыть, а не
    // «считать раскрытым»: иначе «Не сейчас» под формой перестал бы работать.
    LaunchedEffect(failed) { if (failed != null) showForm = true }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text(
            "ваша книга остаётся на этом телефоне и открывается без аккаунта и сети. " +
                "Полка — способ делиться выпечками с семьёй, а не условие работы.",
            color = colors.ink2,style = PaperType.body,
            fontStyle = FontStyle.Italic,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (failed != null) {
            Text(
                failed.failure.message,
                color = colors.rib,style = PaperType.body,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        when {
            loading -> Text("проверяем полку", color = colors.ink2,style = PaperType.body, fontStyle = FontStyle.Italic)
            account == null && !showForm -> {
                PaperButton(
                    label = "Подключить полку…",
                    variant = PaperButtonVariant.SECONDARY,
                    onClick = { showForm = true },
                )
            }
            account == null -> {
                FamilyBookField("Почта", email) { email = it }
                FamilyBookField("Пароль", password, masked = true) { password = it }
                TextAction(
                    PasswordReset.ACTION_LABEL,
                    enabled = passwordResetNotice !is PasswordResetNotice.Sending,
                    onClick = { onRequestPasswordReset(email) },
                )
                when (val notice = passwordResetNotice) {
                    PasswordResetNotice.Sent -> Text(
                        PasswordReset.SENT_LINE,
                        color = colors.ink2,style = PaperType.body,
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    is PasswordResetNotice.Failed -> Text(
                        notice.message,
                        color = colors.rib,style = PaperType.body,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    else -> Unit
                }
                FamilyBookField("Как подписать вас", displayName) { displayName = it }
                Spacer(Modifier.height(8.dp))
                PaperButton(
                    label = "Войти",
                    enabled = email.isNotBlank() && password.isNotBlank(),
                    onClick = { onSignIn(email, password) },
                )
                Spacer(Modifier.height(8.dp))
                PaperButton(
                    label = "Зарегистрироваться",
                    variant = PaperButtonVariant.SECONDARY,
                    enabled = email.isNotBlank() && password.isNotBlank() && displayName.isNotBlank(),
                    onClick = { onRegister(email, password, displayName) },
                )
                TextAction("Не сейчас", onClick = { showForm = false })
            }
            !account.hasFamily -> {
                Text("вы вошли как ${account.displayName.ifBlank { account.email }}", color = colors.ink,style = PaperType.body)
                FamilyBookField("Название полки", familyName) { familyName = it }
                PaperButton(
                    label = "Завести полку",
                    enabled = familyName.isNotBlank(),
                    onClick = { onCreateFamily(familyName) },
                )
                Spacer(Modifier.height(12.dp))
                FamilyBookField("Код приглашения", inviteCode) { inviteCode = it }
                PaperButton(
                    label = "Вступить по коду",
                    variant = PaperButtonVariant.SECONDARY,
                    enabled = inviteCode.isNotBlank(),
                    onClick = { onJoinFamily(inviteCode) },
                )
            }
        }
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
        keyboardOptions = if (masked) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

/** Пояснение под строкой настроек — тем же голосом, что подписи в колофоне. */
@Composable
private fun SettingsCaption(text: String) {
    val colors = LocalPaper.current
    Text(
        text,
        color = colors.ink2,style = PaperType.body,
        fontStyle = FontStyle.Italic,
        fontSize = 11.5.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun SettingsField(label: String, caption: String, field: @Composable () -> Unit) {
    val colors = LocalPaper.current
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        PageLabel(label, color = colors.ink)
        Box(Modifier.padding(top = 8.dp)) { field() }
        Text(
            caption,
            color = colors.ink2,style = PaperType.body,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
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
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, placeholder: String) {
    PaperField(value, onChange, placeholder = placeholder, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: (() -> Unit)?, valueColor: androidx.compose.ui.graphics.Color? = null) {
    PaperListRow(label, value = value,
        valueColor = valueColor ?: LocalPaper.current.acc, onClick = onClick)
}
