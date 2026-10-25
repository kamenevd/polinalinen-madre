package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class PaperFeedingMassIconKind { JAR, WHEAT, DROP }

/** Feeding-only decorative glyphs in PaperBenefitIcon's 24-unit, 1.7-unit outline vocabulary. */
@Composable
internal fun PaperFeedingMassIcon(kind: PaperFeedingMassIconKind) {
    val ink = LocalPaper.current.ink2
    Canvas(Modifier.size(24.dp).testTag("feeding-mass-icon-${kind.name}")) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, xx: Float, yy: Float) =
                drawLine(ink, Offset(x, y), Offset(xx, yy), 1.7f, StrokeCap.Round)
            when (kind) {
                PaperFeedingMassIconKind.JAR -> {
                    drawRoundRect(ink, Offset(6f, 3f), Size(12f, 3f), CornerRadius(1f), style = stroke)
                    drawRoundRect(ink, Offset(5f, 6f), Size(14f, 15f), CornerRadius(3f), style = stroke)
                    line(5f, 13f, 19f, 13f)
                }
                PaperFeedingMassIconKind.WHEAT -> {
                    line(12f, 3f, 12f, 22f)
                    for (y in listOf(6f, 11f, 16f)) {
                        drawPath(Path().apply {
                            moveTo(12f, y + 3f)
                            quadraticTo(6f, y + 3f, 6f, y - 2f)
                            quadraticTo(11f, y - 2f, 12f, y + 3f)
                            moveTo(12f, y + 3f)
                            quadraticTo(18f, y + 3f, 18f, y - 2f)
                            quadraticTo(13f, y - 2f, 12f, y + 3f)
                        }, ink, style = stroke)
                    }
                }
                PaperFeedingMassIconKind.DROP -> drawPath(Path().apply {
                    moveTo(12f, 2f)
                    cubicTo(10f, 6f, 5f, 11f, 5f, 15f)
                    cubicTo(5f, 23f, 19f, 23f, 19f, 15f)
                    cubicTo(19f, 11f, 14f, 6f, 12f, 2f)
                    close()
                }, ink, style = stroke)
            }
        }
    }
}
