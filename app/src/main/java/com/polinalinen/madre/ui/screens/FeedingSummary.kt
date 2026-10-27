package com.polinalinen.madre.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.kit.*

/** Presentation only: ingredient masses, not hydration, measured volume or growth. */
internal data class FeedingSummaryMasses(val starter: Int, val flour: Int, val water: Int) {
    val total get() = starter + flour + water
    val fractions get() = listOf(starter, flour, water).map { it.toFloat() / total }
    val ratio: String get() {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val divisor = gcd(gcd(starter, flour), water)
        return listOf(starter, flour, water).joinToString(" : ") { (it / divisor).toString() }
    }
}

// Exposes the actual drawn proportions for clock-driven tests, not spoken transient facts.
internal val FeedingLayerFractions = SemanticsPropertyKey<List<Float>>("FeedingLayerFractions")

@Composable
internal fun FeedingSummary(
    masses: FeedingSummaryMasses,
    finalHydration: Int,
    priorContext: String,
) {
    val p = LocalPaper.current
    // Navigation also maps the reading preference to LocalCalmMode; respect either source.
    val reduced = LocalCalmMode.current || rememberReadingPreferences().reducedMotion
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FeedingMassJar(masses, reduced)
            Column(Modifier.weight(1f)) {
                Text("Станет в банке ${masses.total} г", style = PaperType.body, color = p.ink)
                Text(masses.ratio, style = PaperType.ui, color = p.ink2)
                Text("Гидратация после кормления", style = PaperType.caption, color = p.ink2,
                    modifier = Modifier.padding(top = 8.dp))
                Text("$finalHydration%", style = PaperType.ui.copy(
                    fontSize = 46.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraLight), color = p.acc,
                    modifier = Modifier.semantics {
                        contentDescription = "Гидратация после кормления: $finalHydration процентов, посчитана книгой"
                    })
            }
        }
        Text(priorContext, style = PaperType.caption, color = p.ink2, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun FeedingMassJar(masses: FeedingSummaryMasses, reduced: Boolean) {
    val p = LocalPaper.current
    val fractions = masses.fractions.map { target ->
        if (reduced) target else {
            val animated by animateFloatAsState(target, tween(500), label = "feeding-mass-layer")
            animated
        }
    }
    Canvas(Modifier.size(68.dp, 96.dp).testTag("feeding-mass-jar").semantics {
        contentDescription = "Слои по массе: закваска ${masses.starter} г, мука ${masses.flour} г, вода ${masses.water} г. " +
            "Соотношение масс, не измерение объёма или роста"
        this[FeedingLayerFractions] = fractions
    }) {
        val w = size.width; val h = size.height
        val bodyTop = h * .2f
        val bodySize = Size(w * .86f, h * .77f)
        val bodyOffset = Offset(w * .07f, bodyTop)
        val body = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
            bodyOffset.x, bodyOffset.y, bodyOffset.x + bodySize.width, bodyOffset.y + bodySize.height,
            CornerRadius(10.dp.toPx()))) }
        clipPath(body) {
            var bottom = bodyOffset.y + bodySize.height
            val pigments = listOf(Illustration.JarDoughShadow, Illustration.JarDough, Illustration.PageCool)
            fractions.forEachIndexed { i, fraction ->
                val layerHeight = bodySize.height * fraction
                drawRect(pigments[i], Offset(bodyOffset.x, bottom - layerHeight), Size(bodySize.width, layerHeight))
                bottom -= layerHeight
            }
            drawRoundRect(Illustration.JarBubble.copy(alpha = .45f), Offset(w * .17f, h * .28f),
                Size(3.dp.toPx(), h * .57f), CornerRadius(2.dp.toPx()))
        }
        drawPath(body, p.ink2, style = Stroke(1.dp.toPx()))
        drawRoundRect(Illustration.JarLid, Offset(w * .04f, h * .07f), Size(w * .92f, h * .12f), CornerRadius(3.dp.toPx()))
        drawOval(Illustration.JarLidTop, Offset(w * .04f, h * .04f), Size(w * .92f, h * .08f))
    }
}
