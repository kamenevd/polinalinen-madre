package com.polinalinen.madre.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * DESIGN-V4.md Cycle 9, фича «Калька» (TracingPaper). В настоящих семейных
 * книгах особые страницы — с фотографиями, юбилейные — прокладывают
 * полупрозрачной калькой. Здесь она ложится поверх страницы «Готово», когда
 * выпечка юбилейная, с припиской от Мадре карандашом; отворачивается свайпом
 * в сторону — лист уезжает с лёгким поворотом, как отогнутая калька.
 *
 * Пороговые числа и математика отгибания — чистые функции (юнит-тест
 * TracingPaperTest), рисование и жест — [TracingPaperOverlay] ниже.
 */
object TracingPaper {
    /**
     * Юбилейные выпечки: первая, десятая, двадцать пятая, дальше — каждые
     * пятьдесят. Между ними калька в книге не появляется.
     */
    fun isMilestone(totalBakes: Int): Boolean =
        totalBakes == 1 || totalBakes == 10 || totalBakes == 25 ||
            (totalBakes > 0 && totalBakes % 50 == 0)

    /** Приписка Мадре карандашом на кальке; null — выпечка не юбилейная. */
    fun noteFor(totalBakes: Int): String? = when {
        totalBakes == 1 -> "первая страница нашей книги. пусть их будет много"
        totalBakes == 10 -> "десятая выпечка. у нас с вами уже свои привычки"
        totalBakes == 25 -> "двадцать пятая. я узнаю ваши руки без подсказок"
        isMilestone(totalBakes) -> "выпечка № $totalBakes — юбилейная страница"
        else -> null
    }

    // ── Математика отгибания: progress 0..1, direction ±1 ────────────────

    /** Уезжает вбок с ускорением — к концу жеста лист гарантированно за краем. */
    fun foldDx(progress: Float, direction: Float, widthPx: Float): Float =
        direction * progress * progress * widthPx * 1.2f

    /** Лёгкий поворот в сторону жеста — отгибают за угол, а не сдвигают линейкой. */
    fun foldRotationDeg(progress: Float, direction: Float): Float =
        direction * progress * 16f

    /** Калька тает лишь слегка: лист уезжает, а не растворяется. */
    fun foldAlpha(progress: Float): Float =
        (1f - progress * progress * 0.6f).coerceIn(0f, 1f)

    /**
     * Одно волокно кальки: [y] — доля высоты, [x0]/[x1] — доли ширины,
     * [alpha] — еле заметная неоднородность листа.
     */
    data class Fibre(val y: Float, val x0: Float, val x1: Float, val alpha: Float)

    /** Детерминированная фактура — волокна не мигают при рекомпозиции. */
    fun fibres(seed: Long, count: Int = 26): List<Fibre> {
        val rng = Random(seed)
        return List(count) {
            val x0 = rng.nextFloat() * 0.7f
            Fibre(
                y = rng.nextFloat(),
                x0 = x0,
                x1 = (x0 + 0.15f + rng.nextFloat() * 0.5f).coerceAtMost(1f),
                alpha = 0.04f + rng.nextFloat() * 0.08f,
            )
        }
    }
}

/**
 * Лист кальки поверх особой страницы: молочная пелена с волокнами, приписка
 * Мадре карандашом (Cursive, бледный Cocoa — не чернила) и тихая подсказка.
 * Горизонтальный свайп длиннее 48dp отворачивает лист; тапы калька забирает
 * себе — под ней ничего не нажать, пока её не отвернули, как и в бумажной
 * книге нельзя читать страницу сквозь кальку.
 */
@Composable
fun TracingPaperOverlay(note: String, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    var folded by remember { mutableStateOf(false) }
    if (folded) return

    val fold = remember { Animatable(0f) }
    var direction by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val fibres = remember { TracingPaper.fibres(seed = 0xCA17BAL) }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { _, amount -> dragged += amount },
                    onDragEnd = {
                        if (abs(dragged) > 48.dp.toPx() && !fold.isRunning) {
                            direction = if (dragged > 0f) 1f else -1f
                            scope.launch {
                                fold.animateTo(1f, tween(550, easing = FastOutLinearInEasing))
                                folded = true
                            }
                        }
                    },
                )
            }
            .graphicsLayer {
                translationX = TracingPaper.foldDx(fold.value, direction, size.width)
                rotationZ = TracingPaper.foldRotationDeg(fold.value, direction)
                alpha = TracingPaper.foldAlpha(fold.value)
            }
            .drawBehind {
                drawRect(colors.cream.copy(alpha = 0.88f))
                fibres.forEach { f ->
                    drawLine(
                        color = colors.flour.copy(alpha = f.alpha),
                        start = Offset(f.x0 * size.width, f.y * size.height),
                        end = Offset(f.x1 * size.width, f.y * size.height),
                        strokeWidth = 1.dp.toPx() * 0.6f,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            // Карандаш, не чернила: бледный Cocoa, рукопись с лёгким наклоном.
            Text(
                note,
                color = colors.cocoa.copy(alpha = 0.75f),
                fontFamily = FontFamily.Cursive,
                fontSize = 19.sp,
                lineHeight = 27.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.rotate(-2f),
            )
            Text(
                "отверните кальку — смахните в сторону",
                color = colors.cocoa.copy(alpha = 0.5f),
                fontFamily = FontFamily.SansSerif,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 26.dp),
            )
        }
    }
}
