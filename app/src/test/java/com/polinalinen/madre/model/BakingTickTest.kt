package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 20: тик таймера обязан попасть В ноль, а не мимо него.
 *
 * Ровный сон по секунде промахивается по границе шага: шаг, начавшийся в
 * 12:00:00.400, кончается в 12:59:59.400, а тики идут в .400 — то есть в ноль
 * страница попадает не сразу, и до следующего тика на ней стоит 0:01 при уже
 * закончившемся шаге. То же число уезжает в шторку и в базу хронометра.
 *
 * Здесь «сейчас» — просто числа: ни часов, ни ожидания в реальном времени.
 */
class BakingTickTest {

    private val startElapsed = 5_000_000L
    private val startWallClock = 1_700_000_000_000L

    private fun session(minutes: Int = 60, isPaused: Boolean = false, pausedAtElapsed: Long? = null) =
        BakingSession(
            id = 1L,
            recipe = Recipe(
                id = "bread", name = "Бородинский", emoji = "", description = "",
                ingredients = emptyMap(),
                timeline = listOf(
                    TimelineStep(StepType.WAIT, "Расстойка", "", minutes),
                    TimelineStep(StepType.ACTION, "Формовка", "", 10),
                ),
            ),
            startedAtElapsed = startElapsed,
            startedAtWallClock = startWallClock,
            isPaused = isPaused,
            pausedAtElapsed = pausedAtElapsed,
        )

    // ————— точный конец шага —————

    @Test
    fun `the step ends at its own moment, not at now plus a rounded remainder`() {
        val s = session(minutes = 60)
        // Конец шага не зависит от того, когда о нём спросили: это свойство
        // самого шага, а не момента вопроса.
        assertThat(s.stepEndsAtElapsed(startElapsed)).isEqualTo(startElapsed + 3_600_000L)
        assertThat(s.stepEndsAtElapsed(startElapsed + 1_234L)).isEqualTo(startElapsed + 3_600_000L)
    }

    /**
     * Секунды на странице округлены вниз, миллисекунды конца — нет. «Сейчас
     * плюс остаток в секундах» промахивается мимо конца на долю секунды, и
     * системный хронометр в шторке досчитывает до нуля позже страницы.
     */
    @Test
    fun `the exact end is not the same as now plus the whole seconds left`() {
        val s = session(minutes = 60)
        val now = startElapsed + 600L
        val roundedGuess = now + s.remainingSeconds(now) * 1000L
        assertThat(s.stepEndsAtElapsed(now)).isNotEqualTo(roundedGuess)
        assertThat(s.stepEndsAtElapsed(now)).isEqualTo(startElapsed + 3_600_000L)
    }

    /** На паузе конец шага уезжает вместе с ней: столько же осталось от «сейчас». */
    @Test
    fun `a paused step ends as far away as it was paused with left`() {
        val pausedAt = startElapsed + 600_000L
        val s = session(minutes = 60, isPaused = true, pausedAtElapsed = pausedAt)
        val now = pausedAt + 5 * 60_000L
        assertThat(s.stepEndsAtElapsed(now)).isEqualTo(now + 3_000_000L)
    }

    /** Шаг, который уже просрочен, кончился в прошлом — и врать про будущее нечем. */
    @Test
    fun `an overrun step keeps its end in the past`() {
        val s = session(minutes = 60)
        val now = startElapsed + 4_000_000L
        assertThat(s.stepEndsAtElapsed(now)).isLessThan(now)
    }

    // ————— сон до следующего тика —————

    @Test
    fun `a tick far from the boundary sleeps a whole second`() {
        assertThat(BakingTick.sleepMillis(nowElapsed = 0, stepEndsAtElapsed = 3_600_000L))
            .isEqualTo(BakingTick.INTERVAL_MS)
    }

    @Test
    fun `the last tick lands on the boundary instead of stepping over it`() {
        assertThat(BakingTick.sleepMillis(nowElapsed = 0, stepEndsAtElapsed = 400L)).isEqualTo(400L)
        assertThat(BakingTick.sleepMillis(nowElapsed = 0, stepEndsAtElapsed = 1L)).isEqualTo(1L)
        assertThat(BakingTick.sleepMillis(nowElapsed = 0, stepEndsAtElapsed = 1_000L))
            .isEqualTo(1_000L)
        assertThat(BakingTick.sleepMillis(nowElapsed = 0, stepEndsAtElapsed = 1_001L))
            .isEqualTo(BakingTick.INTERVAL_MS)
    }

    /**
     * После границы спать снова по секунде: шаг досчитан, но страница живёт
     * дальше — человек ещё не нажал «Дальше». Ноль сна здесь закрутил бы цикл
     * впустую на весь тот час, что тесто ждёт хозяйку.
     */
    @Test
    fun `past the boundary the tick goes back to a whole second`() {
        assertThat(BakingTick.sleepMillis(nowElapsed = 10_000L, stepEndsAtElapsed = 10_000L))
            .isEqualTo(BakingTick.INTERVAL_MS)
        assertThat(BakingTick.sleepMillis(nowElapsed = 10_000L, stepEndsAtElapsed = 9_000L))
            .isEqualTo(BakingTick.INTERVAL_MS)
    }

    /** Ни при каком входе сон не бывает нулевым или отрицательным. */
    @Test
    fun `the tick never sleeps zero or backwards`() {
        val edges = listOf(-5_000L, -1L, 0L, 1L, 999L, 1_000L, 1_001L, 60_000L)
        edges.forEach { end ->
            val sleep = BakingTick.sleepMillis(nowElapsed = 0, stepEndsAtElapsed = end)
            assertThat(sleep).isGreaterThan(0L)
            assertThat(sleep).isAtMost(BakingTick.INTERVAL_MS)
        }
    }

    /**
     * Дойти до нуля тиками: секунда, секунда, остаток. Последний тик обязан
     * прийти ровно на конец шага, а не после него.
     */
    @Test
    fun `ticking from a ragged start reaches the zero second exactly`() {
        val s = session(minutes = 1)
        val end = s.stepEndsAtElapsed(startElapsed)
        var now = startElapsed + 300L
        var guard = 0
        while (now < end && guard++ < 100) {
            now += BakingTick.sleepMillis(now, end)
        }
        assertThat(now).isEqualTo(end)
        assertThat(s.remainingSeconds(now)).isEqualTo(0L)
    }
}
