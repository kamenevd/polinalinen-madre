package com.polinalinen.madre.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.Cocoa
import com.polinalinen.madre.ui.theme.Flour
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * DESIGN-V4.md Cycle 8, фича «Пыль на страницах» (DustLayer). Главы, которые
 * давно не открывали, покрываются лёгкой пеленой и пылинками. Свайп смахивает:
 * в отличие от крошек (Crumbs), пыль не ссыпается вниз, а разлетается облачком
 * в сторону жеста, чуть приподнимаясь, и тает рано — как настоящая пыль в луче
 * света. Дата последнего открытия — SharedPreferences (madre_prefs), ключ
 * last_opened_<recipeId>, пишется в RecipeDetailScreen.
 *
 * Числа и раскладка — чистые функции (юнит-тест DustLayerTest), рисование —
 * Modifier.dustLayer ниже. Детерминированный seed — тот же приём, что Crumbs.
 */
object DustLayer {
    /** Первую неделю страница считается свежей — пыль не успевает осесть. */
    const val DUST_AFTER_DAYS = 7
    const val MAX_MOTES = 90

    /** Полные календарные дни между открытиями; 0 — если книга ещё не открывалась. */
    fun daysSince(lastOpenedMillis: Long, nowMillis: Long): Int =
        if (lastOpenedMillis <= 0L || nowMillis <= lastOpenedMillis) 0
        else ((nowMillis - lastOpenedMillis) / 86_400_000L).toInt()

    /**
     * Пелена по всей странице: появляется после недели, густеет медленно и
     * с жёстким потолком — даже годами не открытая глава остаётся читаемой.
     */
    fun veilAlpha(days: Int): Float =
        if (days < DUST_AFTER_DAYS) 0f
        else (0.03f + 0.0015f * (days - DUST_AFTER_DAYS)).coerceAtMost(0.10f)

    /** Пылинки: от еле заметной россыпи к плотному слою с потолком. */
    fun moteCount(days: Int): Int =
        if (days < DUST_AFTER_DAYS) 0
        else (18 + (days - DUST_AFTER_DAYS) * 2).coerceAtMost(MAX_MOTES)

    /**
     * Одна пылинка. [x]/[y] — доли ширины/высоты страницы, [drift] — насколько
     * далеко её уносит при смахивании, [tone] — доля Cocoa в оттенке (пыль
     * неоднородная: от мучной до тёмной).
     */
    data class Mote(
        val x: Float,
        val y: Float,
        val sizeDp: Float,
        val alpha: Float,
        val drift: Float,
        val tone: Float,
    )

    /**
     * Детерминированная россыпь. Пыль оседает сверху, поэтому верх страницы
     * чуть гуще: y смещается к нулю квадратичным перекосом.
     */
    fun scatter(seed: Long, count: Int): List<Mote> {
        val rng = Random(seed)
        return List(count) {
            val t = rng.nextFloat()
            Mote(
                x = rng.nextFloat(),
                y = t * t * 0.55f + rng.nextFloat() * 0.45f,
                sizeDp = 0.5f + rng.nextFloat() * 1.1f,
                alpha = 0.12f + rng.nextFloat() * 0.16f,
                drift = rng.nextFloat(),
                tone = rng.nextFloat(),
            )
        }
    }

    /** Смахивание: облачко уносит вбок, «летучие» пылинки — дальше. */
    fun sweepDx(progress: Float, drift: Float, direction: Float, widthPx: Float): Float =
        direction * (0.3f + 0.6f * drift) * progress * widthPx

    /** Пыль не падает, а слегка взмывает — лёгкий подъём, у каждой свой. */
    fun sweepDy(progress: Float, drift: Float, heightPx: Float): Float =
        -progress * (0.02f + 0.06f * drift) * heightPx

    /**
     * Тает рано — (1-p)²: к середине жеста пыли почти не видно. Контраст с
     * крошками (Crumbs.sweepAlpha), которые держатся до конца полёта.
     */
    fun sweepAlpha(progress: Float): Float {
        val inv = (1f - progress).coerceIn(0f, 1f)
        return inv * inv
    }
}

