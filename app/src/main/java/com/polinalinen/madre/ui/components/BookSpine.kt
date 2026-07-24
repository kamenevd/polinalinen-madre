package com.polinalinen.madre.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.AppColors

/**
 * DESIGN-V4.md Cycle 2, фича «Растущий корешок» (SpineGrowth). Толщина и
 * количество потёртостей — чистые функции от числа записей (bakeRecords +
 * feedingRecords), вынесены из Composable, чтобы их можно было проверить
 * юнит-тестом без Robolectric/Compose runtime.
 */
object SpineGrowth {
    private const val BASE_THICKNESS_DP = 14f
    private const val MAX_THICKNESS_DP = 56f
    private const val DP_PER_RECORD = 0.6f
    private const val RECORDS_PER_WORN_LINE = 4
    private const val MAX_WORN_LINES = 12

    /** Толще с каждой записью, но не бесконечно — корешок настоящей книги не резиновый. */
    fun thicknessDp(recordCount: Int): Float =
        (BASE_THICKNESS_DP + recordCount.coerceAtLeast(0) * DP_PER_RECORD).coerceAtMost(MAX_THICKNESS_DP)

    /** Одна потёртость на каждые несколько записей — не больше [MAX_WORN_LINES]. */
    fun wornLineCount(recordCount: Int): Int =
        (recordCount.coerceAtLeast(0) / RECORDS_PER_WORN_LINE).coerceAtMost(MAX_WORN_LINES)
}

/**
 * Корешок книги сбоку — растёт с историей семьи (выпечки + кормления).
 * Espresso — основа корешка, Caramel/Cocoa — потёртости и капталы (headband).
 * Углы ≤4dp (DESIGN-V4.md §«Графический язык»/Углы — не пластик, а бумага/ткань).
 */
@Composable
fun BookSpine(
    bakeCount: Int,
    feedingCount: Int,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    val colors = AppColors.current
    val totalRecords = bakeCount + feedingCount
    val thickness = SpineGrowth.thicknessDp(totalRecords).dp
    val wornLines = SpineGrowth.wornLineCount(totalRecords)

    Canvas(modifier.width(thickness).height(height)) {
        val w = size.width
        val h = size.height.coerceAtLeast(1f)
        val corner = CornerRadius(4.dp.toPx())

        drawRoundRect(colors.espresso, cornerRadius = corner)
        // Рамка тиснения по краю корешка — тон чуть теплее, тонкой волосяной линией.
        drawRoundRect(colors.caramel.copy(alpha = 0.5f), cornerRadius = corner, style = Stroke(1.dp.toPx()))

        // Капталы (headband) — тканевая полоска сверху/снизу настоящего корешка.
        val headband = 6.dp.toPx().coerceAtMost(h / 4f)
        drawRect(colors.caramel, topLeft = Offset(0f, 0f), size = Size(w, headband))
        drawRect(colors.caramel, topLeft = Offset(0f, h - headband), size = Size(w, headband))

        // Потёртости — чем длиннее история, тем их больше.
        if (wornLines > 0) {
            val step = h / (wornLines + 1)
            repeat(wornLines) { i ->
                val y = step * (i + 1)
                val tone = if (i % 2 == 0) colors.cocoa else colors.caramel
                drawLine(
                    color = tone.copy(alpha = 0.55f),
                    start = Offset(w * 0.15f, y),
                    end = Offset(w * 0.85f, y),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
        }
    }
}
