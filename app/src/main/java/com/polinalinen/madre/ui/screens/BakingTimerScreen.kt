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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.BakingViewModel

/**
 * Таймер — «Ночная страница» (DESIGN-V4.md, экран 3).
 * БЕЗ круга (decision v4 #9/#21): цифры 88sp по центру полосы.
 * Формат hh:mm:ss при часах (pitfall из CLAUDE.md).
 * Подпись-настроение — ДОПОЛНЕНИЕ к тексту шага из PDF, не замена.
 */
@Composable
fun BakingTimerScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: BakingViewModel = viewModel(),
) {
    val colors = AppColors.current
    val sessions by viewModel.sessions.collectAsState()
    val remainingMap by viewModel.remainingSeconds.collectAsState()
    val s = sessions.find { it.id == sessionId } ?: return
    val remaining = remainingMap[sessionId] ?: 0L
    val otherActive = sessions.size - 1

    val step = s.currentStep
    val isWait = step.type == StepType.WAIT
    val urgent = remaining in 1..(step.durationMinutes * 60L / 10).coerceAtLeast(1)

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PageLabel("← ${s.recipe.name}", Modifier.clickable { onBack() })
                PageLabel("Шаг ${s.currentStepIndex + 1} из ${s.recipe.timeline.size}")
            }
            Text(
                "готовится ×${s.scaleFactor.toInt().coerceAtLeast(1)} ${portionWord(s.scaleFactor.toInt())}" +
                    if (otherActive > 0) " · ещё в печи: $otherActive" else "",
                color = colors.crust,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )

            Text(
                step.title,
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 20.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )

            Text(
                formatTimer(remaining),
                color = if (urgent) colors.terracotta else colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 88.sp,
                letterSpacing = (-3).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                maxLines = 1,
            )
            Text(
                moodLine(isWait, s.isPaused, urgent),
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // Текст шага — строго из PDF, без перефразирования
            Text(
                step.description,
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            )

            // Масло-алерт (decision #22: за 30 мин до шага с requiresButterPrep)
            val butterStepAhead = s.recipe.timeline
                .drop(s.currentStepIndex + 1)
                .firstOrNull()?.requiresButterPrep == true && isWait && remaining in 1..1800
            if (butterStepAhead) {
                Row(
                    Modifier
                        .padding(horizontal = 22.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .drawBehind {
                            drawRoundRect(
                                colors.terracotta,
                                style = Stroke(1.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                            )
                        }
                        .padding(12.dp),
                ) {
                    Text(
                        "скоро понадобится мягкое масло — достаньте его согреться",
                        color = colors.espresso,
                        fontFamily = FontFamily.Serif,
                        fontSize = 13.sp,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { viewModel.togglePause(s.id) }
                        .drawBehind {
                            drawRoundRect(
                                colors.espresso,
                                style = Stroke(1.5.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                            )
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (s.isPaused) "Продолжить" else "Пауза",
                        color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 14.sp,
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clickable {
                            viewModel.advanceStep(s.id)
                            if (s.isLastStep) onComplete()
                        }
                        .drawBehind {
                            drawRoundRect(colors.espresso, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (s.isLastStep) "Готово" else "Дальше →",
                        color = colors.paper, fontFamily = FontFamily.Serif, fontSize = 14.sp,
                    )
                }
            }

            // Бросить выпечку до готовности — остаётся кляксой в дневнике (InkBlot,
            // DESIGN-V4.md Cycle 3). Текстовая ссылка, не кнопка — редкое действие.
            Text(
                "оставить эту страницу — отменить выпечку",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 4.dp)
                    .clickable {
                        viewModel.cancelSession(s.id)
                        onBack()
                    },
            )
        }
    }
}

/** hh:mm:ss при наличии часов, иначе m:ss — pitfall CLAUDE.md соблюдён. */
private fun formatTimer(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val sec = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun moodLine(isWait: Boolean, isPaused: Boolean, urgent: Boolean) = when {
    isPaused -> "страница заложена. вернёмся, когда скажешь"
    urgent -> "почти! не уходи далеко"
    isWait -> "тесто спит. не будите его."
    else -> "твой ход, пекарь"
}

private fun portionWord(n: Int) = when (n) {
    1 -> "семья"
    in 2..4 -> "семьи"
    else -> "семей"
}
