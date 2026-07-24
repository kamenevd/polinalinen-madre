package com.polinalinen.madre.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.polinalinen.madre.ui.theme.AppColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * DESIGN-V4.md Cycle 3, фича «Кляксы» (InkBlot). Небольшие несовершенства на
 * страницах дневника и формуляра — отменённая выпечка или голодная закваска
 * оставляют детерминированный след, а не случайный шум при каждой перерисовке.
 * Форма и позиция — чистые функции от seed, вынесены из Composable ради
 * юнит-теста без Robolectric/Compose runtime (тот же приём, что SpineGrowth).
 */
object InkBlot {
    private const val LOBES_MIN = 5
    private const val LOBES_RANGE = 3 // 5..7 лепестков — узнаваемая клякса, не круг и не звезда
    private const val ALPHA_MIN = 0.15f
    private const val ALPHA_RANGE = 0.15f // 0.15..0.30 — по спецификации

    /** seed = id события × 31 + offset — формула из ТЗ Cycle 3. */
    fun seedFor(eventId: Long, offset: Int): Long = eventId * 31 + offset

    data class BlotSpec(
        val cxFraction: Float,
        val cyFraction: Float,
        val radiusLobes: List<Float>,
        val rotationDeg: Float,
        val alpha: Float,
    )

    /** Форма/положение — только от seed, ничего внешнего (детерминированность). */
    fun specFor(seed: Long): BlotSpec {
        val rng = Random(seed)
        val lobeCount = LOBES_MIN + rng.nextInt(LOBES_RANGE)
        val radii = List(lobeCount) { 0.65f + rng.nextFloat() * 0.55f }
        return BlotSpec(
            cxFraction = 0.25f + rng.nextFloat() * 0.5f,
            cyFraction = 0.25f + rng.nextFloat() * 0.5f,
            radiusLobes = radii,
            rotationDeg = rng.nextFloat() * 360f,
            alpha = ALPHA_MIN + rng.nextFloat() * ALPHA_RANGE,
        )
    }
}

/**
 * Рисует одну кляксу. blurred=true — размытое пятно (HUNGRY-фаза): несколько
 * полупрозрачных слоёв нарастающего радиуса вместо RenderEffect-блюра
 * (minSdk 26 — Modifier.blur недоступен на всех целевых устройствах).
 */
@Composable
fun InkBlotSpot(
    seed: Long,
    modifier: Modifier = Modifier,
    blurred: Boolean = false,
    color: Color = AppColors.current.espresso,
) {
    val spec = InkBlot.specFor(seed)
    Canvas(modifier) {
        val cx = size.width * spec.cxFraction
        val cy = size.height * spec.cyFraction
        val base = size.minDimension * 0.16f
        val layers = if (blurred) listOf(2.2f to 0.35f, 1.5f to 0.6f, 1f to 1f) else listOf(1f to 1f)
        layers.forEach { (scale, alphaScale) ->
            drawPath(blotPath(cx, cy, base * scale, spec), color.copy(alpha = spec.alpha * alphaScale))
        }
    }
}

/** Замкнутая кривая через лепестки — сглаживается квадратичными кривыми, не ломаная. */
private fun blotPath(cx: Float, cy: Float, base: Float, spec: InkBlot.BlotSpec): Path {
    val n = spec.radiusLobes.size
    val rot = Math.toRadians(spec.rotationDeg.toDouble())
    val points = spec.radiusLobes.mapIndexed { i, rFrac ->
        val angle = rot + 2 * Math.PI * i / n
        val r = base * rFrac
        Offset(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat())
    }
    return Path().apply {
        val start = Offset((points.first().x + points.last().x) / 2, (points.first().y + points.last().y) / 2)
        moveTo(start.x, start.y)
        for (i in points.indices) {
            val curr = points[i]
            val next = points[(i + 1) % points.size]
            val mid = Offset((curr.x + next.x) / 2, (curr.y + next.y) / 2)
            quadraticBezierTo(curr.x, curr.y, mid.x, mid.y)
        }
        close()
    }
}
