package com.polinalinen.madre.ui.components

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.Cocoa
import com.polinalinen.madre.ui.theme.Espresso
import com.polinalinen.madre.ui.theme.Flour
import kotlin.random.Random

/**
 * DESIGN-V4.md Cycle 4, фича «Затёртая страница» (WornPages). Часто печёные
 * рецепты изнашиваются: 3 выпечки — потемнение края, 7 — отпечаток пальца в
 * углу, 15 — потёртость у корешка. Ноль новых данных: всё выводится из
 * count(bakeRecords) конкретного рецепта. Пороги и альфы — чистые функции,
 * вынесены из Composable ради юнит-теста (тот же приём, что InkBlot).
 */
object WornPage {
    const val EDGE_AT = 3
    const val FINGERPRINT_AT = 7
    const val SPINE_AT = 15

    fun showsEdgeDarkening(bakeCount: Int): Boolean = bakeCount >= EDGE_AT
    fun showsFingerprint(bakeCount: Int): Boolean = bakeCount >= FINGERPRINT_AT
    fun showsSpineWear(bakeCount: Int): Boolean = bakeCount >= SPINE_AT

    /**
     * Насколько заметно потемнение края: растёт с каждой выпечкой сверх порога,
     * но с мягким потолком — даже самый затёртый рецепт остаётся читаемым.
     */
    fun edgeAlpha(bakeCount: Int): Float =
        if (!showsEdgeDarkening(bakeCount)) 0f
        else (0.35f + 0.03f * (bakeCount - EDGE_AT)).coerceAtMost(0.6f)

    /**
     * Затемнение строки оглавления (HomeScreen): нетронутые главы — 0,
     * дальше едва заметная тонировка Espresso с потолком, чтобы строка
     * никогда не спорила с текстом.
     */
    fun tocAlpha(bakeCount: Int): Float =
        if (bakeCount < EDGE_AT) 0f
        else (0.03f + 0.005f * (bakeCount - EDGE_AT)).coerceAtMost(0.08f)
}

/**
 * Накладывает следы использования поверх страницы. Вешается на viewport-контейнер
 * (Box вокруг скролла), а не на скроллящийся Column — износ живёт на «листе»,
 * а не уезжает вместе с текстом. Рисование — детерминированное от [seed]
 * (id рецепта), чтобы потёртости не мигали при рекомпозиции.
 */
fun Modifier.wornPage(bakeCount: Int, seed: Long): Modifier = composed {
    if (!WornPage.showsEdgeDarkening(bakeCount)) return@composed Modifier
    val jitter = remember(seed) {
        val rng = Random(seed)
        List(6) { rng.nextFloat() }
    }
    drawWithContent {
        drawContent()
        drawEdgeDarkening(WornPage.edgeAlpha(bakeCount))
        if (WornPage.showsFingerprint(bakeCount)) drawFingerprint(jitter)
        if (WornPage.showsSpineWear(bakeCount)) drawSpineWear(jitter)
    }
}

/**
 * Потемнение внешнего (правого) края: градиент Flour -> Parchment-прозрачность,
 * как у книги, которую много раз брали с полки. [alpha] — вес самой тёмной точки.
 */
private fun DrawScope.drawEdgeDarkening(alpha: Float) {
    val strip = 26.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Flour.copy(alpha = alpha)),
            startX = size.width - strip,
            endX = size.width,
        ),
        topLeft = Offset(size.width - strip, 0f),
        size = androidx.compose.ui.geometry.Size(strip, size.height),
    )
}

/**
 * Полупрозрачный отпечаток пальца в нижнем правом углу — концентрические
 * овальные дуги мучной руки. Позиция слегка гуляет от seed, alpha низкая:
 * след, а не пятно.
 */
private fun DrawScope.drawFingerprint(jitter: List<Float>) {
    val cx = size.width - 40.dp.toPx() - jitter[0] * 14.dp.toPx()
    val cy = size.height - 52.dp.toPx() - jitter[1] * 20.dp.toPx()
    val tilt = -20f + jitter[2] * 40f
    rotate(tilt, pivot = Offset(cx, cy)) {
        repeat(5) { ring ->
            val rw = (7 + ring * 4).dp.toPx()
            val rh = rw * 1.35f
            drawOval(
                color = Cocoa.copy(alpha = 0.07f - ring * 0.008f),
                topLeft = Offset(cx - rw / 2, cy - rh / 2),
                size = androidx.compose.ui.geometry.Size(rw, rh),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * Потёртость у корешка (левый край): узкая тень + несколько вертикальных
 * царапин разной длины — место, где страницу заламывали при переплёте.
 */
private fun DrawScope.drawSpineWear(jitter: List<Float>) {
    val strip = 14.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Espresso.copy(alpha = 0.08f), Color.Transparent),
            startX = 0f,
            endX = strip,
        ),
        size = androidx.compose.ui.geometry.Size(strip, size.height),
    )
    repeat(3) { i ->
        val x = (3 + i * 3).dp.toPx() + jitter[3 + i] * 2.dp.toPx()
        val yStart = size.height * (0.15f + jitter[i] * 0.25f)
        val yEnd = yStart + size.height * (0.2f + jitter[5 - i] * 0.3f)
        drawLine(
            color = Espresso.copy(alpha = 0.06f),
            start = Offset(x, yStart),
            end = Offset(x, yEnd),
            strokeWidth = 1.dp.toPx(),
        )
    }
}
