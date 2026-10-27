package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.StepType

/** Display only: never rounds or mutates the timeline's actual minutes. */
internal fun readingDuration(minutes:Long):String {
    val m=minutes.coerceAtLeast(0)
    return if(m<60) "$m мин" else "${m/60} ч"+if(m%60==0L)"" else " ${m%60} мин"
}
internal fun stepOverline(recipe:Recipe,index:Int):String {
    val step=recipe.timeline[index]
    val elapsed=recipe.timeline.take(index).sumOf{it.durationMinutes.toLong()}
    val start=if(elapsed==0L)"Сейчас" else "Через ${readingDuration(elapsed)}"
    return "$start · "+(if(step.type==StepType.WAIT)"Ждём " else "")+readingDuration(step.durationMinutes.toLong())
}
internal fun difficultyLabel(difficulty:Int):String=when(difficulty) {
    1,2->"легко"
    3->"средне"
    4,5->"сложно"
    else->"не указана"
}
