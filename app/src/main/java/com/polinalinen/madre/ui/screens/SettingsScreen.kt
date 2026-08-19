package com.polinalinen.madre.ui.screens

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
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
import com.polinalinen.madre.account.InviteCode
import com.polinalinen.madre.account.PasswordReset
import com.polinalinen.madre.account.PasswordResetNotice
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BookButtonVariant
import com.polinalinen.madre.ui.components.Bookplate
import com.polinalinen.madre.ui.components.MinTouchTarget
import com.polinalinen.madre.sync.SyncStatus
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.CalmModeSetting
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
) {
    val colors = AppColors.current
    val familyName by familySettingsViewModel.familyName.collectAsState()
    val familyBookState by familyBookViewModel.state.collectAsState()
    LaunchedEffect(Unit) { familyBookViewModel.restore() }
    // Книга в семье — значит, её имя живёт в карточке общей книги, и второму
    // полю для того же имени тут делать нечего.
    val inFamily = familyBookState.account?.hasFamily == true

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            BackLabel("Первая полоса", onClick = onBack, modifier = Modifier.padding(horizontal = 22.dp))

            Text(
                "Настройки",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Text(
                "выходные данные книги",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            HeavyRule(Modifier.padding(horizontal = 22.dp))

            // ————— 1. Я —————
            SettingsSection(Sections.ME, first = true) {
                SettingsField(
                    label = "Ваше имя",
                    caption = "появится на титульной странице и на Полке",
                ) {
                    NameField(value = myName, onChange = onMyNameChange, placeholder = "впишите, как вас называть")
                }
                // Экслибрис — DESIGN-V4.md Cycle 3, фича Bookplate. Показывается,
                // только пока семьи нет: у книги в семье имя одно, и оно приходит
                // с сервера, а не набирается здесь второй раз.
                if (!inFamily) {
                    HairRule(Modifier.padding(horizontal = 22.dp))
                    Bookplate(
                        label = "Как подписана книга",
                        familyName = familyName,
                        onSetName = familySettingsViewModel::setFamilyName,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    )
                }
            }

            // ————— 2. Закваска —————
            SettingsSection(Sections.STARTER) {
                // Cycle 14: имя закваски — настоящая настройка, а не строка в коде.
                // Пишется в sourdough_configs, то есть в тот же конфиг, от которого
                // считается фаза и планируется напоминание: дневник, колофон и
                // шторка не могут разойтись в написании одного имени.
                SettingsField(
                    label = "Имя закваски",
                    caption = "так её зовут в дневнике, на фотокарточках кормлений и в напоминаниях",
                ) {
                    StarterNameField(persisted = starterName, onChange = onStarterNameChange)
                }
                HairRule(Modifier.padding(horizontal = 22.dp))
                FeedingRhythmRow(intervalHours = intervalHours, onChange = onIntervalHoursChange)
                HairRule(Modifier.padding(horizontal = 22.dp))
                RemindersRow(
                    remindersEnabled = remindersEnabled,
                    onRemindersEnabledChange = onRemindersEnabledChange,
                )
            }

            // ————— 3. Полка —————
            SettingsSection(Sections.FAMILY) {
                val account = familyBookState.account
                val shelfLabel = account?.takeIf { it.hasFamily }?.familyName
                    ?.ifBlank { null }
                    ?: if (account != null) "завести" else "подключить"
                SettingsRow(
                    label = shelfLabel,
                    value = "›",
                    onClick = onOpenShelfSettings,
                )
            }

            // ————— 4. Вид —————
            SettingsSection(Sections.LOOK) {
                // «Спокойный режим» (Cycle 11): текст честный — в спокойном
                // оформлении отключаются именно непрерывные и интерактивные
                // декорации, а не «часть красоты вообще».
                CalmModeRow(calmMode = calmMode, onCalmModeChange = onCalmModeChange)
                SettingsCaption(
                    "в спокойном книга не крутит непрерывных декораций — ни пыли, ни крошек, " +
                        "ни дыхания страницы; бумага, рамки и следы на страницах остаются на месте."
                )
            }

            // ————— 5. О книге —————
            SettingsSection(Sections.ABOUT) {
                SettingsRow(
                    label = "В журнале",
                    value = "$bakeCount выпечек · $feedingCount кормлений",
                    onClick = null,
                )
                HairRule(Modifier.padding(horizontal = 22.dp))
                SettingsRow(
                    label = "Версия",
                    value = com.polinalinen.madre.BuildConfig.VERSION_NAME,
                    onClick = null,
                )
            }
        }
    }
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
    const val LOOK = "Вид"
    const val ABOUT = "О книге"

    val ALL = listOf(ME, STARTER, FAMILY, LOOK, ABOUT)
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
    val colors = AppColors.current
    if (!first) {
        Spacer(Modifier.height(26.dp))
        HeavyRule(Modifier.padding(horizontal = 22.dp))
    }
    PageLabel(
        title,
        color = colors.espresso,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 4.dp),
    )
    content()
}

