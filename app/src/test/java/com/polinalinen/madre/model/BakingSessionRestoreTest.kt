package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BakingSessionRestoreTest {
    private fun recipe() = Recipe(
        id = "r1",
        name = "Тест",
        emoji = "",
        description = "",
        ingredients = emptyMap(),
        timeline = listOf(
            TimelineStep(StepType.WAIT, "Ждём", "", 60),
            TimelineStep(StepType.ACTION, "Делаем", "", 10),
        ),
    )

    @Test
    fun restoreRebasesMonotonicClockFromWallClock() {
        val startedWall = 1_000_000L
        val nowWall = startedWall + 30_000L
        val nowElapsed = 500_000L
        val s = BakingSession.restoreFromActive(
            sessionId = 7L,
            recipe = recipe(),
            stepIndex = 0,
            startedAtWallClock = startedWall,
            pausedAtWallClock = null,
            nowElapsed = nowElapsed,
            nowWallClock = nowWall,
        )
        assertThat(s.id).isEqualTo(7L)
        assertThat(s.currentStepIndex).isEqualTo(0)
        assertThat(s.isPaused).isFalse()
        assertThat(s.remainingSeconds(nowElapsed)).isEqualTo(60 * 60L - 30L)
    }

    @Test
    fun restoreKeepsPauseFrozen() {
        val startedWall = 1_000_000L
        val pausedWall = startedWall + 10_000L
        val nowWall = pausedWall + 100_000L
        val nowElapsed = 900_000L
        val s = BakingSession.restoreFromActive(
            sessionId = 3L,
            recipe = recipe(),
            stepIndex = 0,
            startedAtWallClock = startedWall,
            pausedAtWallClock = pausedWall,
            nowElapsed = nowElapsed,
            nowWallClock = nowWall,
        )
        assertThat(s.isPaused).isTrue()
        assertThat(s.remainingSeconds(nowElapsed)).isEqualTo(60 * 60L - 10L)
    }
}
