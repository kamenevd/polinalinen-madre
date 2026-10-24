package com.polinalinen.madre.ui.razvorot

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import com.polinalinen.madre.ui.kit.*

@Composable internal fun PlanValue(label:String,value:String,suffix:String="") {
    Text(buildAnnotatedString {
        append(label)
        withStyle(PaperType.ui.toSpanStyle().copy(fontWeight=FontWeight.Bold,fontStyle=FontStyle.Normal)){append(value)}
        append(suffix)
    },style=PaperType.note,color=LocalPaper.current.ink2)
}