/**
 * Вешается на viewport-контейнер разворота внешним слоем (до crumbs в цепочке):
 * пыль рисуется поверх крошек и износа — она осела последней. Жест при этом
 * достаётся сперва внутреннему обработчику (crumbs): на странице с крошками
 * первый свайп смахивает крошки, второй — пыль; двумя движениями, как и
 * прибираются в настоящей книге. Вертикальный скролл не затрагивается.
 */
fun Modifier.dustLayer(daysSinceOpened: Int, seed: Long): Modifier {
    if (DustLayer.moteCount(daysSinceOpened) == 0) return this
    return this then DustLayerElement(daysSinceOpened, seed)
}

/** Сколько длится полёт смахнутой пыли — она легче крошек и оседает быстрее. */
private const val DUST_SWEEP_MS = 650

/**
 * Cycle 16: собственный узел вместо composed {} — по той же причине, что у
 * крошек: жест, анимация и флаг «уже смахнули» в блок кэша не убираются.
 */
private data class DustLayerElement(
    private val daysSinceOpened: Int,
    private val seed: Long,
) : ModifierNodeElement<DustLayerNode>() {

    override fun create(): DustLayerNode = DustLayerNode(daysSinceOpened, seed)

    override fun update(node: DustLayerNode) {
        node.update(daysSinceOpened, seed)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "dustLayer"
        properties["daysSinceOpened"] = daysSinceOpened
        properties["seed"] = seed
    }
}

private class DustLayerNode(
    private var daysSinceOpened: Int,
    private var seed: Long,
) : DelegatingNode(), DrawModifierNode {

    private var motes = scatterFor(daysSinceOpened, seed)
    private var veil = DustLayer.veilAlpha(daysSinceOpened)
    private var sweep = Animatable(0f)
    private var direction = 1f
    private var swept = false

    private val pointer = delegate(SuspendingPointerInputModifierNode { awaitSweep() })

    private fun scatterFor(daysSinceOpened: Int, seed: Long) =
        DustLayer.scatter(seed * 17 + daysSinceOpened, DustLayer.moteCount(daysSinceOpened))

    /** Главу открыли снова — пыль пересыпается заново (ключи remember прежде). */
    fun update(daysSinceOpened: Int, seed: Long) {
        if (this.daysSinceOpened == daysSinceOpened && this.seed == seed) return
        this.daysSinceOpened = daysSinceOpened
        this.seed = seed
        motes = scatterFor(daysSinceOpened, seed)
        veil = DustLayer.veilAlpha(daysSinceOpened)
        sweep = Animatable(0f)
        swept = false
        pointer.resetPointerInputHandler()
        invalidateDraw()
    }

    private suspend fun PointerInputScope.awaitSweep() {
        // Смахнутая страница жест больше не ловит: подметать нечего.
        if (swept) return
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onHorizontalDrag = { _, amount -> dragged += amount },
            onDragEnd = {
                if (abs(dragged) > 48.dp.toPx() && !sweep.isRunning) {
                    direction = if (dragged > 0f) 1f else -1f
                    coroutineScope.launch {
                        sweep.animateTo(1f, tween(DUST_SWEEP_MS, easing = LinearEasing)) {
                            invalidateDraw()
                        }
                        swept = true
                        pointer.resetPointerInputHandler()
                        invalidateDraw()
                    }
                }
            },
        )
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (swept) return
        val progress = sweep.value
        val alpha = DustLayer.sweepAlpha(progress)
        drawVeil(veil * alpha)
        motes.forEach { m -> drawMote(m, progress, direction, alpha) }
    }
}

/** Пелена: сверху гуще (пыль оседает с верхнего обреза), к низу сходит на нет. */
private fun DrawScope.drawVeil(alpha: Float) {
    if (alpha <= 0f) return
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Flour.copy(alpha = alpha), Flour.copy(alpha = alpha * 0.35f)),
            startY = 0f,
            endY = size.height,
        ),
    )
}

private fun DrawScope.drawMote(m: DustLayer.Mote, progress: Float, direction: Float, alpha: Float) {
    val cx = m.x * size.width + DustLayer.sweepDx(progress, m.drift, direction, size.width)
    val cy = m.y * size.height + DustLayer.sweepDy(progress, m.drift, size.height)
    val tone = lerpColor(Flour, Cocoa, m.tone)
    drawCircle(tone.copy(alpha = m.alpha * alpha), m.sizeDp.dp.toPx(), Offset(cx, cy))
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
