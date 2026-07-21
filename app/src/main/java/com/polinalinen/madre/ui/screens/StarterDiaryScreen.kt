package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.sourdough.DiaryEntry
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.components.BubbleVignette
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.breathingPage
import com.polinalinen.madre.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Закваска — «Дневник культуры» (DESIGN-V4.md, экран 4).
 * Механики: #1 дневник от первого лица, #4 формуляр, #5 дышащая страница.
 */
@Composable
fun StarterDiaryScreen(
    dayNumber: Int,
    phase: GrowthPhase,
    entries: List<DiaryEntry>,
    history: List<FeedingEntity>,
    onBack: () -> Unit,
    onFeed: () -> Unit,
) {
    val colors = AppColors.current

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .breathingPage(phase) // страница дышит — механика #5
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PageLabel("← Первая полоса", Modifier.clickable { onBack() })
                PageLabel("Глава VII · день $dayNumber")
            }

            Text(
                "Мадре пишет:",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )

            // Дневник — рукописный шрифт, вертикальная линия полей слева
            Column(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 12.dp)
                    .drawBehind {
                        drawRect(colors.flour, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                    }
                    .padding(start = 14.dp)
            ) {
                entries.forEach { entry ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            "${entry.timeLabel} —",
                            color = if (entry.isHighlight) colors.sage else colors.cocoa,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            entry.text,
                            color = colors.espresso,
                            fontFamily = FontFamily.Cursive,
                            fontWeight = if (entry.isHighlight) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                        )
                    }
                }
            }

            BubbleVignette(phase, Modifier.padding(horizontal = 22.dp, vertical = 8.dp))

            // Формуляр выпечки — механика #4
            PageLabel("Формуляр кормлений", Modifier.padding(start = 22.dp, top = 10.dp), color = colors.espresso)
            Column(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 8.dp)
                    .drawBehind { drawRect(colors.cream) }
                    .padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    FormHeader("дата", 1.2f)
                    FormHeader("мука/вода", 2f)
                    FormHeader("отметка", 1.5f, alignEnd = true)
                }
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(0.dp)) {
                        drawRect(colors.espresso, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                    }
                }
                history.take(10).forEach { feeding ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            romanDate(feeding.timestampMillis),
                            color = colors.cocoa, fontFamily = FontFamily.Serif, fontSize = 12.sp,
                            modifier = Modifier.weight(1.2f),
                        )
                        Text(
                            "${feeding.flourGrams}г · ${feeding.waterGrams}г",
                            color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 12.sp,
                            modifier = Modifier.weight(2f),
                        )
                        Text(
                            feeding.notes ?: "—",
                            color = colors.sage, fontFamily = FontFamily.Cursive, fontSize = 13.sp,
                            modifier = Modifier.weight(1.5f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            maxLines = 1,
                        )
                    }
                    HairRule()
                }
            }

            Box(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .clickable { onFeed() }
                    .drawBehind {
                        drawRoundRect(colors.espresso, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Покормить", color = colors.paper, fontFamily = FontFamily.Serif, fontSize = 16.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FormHeader(text: String, weight: Float, alignEnd: Boolean = false) {
    Text(
        text.uppercase(),
        color = AppColors.current.cocoa,
        fontFamily = FontFamily.SansSerif,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.weight(weight),
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
}

/** «18.VII» — день + месяц римскими, как в библиотечном формуляре. */
private fun romanDate(millis: Long): String {
    val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(java.util.Calendar.DAY_OF_MONTH)}.${roman[cal.get(java.util.Calendar.MONTH)]}"
}
