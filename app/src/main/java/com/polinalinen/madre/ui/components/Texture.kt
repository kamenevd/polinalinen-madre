package com.polinalinen.madre.ui.components

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.Espresso
import kotlin.random.Random

/**
 * decision #25: "Литерально текстура мешковины/дерева как поверхность карточки" —
 * не картинка-ассет (их не генерировали), а лёгкий процедурный узор поверх Cream/Parchment,
 * почти невидимый (alpha 2-3%), чтобы не спорить с контентом карточки.
 *
 * Woven cloth: сетка тонких диагональных линий в две стороны — имитация плетения мешковины.
 */
fun Modifier.wovenClothTexture(tint: Color = Espresso, alpha: Float = 0.035f): Modifier = composed {
    drawWithContent {
        drawContent()
        val step = 10.dp.toPx()
        rotate(45f) {
            var x = -size.height
            while (x < size.width + size.height) {
                drawLine(
                    color = tint.copy(alpha = alpha),
                    start = Offset(x, -size.height),
                    end = Offset(x, size.height * 2),
                    strokeWidth = 1f,
                )
                x += step
            }
        }
        rotate(-45f) {
            var x = -size.height
            while (x < size.width + size.height) {
                drawLine(
                    color = tint.copy(alpha = alpha),
                    start = Offset(x, -size.height),
                    end = Offset(x, size.height * 2),
                    strokeWidth = 1f,
                )
                x += step
            }
        }
    }
}

/**
 * v4-screen-inventory "Decorative elements": scattered flour dust particles.
 * Используется один раз на Home (за greeting), НЕ на каждой карточке — иначе шумно.
 */
fun Modifier.flourDust(count: Int = 14, tint: Color = Espresso, seed: Int = 42): Modifier = composed {
    val points = remember(seed) {
        val random = Random(seed)
        List(count) {
            Triple(random.nextFloat(), random.nextFloat(), random.nextFloat() * 2.5f + 0.5f)
        }
    }
    drawWithContent {
        drawContent()
        points.forEach { (fx, fy, radiusDp) ->
            drawCircle(
                color = tint.copy(alpha = 0.05f),
                radius = radiusDp.dp.toPx(),
                center = Offset(fx * size.width, fy * size.height),
            )
        }
    }
}
