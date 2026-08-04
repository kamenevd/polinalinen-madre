package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.notifications.BakingProgressFormatter
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BookButtonVariant
import com.polinalinen.madre.ui.components.ConfirmDialog
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.TextAction
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
    val s = sessions.find { it.id == sessionId }
    // Выпечки больше нет в книге — её закрыли, бросили или пережил только
    // ярлык уведомления после перезапуска. Раньше здесь просто ничего не
    // рисовалось: человек оставался на белом экране без единой кнопки.
    if (s == null) {
        ClosedBakingPage(onBack)
        return
    }
    val remaining = remainingMap[sessionId] ?: 0L
    val otherActive = sessions.size - 1
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    val step = s.currentStep
    val isWait = step.type == StepType.WAIT
    val urgent = remaining in 1..(step.durationMinutes * 60L / 10).coerceAtLeast(1)
    val etaText = BakingProgressFormatter.etaText(
        stepIndex = s.currentStepIndex,
        stepCount = s.recipe.timeline.size,
        nextStepTitle = s.recipe.timeline.getOrNull(s.currentStepIndex + 1)?.title,
        remainingSeconds = remaining,
    )

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackLabel(s.recipe.name, onClick = onBack)
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
            Text(
                etaText,
                color = colors.crust,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            // Текст шага — строго из PDF, без перефразирования. Cycle 14:
            // граммы в нём пересчитаны на выбранные порции — тем же масштабом,
            // с которым выпечку начали, и тем же, что на развороте рецепта.
            Text(
                RecipeScaler.scaledStepText(step.description, s.scaleFactor),
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
                BookButton(
                    label = if (s.isPaused) "Продолжить" else "Пауза",
                    onClick = { viewModel.togglePause(s.id) },
                    variant = BookButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
                BookButton(
                    label = if (s.isLastStep) "Готово" else "Дальше →",
                    onClick = {
                        viewModel.advanceStep(s.id)
                        if (s.isLastStep) onComplete()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // Бросить выпечку до готовности — остаётся кляксой в дневнике
            // (InkBlot, Cycle 3) и кофейным кругом на развороте (Cycle 9),
            // а сама выпечка пропадает безвозвратно.
            //
            // Cycle 12: раньше это была строка 12sp сразу под «Дальше», без
            // отступа и без спроса — палец, промахнувшийся мимо главной
            // кнопки на пару миллиметров вниз, отменял выпечку молча и
            // насовсем. Теперь отмена отодвинута линейкой и большим полем,
            // выглядит приписке на полях, а не соседней кнопкой, и всегда
            // переспрашивает.
            Spacer(Modifier.height(28.dp))
            HairRule(Modifier.padding(horizontal = 22.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextAction(
                    label = "бросить эту выпечку",
                    onClick = { confirmCancel = true },
                )
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "Бросить выпечку?",
            message = "«${s.recipe.name}» уйдёт со страницы вместе с таймером — " +
                "вернуться к этому шагу будет уже нельзя. В дневнике останется клякса.",
            confirmLabel = "Бросить",
            dismissLabel = "Остаться",
            onConfirm = {
                confirmCancel = false
                viewModel.cancelSession(s.id)
                onBack()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

/**
 * Страница закрытой выпечки: сюда попадают, когда сессии уже нет — её бросили,
 * довели до конца или открыли по старому ярлыку уведомления после перезапуска.
 */
@Composable
private fun ClosedBakingPage(onBack: () -> Unit) {
    val colors = AppColors.current
    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.statusBarsPadding().fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "эта выпечка уже закрыта",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                "страница таймера живёт, пока идёт выпечка — эта своё отходила",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            BookButton(label = "На первую полосу", onClick = onBack)
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
