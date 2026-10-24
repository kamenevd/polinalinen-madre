package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.components.bookAction

/** Measure the value at its natural width, then the full available title; dots take only the remainder. */
@Composable internal fun PaperLeaderLine(modifier:Modifier=Modifier,name:@Composable ()->Unit,value:@Composable ()->Unit) {
    val p=LocalPaper.current
    Layout(content={name();value();Canvas(Modifier){drawLine(p.line,Offset.Zero,Offset(size.width,0f),2.dp.toPx(),
        pathEffect=PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(),4.dp.toPx()))) }},modifier=modifier.fillMaxWidth()) { items,c ->
        val amount=items[1].measure(c.copy(minWidth=0,minHeight=0))
        val gap=8.dp.roundToPx()
        val title=items[0].measure(c.copy(minWidth=0,minHeight=0,maxWidth=(c.maxWidth-amount.width-gap).coerceAtLeast(0)))
        val baseline=maxOf(title[FirstBaseline].takeIf{it!=androidx.compose.ui.layout.AlignmentLine.Unspecified}?:title.height,
            amount[FirstBaseline].takeIf{it!=androidx.compose.ui.layout.AlignmentLine.Unspecified}?:amount.height)
        val titleY=(baseline-(title[FirstBaseline].takeIf{it!=androidx.compose.ui.layout.AlignmentLine.Unspecified}?:title.height)).coerceAtLeast(0)
        val amountY=(baseline-(amount[FirstBaseline].takeIf{it!=androidx.compose.ui.layout.AlignmentLine.Unspecified}?:amount.height)).coerceAtLeast(0)
        val dots=items[2].measure(Constraints.fixed((c.maxWidth-title.width-amount.width-gap).coerceAtLeast(0),2.dp.roundToPx()))
        layout(c.maxWidth,maxOf(title.height+titleY,amount.height+amountY)) {
            title.placeRelative(0,titleY)
            amount.placeRelative(c.maxWidth-amount.width,amountY)
            dots.placeRelative(title.width+gap/2,(baseline-2.dp.roundToPx()).coerceAtLeast(0))
        }
    }
}

@Composable fun PaperPortionSummary(value:String,onClick:(()->Unit)?,modifier:Modifier=Modifier) {
    val p=LocalPaper.current
    Row(modifier.fillMaxWidth().background(p.bg2,RoundedCornerShape(14.dp))
        .then(if(onClick!=null)bookAction("Сколько печём",repeatable=true,onClick=onClick) else Modifier)
        .padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SmallLabel("НА СКОЛЬКО ПЕЧЁМ",color=p.ink2)
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(value,style=PaperType.number,color=p.ink)
                Text("семьи",style=PaperType.note,color=p.ink2,modifier=Modifier.padding(bottom=6.dp))
            }
        }
        Text("− / + внизу",style=PaperType.note,color=p.ink2)
    }
}
