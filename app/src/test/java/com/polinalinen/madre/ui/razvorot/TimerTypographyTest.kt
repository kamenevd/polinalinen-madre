package com.polinalinen.madre.ui.razvorot

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.polinalinen.madre.ui.kit.timerProgress
import com.polinalinen.madre.ui.kit.timerWeight
import com.polinalinen.madre.model.*

class TimerTypographyTest {
    @Test fun exactProgressAndWeights() {
        assertThat(timerProgress(2400,2400)).isEqualTo(0f)
        assertThat(timerProgress(2400,1200)).isEqualTo(.5f)
        assertThat(timerProgress(2400,0)).isEqualTo(1f)
        assertThat(timerWeight(0f)).isEqualTo(200)
        assertThat(timerWeight(.5f)).isEqualTo(500)
        assertThat(timerWeight(1f)).isEqualTo(800)
    }
    @Test fun pauseStepZeroAndLongTimeDoNotResetProgress() {
        val remaining=1200L
        val recipe=Recipe("timer-test","Тест","","",emptyMap(),listOf(TimelineStep(StepType.WAIT,"Шаг1","",40),TimelineStep(StepType.WAIT,"Шаг2","",20)))
        val paused=BakingSession(1,recipe,0,0,0,isPaused=true,pausedAtElapsed=1_200_000L)
        assertThat(paused.remainingSeconds(1_200_000L)).isEqualTo(remaining)
        assertThat(paused.remainingSeconds(9_000_000L)).isEqualTo(remaining)
        assertThat(timerWeight(timerProgress(paused.currentStep.durationMinutes*60L,paused.remainingSeconds(9_000_000L)))).isEqualTo(500)
        val next=paused.copy(currentStepIndex=1,isPaused=false,pausedAtElapsed=null,startedAtElapsed=9_000_000L)
        assertThat(timerWeight(timerProgress(next.currentStep.durationMinutes*60L,next.remainingSeconds(9_000_000L)))).isEqualTo(200)
        assertThat(timerProgress(1200,remaining)).isEqualTo(0f) // next step's duration
        assertThat(timerProgress(0,0)).isEqualTo(1f)
        assertThat(timerProgress(2400,-1)).isEqualTo(1f)
        assertThat(timerProgress(2400,2500)).isEqualTo(0f)
        assertThat(timerProgress(7_200_000,3_600_000)).isEqualTo(.5f)
        assertThat(timerDigits(7201)).isEqualTo("2:00:01")
    }
}
