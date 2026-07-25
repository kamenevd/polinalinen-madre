package com.polinalinen.madre.ui.components

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.AmberDeep
import kotlin.random.Random

/**
 * DESIGN-V4.md Cycle 9, фича «След от кружки» (CoffeeRing). Прерванная
 * выпечка — это чашка кофе, поставленная на раскрытую книгу: на развороте
 * рецепта остаётся бледный кофейный круг. Эмоциональная пара к крошкам
 * (Crumbs): те — про доведённое до конца, этот след — про паузу.
 *
 * Счёт прерываний — SharedPreferences (madre_prefs), ключ
 * [prefsKey]: пишет BakingViewModel.cancelSession, читает RecipeDetailScreen.
 * Показываем не больше [MAX_RINGS] — старые пятна на бумаге сливаются.
 * Геометрия — чистые функции (юнит-тест CoffeeRingTest), детерминированный
 * seed — тот же приём, что Crumbs/InkBlot: след не мигает при рекомпозиции.
 */
object CoffeeRing {
    const val MAX_RINGS = 3

    fun prefsKey(recipeId: String): String = "coffee_rings_$recipeId"

    /** Сколько кругов рисовать: по одному на прерывание, с потолком. */
    fun ringCount(cancelledCount: Int): Int = cancelledCount.coerceIn(0, MAX_RINGS)

    /**
     * Один след: [cx]/[cy] — доли ширины/высоты страницы, [radius] — доля
     * меньшей стороны, [squash] — сжатие в эллипс (кружку видно под углом),
     * [gapStartDeg]/[gapSweepDeg] — разрыв кольца (край, где чашку подняли),
     * [alpha] — бледность высохшего кофе.
     */
    data class RingSpec(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val squash: Float,
        val rotationDeg: Float,
        val gapStartDeg: Float,
        val gapSweepDeg: Float,
        val alpha: Float,
    )

    /**
     * Детерминированная геометрия следа. Кружку ставят у правого поля —
     * центр смещён вправо, но след целиком остаётся на странице.
     */
    fun specFor(seed: Long): RingSpec {
        val rng = Random(seed)
        return RingSpec(
            cx = 0.55f + rng.nextFloat() * 0.28f,
            cy = 0.12f + rng.nextFloat() * 0.72f,
            radius = 0.09f + rng.nextFloat() * 0.05f,
            squash = 0.82f + rng.nextFloat() * 0.12f,
            rotationDeg = rng.nextFloat() * 30f - 15f,
            gapStartDeg = rng.nextFloat() * 360f,
            gapSweepDeg = 40f + rng.nextFloat() * 70f,
            alpha = 0.10f + rng.nextFloat() * 0.08f,
        )
    }
}

/**
 * Вешается на viewport-контейнер разворота между крошками и износом: кофе
 * пролит поверх печати и потёртостей, но крошки и пыль легли позже. След
 * ничего не перехватывает — это просто высохшее пятно.
 */
fun Modifier.coffeeRings(cancelledCount: Int, seed: Long): Modifier = composed {
    val count = CoffeeRing.ringCount(cancelledCount)
    if (count == 0) return@composed Modifier

    val specs = remember(seed, count) {
        List(count) { CoffeeRing.specFor(seed * 31 + it) }
    }
    this.drawWithContent {
        drawContent()
        specs.forEach { drawCoffeeRing(it) }
    }
}

private fun DrawScope.drawCoffeeRing(spec: CoffeeRing.RingSpec) {
    val cx = spec.cx * size.width
    val cy = spec.cy * size.height
    val r = spec.radius * size.minDimension
    val visibleSweep = 360f - spec.gapSweepDeg
    rotate(spec.rotationDeg, pivot = Offset(cx, cy)) {
        scale(scaleX = 1f, scaleY = spec.squash, pivot = Offset(cx, cy)) {
            // Донце пятна: еле заметная заливка внутри кольца.
            drawCircle(
                color = AmberDeep.copy(alpha = spec.alpha * 0.25f),
                radius = r * 0.94f,
                center = Offset(cx, cy),
            )
            // Основной обод — с разрывом там, где чашку подняли.
            drawArc(
                color = AmberDeep.copy(alpha = spec.alpha),
                startAngle = spec.gapStartDeg + spec.gapSweepDeg,
                sweepAngle = visibleSweep,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 2.5.dp.toPx()),
            )
            // Второй, тонкий обод чуть внутри: кофе подтекает двойным краем.
            drawArc(
                color = AmberDeep.copy(alpha = spec.alpha * 0.6f),
                startAngle = spec.gapStartDeg + spec.gapSweepDeg + 25f,
                sweepAngle = (visibleSweep - 50f).coerceAtLeast(60f),
                useCenter = false,
                topLeft = Offset(cx - r + 3.dp.toPx(), cy - r + 3.dp.toPx()),
                size = Size((r - 3.dp.toPx()) * 2, (r - 3.dp.toPx()) * 2),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
