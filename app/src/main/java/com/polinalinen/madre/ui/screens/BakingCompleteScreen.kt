package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.R
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.WaxSealStamp
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.BakingViewModel

/**
 * Готово — «Сургучная печать» (DESIGN-V4.md, экран 6). Мокап ещё не согласован —
 * визуальная композиция черновая, Дима/Полина могут её пересмотреть; данные
 * (рецепт, порции, время) настоящие, читаются из BakingSession, которую
 * BakingViewModel ещё не убрал (exitSession зовётся только из onHome).
 *
 * Фотокарточка — декоративный тоггл без реального файла, один в один как
 * в FeedingFormScreen (там это тоже пока не настоящее вложение).
 */
@Composable
fun BakingCompleteScreen(
    sessionId: Long?,
    onHome: () -> Unit,
    viewModel: BakingViewModel = viewModel(),
) {
    val colors = AppColors.current
    val sessions by viewModel.sessions.collectAsState()
    val session = sessionId?.let { id -> sessions.find { it.id == id } }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            WaxSeal(dateLabel = romanDate(System.currentTimeMillis()))
            Spacer(Modifier.height(22.dp))

            Text(
                session?.recipe?.name ?: "испечено",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
            if (session != null) {
                val portions = session.scaleFactor.toInt().coerceAtLeast(1)
                Text(
                    "×$portions ${familyWord(portions)}",
                    color = colors.crust,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            HairRule(Modifier.padding(vertical = 18.dp))

            if (session != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CompletedStat("${session.totalDurationMinutes / 60}ч", "заняло")
                    CompletedStat("${session.recipe.timeline.size}", "шагов")
                }
                Spacer(Modifier.height(22.dp))
            }

            PastedPhotoPrompt()

            Text(
                "формуляр книги пополнен — испечено с любовью",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { onHome() }
                    .drawBehind {
                        drawRoundRect(colors.espresso, cornerRadius = CornerRadius(4.dp.toPx()))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("На главную", color = colors.paper, fontFamily = FontFamily.Serif, fontSize = 16.sp, letterSpacing = 1.sp)
            }
        }
    }
}

/**
 * Круглая сургучная печать — центральный мотив экрана. Общая механика штампа
 * (DESIGN-V4.md §«Графический язык»/Штампы) вынесена в WaxSealStamp
 * (ui/components/BookComponents.kt, Cycle 2) — переиспользуется в TimeCapsule.
 */
@Composable
private fun WaxSeal(dateLabel: String, modifier: Modifier = Modifier) {
    WaxSealStamp(title = "ИСПЕЧЕНО", caption = dateLabel, color = AppColors.current.sage, modifier = modifier)
}

@Composable
private fun CompletedStat(value: String, label: String) {
    val colors = AppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.espresso, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            label.uppercase(),
            color = colors.cocoa,
            fontFamily = FontFamily.SansSerif,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

@Composable
private fun PastedPhotoPrompt() {
    val colors = AppColors.current
    var photoAttached by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .rotate(-1f)
            .drawBehind { drawRect(colors.cream) }
            .padding(10.dp)
            .clickable { photoAttached = !photoAttached }
            .drawBehind {
                drawRoundRect(
                    color = colors.flour,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
            }
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = "Вклеить фотокарточку",
            modifier = Modifier.size(28.dp),
        )
        Text(
            if (photoAttached) "фотокарточка вклеена ✦" else "вклеить фотокарточку",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun familyWord(n: Int) = when (n) {
    1 -> "семья"
    in 2..4 -> "семьи"
    else -> "семей"
}

/** «20.VII» — день + месяц римскими, как в формуляре (см. StarterDiaryScreen.romanDate). */
private fun romanDate(millis: Long): String {
    val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(java.util.Calendar.DAY_OF_MONTH)}.${roman[cal.get(java.util.Calendar.MONTH)]}"
}
