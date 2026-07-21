package com.polinalinen.madre.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.theme.AppColors

// ═══════════════════════════════════════════════════════════════
// «Живая книга» — типографские компоненты. См. DESIGN-V4.md.
// ═══════════════════════════════════════════════════════════════

/** Служебный ярлык: UPPERCASE, разрядка. «ОГЛАВЛЕНИЕ», «РЕЦЕПТ № 01». */
@Composable
fun PageLabel(text: String, modifier: Modifier = Modifier, color: Color = AppColors.current.cocoa) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontSize = 10.sp,
        letterSpacing = 3.sp,
    )
}

/** Жирная типографская линейка (2dp Espresso) — главные разделы. */
@Composable
fun HeavyRule(modifier: Modifier = Modifier) {
    val ink = AppColors.current.espresso
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind { drawRect(ink) }
    )
}

/** Волосяная линейка (0.5dp Flour) — строки. */
@Composable
fun HairRule(modifier: Modifier = Modifier) {
    val flour = AppColors.current.flour
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .drawBehind { drawRect(flour) }
    )
}

/**
 * Строка с отточием: «тёплой воды ...... 285 г».
 * Классика кулинарной книги. Значение — Crust, жирное.
 */
@Composable
fun DottedLeaderRow(name: String, value: String, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = name,
            color = colors.espresso,
            fontFamily = FontFamily.Serif,
            fontSize = 14.sp,
        )
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .height(1.5.dp)
                .drawBehind {
                    drawLine(
                        color = colors.flour,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
                    )
                }
        )
        Text(
            text = value,
            color = colors.crust,
            fontFamily = FontFamily.Serif,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Штамп: рамка 1.5dp, лёгкий поворот, без заливки.
 * «В ПЕЧИ» (terracotta), «ИСПЕЧЕНО» (sage).
 */
@Composable
fun Stamp(text: String, color: Color, modifier: Modifier = Modifier, rotation: Float = 3f) {
    Box(
        modifier = modifier
            .rotate(rotation)
            .drawBehind {
                drawRoundRect(
                    color = color,
                    style = Stroke(width = 1.5.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text.uppercase(),
            color = color,
            fontFamily = FontFamily.SansSerif,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        )
    }
}

/**
 * Ляссе — красная ленточка-закладка. Рисуется поверх страницы (в Box),
 * пока идёт выпечка. Тап обрабатывает родитель.
 */
@Composable
fun RibbonBookmark(modifier: Modifier = Modifier, width: Dp = 22.dp, length: Dp = 92.dp) {
    val terracotta = AppColors.current.terracotta
    Canvas(modifier = modifier.width(width).height(length)) {
        val w = size.width
        val h = size.height
        val notch = w * 0.45f
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w, h)
            lineTo(w / 2f, h - notch)
            lineTo(0f, h)
            close()
        }
        drawPath(path, terracotta)
    }
}

/**
 * Загнутый уголок страницы = избранное. favoriteProgress 0..1 (анимируется).
 * Рисуется в правом верхнем углу строки оглавления.
 */
@Composable
fun DogEar(isFavorite: Boolean, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val colors = AppColors.current
    val progress by animateFloatAsState(
        targetValue = if (isFavorite) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "dogEar",
    )
    Canvas(modifier = modifier.size(size)) {
        if (progress > 0.01f) {
            val s = this.size.width * progress
            val fold = Path().apply {
                moveTo(this@Canvas.size.width - s, 0f)
                lineTo(this@Canvas.size.width, 0f)
                lineTo(this@Canvas.size.width, s)
                close()
            }
            // Тень сгиба + сам загиб (чуть темнее бумаги)
            drawPath(fold, colors.parchment)
            drawPath(fold, colors.flour, style = Stroke(width = 1f))
        }
    }
}

/**
 * Перфорированный талон: рамка 1.5dp Espresso + полукруглые вырезы по бокам.
 * Контент кладётся внутрь через content-слот.
 */
@Composable
fun TicketFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = AppColors.current
    Box(
        modifier = modifier
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                val r = 4.dp.toPx()
                drawRoundRect(
                    color = colors.espresso,
                    style = Stroke(width = stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                )
                // Перфорация: полукруги по центру боков — «пробитый» талон
                val notchR = 5.dp.toPx()
                drawCircle(colors.paper, notchR, Offset(0f, size.height / 2))
                drawCircle(colors.espresso, notchR, Offset(0f, size.height / 2), style = Stroke(stroke))
                drawCircle(colors.paper, notchR, Offset(size.width, size.height / 2))
                drawCircle(colors.espresso, notchR, Offset(size.width, size.height / 2), style = Stroke(stroke))
            }
            .padding(16.dp)
    ) { content() }
}

/**
 * Пузырьковая виньетка-разделитель — маленький след живой закваски
 * между разделами страницы. Цвет — по фазе.
 */
@Composable
fun BubbleVignette(phase: GrowthPhase, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    val tone = when (phase) {
        GrowthPhase.PEAK -> colors.sage
        GrowthPhase.HUNGRY -> colors.terracotta
        GrowthPhase.DECLINING -> colors.cool
        GrowthPhase.EMPTY -> colors.flour
        else -> colors.caramel
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        HairRule(Modifier.weight(1f))
        Canvas(Modifier.size(40.dp).padding(horizontal = 6.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(tone, size.minDimension * 0.32f, c)
            drawCircle(tone.copy(alpha = 0.4f), size.minDimension * 0.46f, c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            drawCircle(colors.paper.copy(alpha = 0.5f), 2.5.dp.toPx(), c + Offset(-6f, -7f))
            drawCircle(colors.paper.copy(alpha = 0.4f), 1.5.dp.toPx(), c + Offset(7f, 5f))
        }
        HairRule(Modifier.weight(1f))
    }
}

/**
 * «Дышащая страница»: почти невидимое scale-дыхание всего контента.
 * Период — по фазе закваски (пик быстрее, голодная — тревожнее).
 * Применять к корню страницы дневника.
 */
@Composable
fun Modifier.breathingPage(phase: GrowthPhase): Modifier {
    val duration = when (phase) {
        GrowthPhase.PEAK -> 2000
        GrowthPhase.HUNGRY -> 1500
        else -> 4000
    }
    val transition = rememberInfiniteTransition(label = "page")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.004f,
        animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse),
        label = "pageScale",
    )
    return this.scale(scale)
}
