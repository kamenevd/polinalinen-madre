package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/** Unbranded 24-unit outline symbols from the existing Y01 vocabulary, one 1.7-unit stroke. */
@Composable internal fun PaperBenefitIcon(kind:Int) {
    val ink=LocalPaper.current.ink2
    Canvas(Modifier.size(24.dp)) {
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero) {
            val stroke=Stroke(1.7f,cap=StrokeCap.Round)
            fun line(x:Float,y:Float,xx:Float,yy:Float)=drawLine(ink,Offset(x,y),Offset(xx,yy),1.7f,StrokeCap.Round)
            when(kind) {
                0->{
                    drawRoundRect(ink,Offset(9f,2f),Size(6f,12f),CornerRadius(3f),style=stroke)
                    drawArc(ink,0f,180f,false,Offset(5f,7f),Size(14f,11f),style=stroke)
                    line(12f,18f,12f,22f);line(8f,22f,16f,22f)
                }
                1->{
                    val cloud=Path().apply {
                        moveTo(6f,19f);cubicTo(0f,19f,0f,10f,6f,10f)
                        cubicTo(7f,2f,17f,2f,19f,10f);cubicTo(25f,10f,25f,19f,19f,19f);close()
                    }
                    drawPath(cloud,ink,style=stroke)
                }
                2->{
                    drawCircle(ink,3.5f,Offset(12f,7f),style=stroke)
                    drawArc(ink,180f,180f,false,Offset(4f,13f),Size(16f,14f),style=stroke)
                    line(4f,20f,4f,22f);line(20f,20f,20f,22f)
                }
                else->{
                    drawCircle(ink,3f,Offset(9f,7f),style=stroke)
                    drawArc(ink,180f,180f,false,Offset(2f,13f),Size(14f,14f),style=stroke)
                    drawArc(ink,-90f,180f,false,Offset(14f,4f),Size(6f,6f),style=stroke)
                    drawArc(ink,-90f,90f,false,Offset(16f,13f),Size(12f,14f),style=stroke)
                }
            }
        }
    }
}
