package com.polinalinen.madre.account.yandex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.kit.LocalPaper

/** Y01's book + Station line art, transcribed from the approved I-board source; not a connection status. */
@Composable internal fun YandexOfferIllustration(modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    Canvas(modifier.size(220.dp, 150.dp)) {
        withTransform({ scale(size.width / 220f, size.height / 150f, Offset.Zero) }) {
            val pages = Path().apply {
                moveTo(30f,30f); cubicTo(52f,22f,76f,22f,100f,34f); lineTo(100f,134f)
                cubicTo(76f,122f,52f,122f,30f,130f); close()
                moveTo(100f,34f); cubicTo(124f,22f,148f,22f,170f,30f); lineTo(170f,130f)
                cubicTo(148f,122f,124f,122f,100f,134f)
                for (y in listOf(54f,70f,86f)) { moveTo(44f,y); cubicTo(58f,y-3,72f,y-2,86f,y+3) }
                for (y in listOf(57f,73f)) { moveTo(114f,y); cubicTo(128f,y-5,142f,y-6,156f,y-3) }
            }
            drawPath(pages,p.ink2,style=Stroke(1.7f))
            drawPath(Path().apply { moveTo(150f,30f); lineTo(150f,64f); lineTo(157f,58f); lineTo(164f,64f); lineTo(164f,30f) },p.rib,style=Stroke(1.7f))
            drawCircle(p.ink2,18f,Offset(186f,104f),style=Stroke(1.7f))
            drawCircle(p.ink2,7f,Offset(186f,104f),style=Stroke(1.7f))
            drawPath(Path().apply { moveTo(178f,84f); cubicTo(183f,78f,189f,78f,194f,84f) },p.acc,style=Stroke(1.7f))
            drawPath(Path().apply { moveTo(172f,78f); cubicTo(180f,68f,192f,68f,200f,78f) },p.acc.copy(alpha=.5f),style=Stroke(1.7f))
        }
    }
}
