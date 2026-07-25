package com.polinalinen.madre.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.polinalinen.madre.ui.theme.Caramel
import com.polinalinen.madre.ui.theme.Espresso
import kotlin.math.PI
import kotlin.math.sin

/**
 * DESIGN-V4.md Cycle 10, фича «Чтение при свече» (Candlelight). После заката
 * книга читается при свече: тёплое дрожащее пятно света следует за пальцем,
 * края страницы уходят в полумрак, тени колышутся. Вечерний ритуал — палец
 * ведёт по строкам, свет идёт следом.
 *
 * Часы, мерцание и геометрия света — чистые функции (юнит-тест
 * CandlelightTest), рендер — Modifier.candlelight: радиальный градиент от
 * тёплого центра к полумраку краёв, радиус дышит суммой несинхронных синусов.
 */
object Candlelight {
    /** Полумрак краёв: страница темнеет, но текст под пятном света читаем. */
    const val SCRIM_ALPHA = 0.50f

    /** Свечу зажигают после заката и задувают под утро. */
    fun isCandleTime(hour: Int): Boolean = hour >= 21 || hour < 6

    /**
     * Мерцание пламени: три несинхронных синуса на целых частотах — шумное
     * дрожание без видимого ритма, бесшовное по циклу [phase] 0..1.
     * Возвращает множитель радиуса в пределах ~[0.9, 1.1].
     */
    fun flicker(phase: Float): Float {
        val p = phase * 2f * PI.toFloat()
        return 1f + 0.045f * sin(p * 3f) + 0.03f * sin(p * 7f + 1.3f) + 0.02f * sin(p * 13f + 4.1f)
    }

    /** Радиус пятна света: больше половины короткой стороны, дышит мерцанием. */
    fun radiusPx(minDimensionPx: Float, flickerValue: Float): Float =
        minDimensionPx * 0.6f * flickerValue

    /** Тёплое ядро пламени вспыхивает и опадает вместе с мерцанием. */
    fun glowAlpha(flickerValue: Float): Float =
        (0.05f + 0.45f * (flickerValue - 0.9f)).coerceIn(0.04f, 0.14f)
}

/**
 * Вешается на viewport-контейнер разворота первым в цепочке — свет и полумрак
 * ложатся поверх всех бумажных слоёв (пыли, крошек, кофе). Палец книга только
 * слушает (PointerEventPass.Initial, без потребления): скролл, крошки и
 * склейки работают как прежде, пятно света просто идёт следом. Пока страницу
 * не трогали, свеча стоит у верхней трети — где читают.
 */
fun Modifier.candlelight(enabled: Boolean): Modifier = composed {
    if (!enabled) return@composed Modifier

    var focus by remember { mutableStateOf(Offset.Unspecified) }
    val transition = rememberInfiniteTransition(label = "candle")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "candlePhase",
    )

    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.lastOrNull()?.let { focus = it.position }
                }
            }
        }
        .drawWithContent {
            drawContent()
            val f = Candlelight.flicker(phase)
            val center = if (focus.isSpecified) focus else Offset(size.width / 2f, size.height * 0.38f)
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Caramel.copy(alpha = Candlelight.glowAlpha(f)),
                        0.45f to Color.Transparent,
                        1f to Espresso.copy(alpha = Candlelight.SCRIM_ALPHA),
                    ),
                    center = center,
                    radius = Candlelight.radiusPx(size.minDimension, f) * 2.2f,
                ),
            )
        }
}
