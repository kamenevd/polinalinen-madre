package com.polinalinen.madre.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.AmberDeep
import com.polinalinen.madre.ui.theme.Cocoa
import com.polinalinen.madre.ui.theme.Crust
import com.polinalinen.madre.ui.theme.Flour
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * DESIGN-V4.md Cycle 7, фича «Крошки между страниц» (Crumbs). После выпечек
 * на развороте рецепта остаются крошки и мучная пыль — чем чаще пекут главу,
 * тем их больше. Свайп по странице смахивает их: частицы уносит в сторону
 * жеста и они ссыпаются вниз за край экрана.
 *
 * Числа и раскладка — чистые функции (юнит-тест CrumbsTest), рисование —
 * Modifier.crumbs ниже. Детерминированный seed — тот же приём, что InkBlot
 * и WornPage: крошки не мигают при рекомпозиции.
 */
object Crumbs {
    const val MAX_CRUMBS = 42
    const val MAX_DUST = 70

    /** Крошки: нет выпечек — чистая страница; дальше растёт с мягким потолком. */
    fun crumbCount(bakeCount: Int): Int =
        if (bakeCount <= 0) 0 else (6 + bakeCount * 4).coerceAtMost(MAX_CRUMBS)

    /** Мучная пыль — мельче и гуще крошек, тот же принцип роста. */
    fun dustCount(bakeCount: Int): Int =
        if (bakeCount <= 0) 0 else (10 + bakeCount * 6).coerceAtMost(MAX_DUST)

    /**
     * Одна частица. [x]/[y] — доли ширины/высоты страницы, [drift] — насколько
     * её уносит вбок при смахивании (у каждой крошки свой характер полёта).
     */
    data class Particle(
        val x: Float,
        val y: Float,
        val sizeDp: Float,
        val rotationDeg: Float,
        val toneIndex: Int,
        val drift: Float,
        val isDust: Boolean,
    )

    /**
     * Детерминированная россыпь: крошки тяготеют к низу страницы (туда они
     * и ссыпаются в настоящей книге), пыль — равномернее.
     */
    fun scatter(seed: Long, crumbs: Int, dust: Int): List<Particle> {
        val rng = Random(seed)
        val result = ArrayList<Particle>(crumbs + dust)
        repeat(crumbs) {
            val t = rng.nextFloat()
            result += Particle(
                x = rng.nextFloat(),
                y = 1f - t * t * 0.92f,
                sizeDp = 1.8f + rng.nextFloat() * 1.8f,
                rotationDeg = rng.nextFloat() * 360f,
                toneIndex = rng.nextInt(3),
                drift = rng.nextFloat(),
                isDust = false,
            )
        }
        repeat(dust) {
            val t = rng.nextFloat()
            result += Particle(
                x = rng.nextFloat(),
                y = 1f - t * 0.95f,
                sizeDp = 0.7f + rng.nextFloat() * 0.9f,
                rotationDeg = 0f,
                toneIndex = 3,
                drift = rng.nextFloat(),
                isDust = true,
            )
        }
        return result
    }

    /** Смахивание по горизонтали: уносит в сторону жеста, дальше — у «летучих». */
    fun sweepDx(progress: Float, drift: Float, direction: Float, widthPx: Float): Float =
        direction * (0.25f + 0.5f * drift) * progress * widthPx

    /**
     * Падение: ускоряется как progress² и к концу гарантированно уводит
     * частицу за нижний край (множитель 1.15 больше оставшейся высоты).
     */
    fun sweepDy(progress: Float, y: Float, heightPx: Float): Float =
        progress * progress * (1.15f - y).coerceAtLeast(0.2f) * heightPx

    /** Частица чуть тает в полёте, но исчезает только к самому концу. */
    fun sweepAlpha(progress: Float): Float =
        (1f - progress * progress * progress).coerceIn(0f, 1f)
}

/**
 * Вешается на viewport-контейнер разворота (как wornPage): крошки лежат на
 * «листе», а не уезжают со скроллом. Горизонтальный свайп длиннее 48dp
 * запускает анимацию смахивания; вертикальный скролл жест не трогает.
 */
fun Modifier.crumbs(bakeCount: Int, seed: Long): Modifier {
    if (Crumbs.crumbCount(bakeCount) == 0) return this
    return this then CrumbsElement(bakeCount, seed)
}

/** Сколько длится полёт смахнутых крошек. */
private const val CRUMB_SWEEP_MS = 850

/**
 * Cycle 16: собственный узел вместо composed {}. Крошки — один из двух «живых»
 * слоёв книги, и свести их к drawWithCache было нельзя: тут не «посчитать раз от
 * размера», а жест, анимация полёта и флаг «уже смахнули».
 */
private data class CrumbsElement(
    private val bakeCount: Int,
    private val seed: Long,
) : ModifierNodeElement<CrumbsNode>() {

    override fun create(): CrumbsNode = CrumbsNode(bakeCount, seed)

    override fun update(node: CrumbsNode) {
        node.update(bakeCount, seed)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "crumbs"
        properties["bakeCount"] = bakeCount
        properties["seed"] = seed
    }
}

private class CrumbsNode(
    private var bakeCount: Int,
    private var seed: Long,
) : DelegatingNode(), DrawModifierNode {

    private var particles = scatterFor(bakeCount, seed)
    private var sweep = Animatable(0f)
    private var direction = 1f
    private var swept = false

    private val pointer = delegate(SuspendingPointerInputModifierNode { awaitSweep() })

    private fun scatterFor(bakeCount: Int, seed: Long) =
        Crumbs.scatter(seed * 31 + bakeCount, Crumbs.crumbCount(bakeCount), Crumbs.dustCount(bakeCount))

    /**
     * После новой выпечки страница присыпается заново — ровно то, что раньше
     * делали ключи remember(seed, bakeCount): и раскладка, и «уже смахнули»
     * начинаются с чистого листа.
     */
    fun update(bakeCount: Int, seed: Long) {
        if (this.bakeCount == bakeCount && this.seed == seed) return
        this.bakeCount = bakeCount
        this.seed = seed
        particles = scatterFor(bakeCount, seed)
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
                        // Кадр анимации перерисовывается явно: узел рисует по
                        // sweep.value, и ждать, что чтение само себя пригласит
                        // на перерисовку, здесь не за чем.
                        sweep.animateTo(1f, tween(CRUMB_SWEEP_MS, easing = LinearEasing)) {
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
        val alpha = Crumbs.sweepAlpha(progress)
        particles.forEach { p -> drawCrumb(p, progress, direction, alpha) }
    }
}

private val CrumbTones = listOf(Cocoa, Crust, AmberDeep, Flour)

private fun DrawScope.drawCrumb(p: Crumbs.Particle, progress: Float, direction: Float, alpha: Float) {
    val cx = p.x * size.width + Crumbs.sweepDx(progress, p.drift, direction, size.width)
    val cy = p.y * size.height + Crumbs.sweepDy(progress, p.y, size.height)
    val tone = CrumbTones[p.toneIndex]
    if (p.isDust) {
        drawCircle(tone.copy(alpha = 0.30f * alpha), p.sizeDp.dp.toPx(), Offset(cx, cy))
    } else {
        val w = p.sizeDp.dp.toPx()
        val h = w * 0.72f
        // В полёте крошка ещё и докручивается — живее, чем строгий параллельный перенос.
        rotate(p.rotationDeg + progress * 160f * direction, pivot = Offset(cx, cy)) {
            drawRoundRect(
                color = tone.copy(alpha = 0.75f * alpha),
                topLeft = Offset(cx - w / 2, cy - h / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w * 0.3f),
            )
        }
    }
}
