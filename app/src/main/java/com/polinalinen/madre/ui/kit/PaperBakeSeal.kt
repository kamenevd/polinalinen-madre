package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An ink impression, never a button. The date belongs to the completed record, not today's clock. */
@Composable fun PaperBakeSeal(completedAtMillis:Long,modifier:Modifier=Modifier) {
    val date=Instant.ofEpochMilli(completedAtMillis).atZone(ZoneId.systemDefault())
    val roman=listOf("I","II","III","IV","V","VI","VII","VIII","IX","X","XI","XII")
    val label="${date.dayOfMonth}.${roman[date.monthValue-1]}"
    val weekday=date.format(DateTimeFormatter.ofPattern("EEEE",Locale.forLanguageTag("ru")))
    val p=LocalPaper.current
    val scale=LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(modifier.size(104.dp).rotate(-12f).clearAndSetSemantics{contentDescription="Испечено · $label · $weekday"},contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(p.rib,radius=size.minDimension*.47f,style=Stroke(1.5.dp.toPx()))
            drawCircle(p.rib,radius=size.minDimension*.36f,style=Stroke(.8.dp.toPx()))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text("ИСПЕЧЕНО",style=PaperType.overline.copy(fontSize=(8f/scale).sp,lineHeight=(12f/scale).sp,letterSpacing=(1f/scale).sp),color=p.rib)
            Text(label,style=PaperType.h2.copy(fontSize=(22f/scale).sp,lineHeight=(28f/scale).sp),color=p.rib)
            Text(weekday,style=PaperType.note.copy(fontSize=(11f/scale).sp,lineHeight=(16f/scale).sp),color=p.rib)
        }
    }
}
