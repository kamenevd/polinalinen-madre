package com.polinalinen.madre.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.notifications.MadreNotifier
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BookButtonVariant
import com.polinalinen.madre.ui.components.Bookplate
import com.polinalinen.madre.ui.components.MinTouchTarget
import com.polinalinen.madre.ui.components.BookSpine
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.CalmModeSetting
import com.polinalinen.madre.viewmodel.FamilySettingsViewModel

/**
 * Настройки — «Выходные данные» (колофон книги, DESIGN-V4.md экран 8).
 *
 * «Ваше имя» — SharedPreferences (см. MadreNavHost — тот же механизм, что уже
 * держит избранное). Оно подставляется в автограф на титульной и в подпись
 * корешка на Полке.
 *
 * Cycle 11: «Кормить» и «Напоминания» перестали быть UI-стейтом этого экрана и
 * пишут в sourdough_configs (intervalHours / remindersEnabled) — тот же конфиг,
 * от которого считается фаза закваски и планируется напоминание. «Оформление» —
 * спокойный режим, madre_prefs (CalmModeSetting).
 */
@Composable
fun SettingsScreen(
    myName: String,
    onMyNameChange: (String) -> Unit,
    onBack: () -> Unit,
    bakeCount: Int = 0,
    feedingCount: Int = 0,
    intervalHours: Int = 24,
    onIntervalHoursChange: (Int) -> Unit = {},
    remindersEnabled: Boolean = true,
    onRemindersEnabledChange: (Boolean) -> Unit = {},
    calmMode: Boolean = CalmModeSetting.DEFAULT,
    onCalmModeChange: (Boolean) -> Unit = {},
    familySettingsViewModel: FamilySettingsViewModel = viewModel(),
) {
    val colors = AppColors.current
    val familyName by familySettingsViewModel.familyName.collectAsState()
    // Ключи — те же, что понимает profileForInterval() (12/24/48/72/168):
    // строка в колофоне и профиль закваски не могут разойтись.
    val intervalHoursOptions = listOf(12, 24, 48, 72, 168)
    val intervals = listOf("раз в 12 часов", "раз в 24 часа", "раз в 48 часов", "раз в 72 часа", "раз в неделю")
    val intervalIdx = intervalHoursOptions.indexOf(intervalHours).takeIf { it >= 0 } ?: 1

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            BackLabel("Первая полоса", onClick = onBack, modifier = Modifier.padding(horizontal = 22.dp))

            Text(
                "Выходные данные",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Spacer(Modifier.height(14.dp))
            HeavyRule(Modifier.padding(horizontal = 22.dp))

            // Экслибрис — DESIGN-V4.md Cycle 3, фича Bookplate. Поверх остального
            // колофона, отдельным орнаментальным блоком в самом начале страницы.
            Bookplate(
                familyName = familyName,
                onSetName = familySettingsViewModel::setFamilyName,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            )
            HairRule(Modifier.padding(horizontal = 22.dp))

            SettingsField(
                label = "Ваше имя",
                caption = "появится на титульной странице и на Полке",
            ) {
                NameField(value = myName, onChange = onMyNameChange, placeholder = "впишите, как вас называть")
            }

            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(label = "Имя закваски", value = "Мадре", onClick = null)
            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(
                label = "Кормить",
                value = intervals[intervalIdx],
                onClick = {
                    val next = (intervalIdx + 1) % intervalHoursOptions.size
                    onIntervalHoursChange(intervalHoursOptions[next])
                },
            )
            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(
                label = "Напоминания",
                value = if (remindersEnabled) "вкл" else "выкл",
                valueColor = if (remindersEnabled) colors.sage else colors.cocoa,
                onClick = { onRemindersEnabledChange(!remindersEnabled) },
            )
            HairRule(Modifier.padding(horizontal = 22.dp))
            // «Спокойный режим» (Cycle 11): текст честный — в спокойном
            // оформлении отключаются именно непрерывные и интерактивные
            // декорации, а не «часть красоты вообще».
            SettingsRow(
                label = "Оформление",
                value = CalmModeSetting.label(calmMode),
                onClick = { onCalmModeChange(!calmMode) },
            )
            SettingsCaption(
                if (calmMode) {
                    "спокойное: книга не крутит непрерывных декораций — ни пыли, ни крошек, " +
                        "ни дыхания страницы. Прокрутка плавная, бумага, рамки и следы на " +
                        "страницах остаются на месте."
                } else {
                    "полное: пыль на давно не открытых главах, крошки между страниц и дыхание " +
                        "страницы включены. Красиво, но на длинных страницах прокрутка может " +
                        "подтормаживать."
                }
            )
            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(label = "Тираж", value = "одна семья", onClick = null)
            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(label = "Версия", value = com.polinalinen.madre.BuildConfig.VERSION_NAME, onClick = null)

            HairRule(Modifier.padding(horizontal = 22.dp))
            BookSpineSection(bakeCount = bakeCount, feedingCount = feedingCount)

            Spacer(Modifier.height(24.dp))
            HeavyRule(Modifier.padding(horizontal = 22.dp))
            NotificationPermissionSection()
        }
    }
}

