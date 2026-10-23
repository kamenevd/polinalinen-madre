package com.polinalinen.madre.ui.razvorot

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.TextUnit
import com.polinalinen.madre.R
import kotlin.math.roundToInt

internal fun timerProgress(durationSeconds:Long,remainingSeconds:Long):Float =
    if(durationSeconds<=0L) { if(remainingSeconds<=0L)1f else 0f }
    else (1.0-remainingSeconds.coerceIn(0L,durationSeconds).toDouble()/durationSeconds).toFloat()

internal fun timerWeight(progress:Float):Int = (200f+600f*progress.coerceIn(0f,1f)).roundToInt()

/** Manrope contains a real wght axis200..800. Explicit variation avoids synthetic
 * bold selection from a single default400 face; both timer views share this path. */
@OptIn(ExperimentalTextApi::class)
@Composable internal fun TimerNumber(text:String,progress:Float,size:TextUnit,spacing:TextUnit,color:Color,modifier:Modifier=Modifier) {
    val weight=timerWeight(progress)
    val family=remember(weight){FontFamily(Font(R.font.manrope,weight=FontWeight(weight),variationSettings=FontVariation.Settings(FontVariation.weight(weight))))}
    Text(text,modifier,fontFamily=family,fontWeight=FontWeight(weight),fontSize=size,letterSpacing=spacing,color=color,maxLines=1,style=TextStyle(fontSynthesis=FontSynthesis.None))
}
