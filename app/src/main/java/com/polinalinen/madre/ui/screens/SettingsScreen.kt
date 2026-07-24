package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.components.BookSpine
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors

/**
 * Настройки — «Выходные данные» (колофон книги, DESIGN-V4.md экран 8).
 *
 * «Ваше имя» — единственное поле здесь, что реально сохраняется (SharedPreferences,
 * см. MadreNavHost — тот же механизм, что уже держит избранное). Оно подставляется
 * в автограф на титульной и в подпись корешка на Полке.
 *
 * Интервал кормления и напоминания пока живут только в UI-стейте этого экрана —
 * настоящая Room-интеграция через SourdoughRepository запланирована на Cycle 3
 * (см. комментарий в MadreNavHost.kt); здесь не притворяемся, что это уже сохраняется.
 */
@Composable
fun SettingsScreen(
    myName: String,
    onMyNameChange: (String) -> Unit,
    onBack: () -> Unit,
    bakeCount: Int = 0,
    feedingCount: Int = 0,
) {
    val colors = AppColors.current
    var intervalIdx by remember { mutableIntStateOf(1) }
    var remindersOn by remember { mutableStateOf(true) }
    val intervals = listOf("раз в 12 часов", "раз в 24 часа", "раз в 48 часов", "раз в 72 часа", "раз в неделю")

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            PageLabel(
                "← Первая полоса",
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp).clickable { onBack() },
            )

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
                onClick = { intervalIdx = (intervalIdx + 1) % intervals.size },
            )
            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(
                label = "Напоминания",
                value = if (remindersOn) "вкл" else "выкл",
                valueColor = if (remindersOn) colors.sage else colors.cocoa,
                onClick = { remindersOn = !remindersOn },
            )
            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(label = "Тираж", value = "одна семья", onClick = null)
            HairRule(Modifier.padding(horizontal = 22.dp))
            SettingsRow(label = "Версия", value = com.polinalinen.madre.BuildConfig.VERSION_NAME, onClick = null)

            HairRule(Modifier.padding(horizontal = 22.dp))
            BookSpineSection(bakeCount = bakeCount, feedingCount = feedingCount)

            Spacer(Modifier.height(24.dp))
            HeavyRule(Modifier.padding(horizontal = 22.dp))
            Text(
                "кормления и таймер закваски подключатся к этим настройкам следующим шагом",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
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
            .let { if (onClick != null) it.clickable { onClick() } else it }
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