/**
 * Cycle 12: честное состояние уведомлений.
 *
 * До этого книга спрашивала разрешение при каждом холодном старте, ответ
 * выбрасывала (`{ _ -> }`) и нигде его не показывала. Человек, однажды
 * отказавший, потом просто не понимал, почему напоминания не приходят и куда
 * делся прогресс выпечки: настройки бодро говорили «Напоминания: вкл».
 *
 * Теперь состояние проверяется при каждом возвращении на экран (человек мог
 * поменять его в системных настройках и вернуться), и отказ — не тупик:
 * системные настройки уведомлений открываются отсюда одной кнопкой.
 */
@Composable
private fun NotificationPermissionSection() {
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
        PageLabel("Уведомления книги", color = colors.espresso)
        Text(
            if (allowed) {
                "книге разрешено писать вам: напоминание покормить закваску, конец шага " +
                    "ожидания, просьба достать масло и строка хода выпечки в шторке. " +
                    "Напоминание о кормлении приходит примерно в срок — система будит " +
                    "книгу, когда ей удобно, с точностью до нескольких минут."
            } else {
                "уведомления запрещены — книга молчит. Она не разбудит вас, когда " +
                    "закончится расстойка, не напомнит про закваску и не покажет ход " +
                    "выпечки в шторке. Таймер на экране при этом работает как прежде."
            },
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!allowed) {
            Spacer(Modifier.height(12.dp))
            BookButton(
                label = "Открыть настройки уведомлений",
                variant = BookButtonVariant.SECONDARY,
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
}

/**
 * «Состояние книги» — DESIGN-V4.md Cycle 2, фича «Растущий корешок» (SpineGrowth).
 * Корешок сбоку толстеет и «трётся» вместе с историей — bakeCount + feedingCount.
 */
@Composable
private fun BookSpineSection(bakeCount: Int, feedingCount: Int) {
    val colors = AppColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
        PageLabel("Состояние книги", color = colors.espresso)
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
            BookSpine(bakeCount = bakeCount, feedingCount = feedingCount, height = 140.dp)
            Column(Modifier.padding(start = 16.dp).height(140.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
                Text(
                    "$bakeCount выпечек · $feedingCount кормлений",
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    "корешок растёт и обтрёпывается вместе с историей семьи",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
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
            // Строка-переключатель («Кормить», «Напоминания», «Оформление») —
            // такая же мишень, как кнопка: 48dp и объявленная роль.
            .defaultMinSize(minHeight = MinTouchTarget)
            .let {
                if (onClick != null) {
                    it.clickable(onClickLabel = "$label: $value", role = Role.Button) { onClick() }
                } else {
                    it
                }
            }
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
