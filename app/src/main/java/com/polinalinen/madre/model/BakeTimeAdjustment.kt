package com.polinalinen.madre.model

/** Session-local timer correction; canonical recipe assets and voice authority remain unchanged. */
fun BakingSession.adjustTimer(minutes: Int, nowElapsed: Long, nowWall: Long): BakingSession {
    require(!isCompleted && restoredStepValid && minutes in setOf(-5, 5, 15))
    val delta = minutes * 60_000L
    val duration = (currentStep.durationMinutes.toLong() + minutes).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val total = duration * 60_000L
    val remaining = ((stepEndsAtElapsed(nowElapsed)-nowElapsed).coerceAtLeast(0) + delta).coerceIn(0, total)
    val until = if(isPaused)pausedAtElapsed?:nowElapsed else nowElapsed
    val start = until-(total-remaining)
    val steps = recipe.timeline.mapIndexed { index, step -> if(index==currentStepIndex)step.copy(durationMinutes=duration) else step }
    return copy(recipe=recipe.copy(timeline=steps),startedAtElapsed=start,
        startedAtWallClock=nowWall-(nowElapsed-start),revision=Math.addExact(revision,1L))
}