/**
 * Cycle 19: «как часто кормить» вместо «Кормить: раз в 48 часов».
 *
 * До этого строка перебирала пять значений по кругу от нажатия к нажатию:
 * чтобы вернуть предыдущее, нужно было пройти всю пятёрку, а увидеть весь
 * список было негде вовсе. Теперь список открывается и показывает, из чего
 * выбор, — включая то, что выбрано сейчас.
 */
@Composable
private fun FeedingRhythmRow(intervalHours: Int, onChange: (Int) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        label = "Как часто кормить",
        value = "Ваш ритм: ${FeedingInterval.label(intervalHours)}",
        onClick = { picking = true },
    )
    Text(
        "Руководство Levito Madre: при хранении на средней полке холодильника 4–6°C — каждые 3–5 дней. Это ориентир, не замена вашему ритму.",
        color = AppColors.current.cocoa,
        fontFamily = FontFamily.Serif,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    )
    if (picking) {
        SettingsChoiceDialog(
            title = "Как часто кормить",
            options = FeedingInterval.HOURS.map { it to FeedingInterval.label(it) },
            selected = intervalHours,
            onPick = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
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
    val colors = AppColors.current
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
        SettingsRow(
            label = "Напоминания",
            value = if (remindersEnabled) "вкл" else "выкл",
            valueColor = if (remindersEnabled) colors.sage else colors.cocoa,
            onClick = { onRemindersEnabledChange(!remindersEnabled) },
        )
    } else {
        // Переключать нечего: телефон запретил книге писать, и «вкл» здесь было
        // бы обещанием, которого книга не сдержит.
        SettingsRow(
            label = "Напоминания",
            value = "не разрешены телефоном",
            valueColor = colors.terracotta,
            onClick = null,
        )
        SettingsCaption(
            "книга не разбудит вас, когда закончится расстойка, не напомнит про закваску " +
                "и не покажет ход выпечки в шторке. Таймер на экране при этом работает как прежде."
        )
        BookButton(
            label = "Разрешить уведомления",
            variant = BookButtonVariant.SECONDARY,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
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

/**
 * Cycle 19: оба варианта оформления названы и стоят рядом.
 *
 * Строка-переключатель показывала одно слово и меняла его от нажатия: чтобы
 * узнать, что бывает кроме «спокойного», нужно было нажать и посмотреть, что
 * стало. Здесь оба слова видны сразу, выбранное подчёркнуто.
 */
@Composable
private fun CalmModeRow(calmMode: Boolean, onCalmModeChange: (Boolean) -> Unit) {
    val colors = AppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Оформление", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(true, false).forEach { calm ->
                val label = CalmModeSetting.label(calm)
                val chosen = calm == calmMode
                Box(
                    Modifier
                        .then(bookAction(label = "Оформление: $label") { onCalmModeChange(calm) })
                        .padding(start = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (chosen) colors.espresso else colors.flour,
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.drawBehind {
                            if (!chosen) return@drawBehind
                            val thickness = 1.5.dp.toPx()
                            drawLine(
                                color = colors.crust,
                                start = androidx.compose.ui.geometry.Offset(0f, size.height + 2.dp.toPx()),
                                end = androidx.compose.ui.geometry.Offset(size.width, size.height + 2.dp.toPx()),
                                strokeWidth = thickness,
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Выбор из нескольких значений — страницей книги, а не material-списком.
 * Форма та же, что у [com.polinalinen.madre.ui.components.ConfirmDialog]:
 * скругление 4dp, бумага Cream. Каждая строка — `bookAction`, то есть роль
 * кнопки, подпись для TalkBack и мишень не меньше 48dp.
 */
@Composable
private fun <T> SettingsChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        containerColor = colors.cream,
        title = {
            Text(title, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 20.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                options.forEach { (value, label) ->
                    val chosen = value == selected
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(bookAction(label = label) { onPick(value) }),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            label,
                            color = if (chosen) colors.crust else colors.espresso,
                            fontFamily = FontFamily.Serif,
                            fontSize = 16.sp,
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextAction("Оставить как есть", onClick = onDismiss) },
    )
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
    val colors = AppColors.current
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
        color = colors.cocoa,
        fontFamily = FontFamily.Serif,
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
    onRotateInvite: () -> Unit,
    onSignOut: () -> Unit,
    onCodeHandled: () -> Unit,
    passwordResetNotice: PasswordResetNotice = PasswordResetNotice.Idle,
    onRequestPasswordReset: (String) -> Unit = {},
) {
    val colors = AppColors.current
    val context = LocalContext.current
    // rememberSaveable, а не remember: лист «Отправить» и смена кода уводят
    // Activity в фон и переживают её пересоздание — набранные почта, пароль,
    // подпись, название и код не должны обнуляться под руками.
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

    // Одноразовый код не должен пережить уход со страницы: как только секция
    // покидает композицию, просим ViewModel забыть открытый код из состояния.
    DisposableEffect(Unit) {
        onDispose { onCodeHandled() }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text(
            "ваша книга остаётся на этом телефоне и открывается без аккаунта и сети. " +
                "Полка — способ делиться выпечками с семьёй, а не условие работы.",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (failed != null) {
            Text(
                failed.failure.message,
                color = colors.terracotta,
                fontFamily = FontFamily.Serif,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        when {
            loading -> Text("проверяем полку", color = colors.cocoa, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
            account == null && !showForm -> {
                BookButton(
                    label = "Подключить полку…",
                    variant = BookButtonVariant.SECONDARY,
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
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    is PasswordResetNotice.Failed -> Text(
                        notice.message,
                        color = colors.terracotta,
                        fontFamily = FontFamily.Serif,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    else -> Unit
                }
                FamilyBookField("Как подписать вас", displayName) { displayName = it }
                Spacer(Modifier.height(8.dp))
                BookButton(
                    label = "Войти",
                    enabled = email.isNotBlank() && password.isNotBlank(),
                    onClick = { onSignIn(email, password) },
                )
                Spacer(Modifier.height(8.dp))
                BookButton(
                    label = "Зарегистрироваться",
                    variant = BookButtonVariant.SECONDARY,
                    enabled = email.isNotBlank() && password.isNotBlank() && displayName.isNotBlank(),
                    onClick = { onRegister(email, password, displayName) },
                )
                TextAction("Не сейчас", onClick = { showForm = false })
            }
            !account.hasFamily -> {
                Text("вы вошли как ${account.displayName.ifBlank { account.email }}", color = colors.espresso, fontFamily = FontFamily.Serif)
                FamilyBookField("Название полки", familyName) { familyName = it }
                BookButton(
                    label = "Завести полку",
                    enabled = familyName.isNotBlank(),
                    onClick = { onCreateFamily(familyName) },
                )
                Spacer(Modifier.height(12.dp))
                FamilyBookField("Код приглашения", inviteCode) { inviteCode = it }
                BookButton(
                    label = "Вступить по коду",
                    variant = BookButtonVariant.SECONDARY,
                    enabled = inviteCode.isNotBlank(),
                    onClick = { onJoinFamily(inviteCode) },
                )
                TextAction("Выйти · книга на телефоне останется", onClick = onSignOut)
            }
            else -> {
                Text(
                    "полка: ${account.familyName ?: "семья"}",
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                val code = account.inviteCode
                if (code != null) {
                    val printedCode = InviteCode.format(code)
                    Text(
                        printedCode,
                        color = colors.crust,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        TextAction("Скопировать", onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Код полки", printedCode))
                            onCodeHandled()
                        }, modifier = Modifier.weight(1f))
                        TextAction("Отправить", onClick = {
                            val share = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "Вступите на полку «${account.familyName ?: "Мадре"}»: $printedCode")
                            context.startActivity(Intent.createChooser(share, "Отправить приглашение"))
                            onCodeHandled()
                        }, modifier = Modifier.weight(1f))
                    }
                }
                if (account.isFamilyOwner) {
                    Spacer(Modifier.height(8.dp))
                    BookButton(
                        label = "Обновить код приглашения",
                        variant = BookButtonVariant.SECONDARY,
                        onClick = onRotateInvite,
                    )
                }
                TextAction("Выйти · книга на телефоне останется", onClick = onSignOut)
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (masked) {
            KeyboardOptions(keyboardType = KeyboardType.Password)
        } else {
            KeyboardOptions.Default
        },
        textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Пояснение под строкой настроек — тем же голосом, что подписи в колофоне. */
@Composable
private fun SettingsCaption(text: String) {
    val colors = AppColors.current
    Text(
        text,
        color = colors.cocoa,
        fontFamily = FontFamily.Serif,
        fontStyle = FontStyle.Italic,
        fontSize = 11.5.sp,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
    )
}

@Composable
private fun SettingsField(label: String, caption: String, field: @Composable () -> Unit) {
    val colors = AppColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
        PageLabel(label, color = colors.espresso)
        Box(Modifier.padding(top = 8.dp)) { field() }
        Text(
            caption,
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
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
    val colors = AppColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = colors.flour,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            .padding(bottom = 8.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                color = colors.flour,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 19.sp,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontSize = 19.sp,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.crust),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: (() -> Unit)?, valueColor: androidx.compose.ui.graphics.Color? = null) {
    val colors = AppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            // Cycle 19, hard rule №9: строка настройки — это bookAction, а не
            // голый clickable. Роль кнопки, подпись для TalkBack и мишень не
            // меньше 48dp приходят вместе, одним модификатором, вместе с
            // защитой от двойного тапа.
            .defaultMinSize(minHeight = MinTouchTarget)
            .then(
                if (onClick != null) {
                    bookAction(label = "$label: $value", onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 15.sp)
        Text(
            value,
            color = valueColor ?: colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
        )
    }
}
