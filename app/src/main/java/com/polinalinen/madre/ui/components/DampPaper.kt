package com.polinalinen.madre.ui.components

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.Cool
import kotlin.random.Random

/**
 * «Погода за окном» (DESIGN-V4.md Cycle 7, фича WeatherPage): в дождь бумага
 * первой полосы слегка отсыревает — холодноватые (Cool) разводы у кромок,
 * будто книга лежала у приоткрытого окна. [alpha] приходит из
 * WeatherPage.dampAlpha; при 0 модификатор исчезает бесследно.
 * Разводы детерминированы от [seed] — не мигают при рекомпозиции (приём WornPage).
 */
private data class Blotch(val x: Float, val y: Float, val radius: Float, val weight: Float)

fun Modifier.dampPaper(alpha: Float, seed: Long = 17L): Modifier = composed {
    if (alpha <= 0f) return@composed Modifier
    val blotches = remember(seed) {
        val rng = Random(seed)
        List(5) {
            Blotch(
                // Разводы жмутся к левой/правой кромке — середина страницы сухая.
                x = if (rng.nextBoolean()) rng.nextFloat() * 0.12f else 1f - rng.nextFloat() * 0.12f,
                y = rng.nextFloat(),
                radius = 60f + rng.nextFloat() * 80f,
                weight = 0.6f + rng.nextFloat() * 0.4f,
            )
        }
    }
    drawWithContent {
        drawContent()
        // Отсыревший верхний срез — там книга ближе всего к окну.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Cool.copy(alpha = alpha * 0.55f), Color.Transparent),
                startY = 0f,
                endY = 80.dp.toPx(),
            ),
            size = androidx.compose.ui.geometry.Size(size.width, 80.dp.toPx()),
        )
        blotches.forEach { b ->
            val center = Offset(b.x * size.width, b.y * size.height)
            val radius = b.radius.dp.toPx() * 0.5f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Cool.copy(alpha = alpha * b.weight), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}
