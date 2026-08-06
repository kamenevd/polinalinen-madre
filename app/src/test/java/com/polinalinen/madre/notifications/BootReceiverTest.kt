package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import org.junit.Test

/**
 * Cycle 15: какие выпечки BootReceiver поднимает после перезагрузки телефона, а
 * какие оставляет лежать.
 *
 * Решение по каждой строке active_bakes — чистая функция ([BootRestorePlanner]),
 * поэтому проверяется без Android, WorkManager и Room: ребут здесь — это просто
 * другое «сейчас» по стенным часам.
 */
class BootReceiverTest {

    private val startedAt = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun bake(
        sessionId: Long = 1L,
        stepDurationMinutes: Int = 60,
        isWaitStep: Boolean = true,
        startedAtWallClock: Long = startedAt,
        pausedAtWallClock: Long? = null,
    ) = ActiveBakeEntity(
        sessionId = sessionId,
        recipeId = "bread",
        recipeName = "Бородинский",
        stepTitle = "Расстойка",
        stepIndex = 0,
        stepDurationMinutes = stepDurationMinutes,
        isWaitStep = isWaitStep,
        startedAtWallClock = startedAtWallClock,
        pausedAtWallClock = pausedAtWallClock,
    )

    @Test
    fun `a step still running comes back with the time it has left`() {
        // Ребут случился через двадцать минут часовой расстойки.
        val decision = BootRestorePlanner.decide(bake(), startedAt + 20 * 60_000L)

        assertThat(decision).isEqualTo(BootRestorePlanner.Decision.Notify(1L, 40 * 60_000L))
    }

    @Test
    fun `a step that ran out while the phone was off is due right away`() {
        val decision = BootRestorePlanner.decide(bake(), startedAt + 2 * hour)

        assertThat(decision).isEqualTo(BootRestorePlanner.Decision.Notify(1L, 0L))
    }

    /** Полсуток после конца шага — граница между «ждёт с утра» и «бросили». */
    @Test
    fun `a bake forgotten for longer than the stale window is dropped, not resurrected`() {
        val justInside = startedAt + hour + BootRestorePlanner.STALE_AFTER_HOURS * hour
        assertThat(BootRestorePlanner.decide(bake(), justInside))
            .isEqualTo(BootRestorePlanner.Decision.Notify(1L, 0L))

        assertThat(BootRestorePlanner.decide(bake(), justInside + 1))
            .isEqualTo(BootRestorePlanner.Decision.Forget(1L))
    }

    @Test
    fun `a paused bake keeps its row but nobody is called`() {
        val pausedAt = startedAt + 10 * 60_000L
        val decision = BootRestorePlanner.decide(
            bake(pausedAtWallClock = pausedAt),
            pausedAt + 3 * hour,
        )

        assertThat(decision).isEqualTo(BootRestorePlanner.Decision.Keep(1L))
    }

    @Test
    fun `a pause older than the stale window is a forgotten bake, not a pause`() {
        val pausedAt = startedAt + 10 * 60_000L
        val decision = BootRestorePlanner.decide(
            bake(pausedAtWallClock = pausedAt),
            pausedAt + BootRestorePlanner.STALE_AFTER_HOURS * hour + 1,
        )

        assertThat(decision).isEqualTo(BootRestorePlanner.Decision.Forget(1L))
    }

    /**
     * У шага-действия «время» — оценка автора рецепта, а не таймер. Тот же
     * зарок, что и у BakingNotificationPlanner.isStepDone: звонить по нему
     * незачем ни с живым таймером, ни после ребута.
     */
    @Test
    fun `an action step is kept but never rings`() {
        val decision = BootRestorePlanner.decide(
            bake(isWaitStep = false, stepDurationMinutes = 10),
            startedAt + hour,
        )

        assertThat(decision).isEqualTo(BootRestorePlanner.Decision.Keep(1L))
    }

    /** Шаг нулевой длины истёк в тот же миг — но всё-таки истёк, а не «висит». */
    @Test
    fun `a zero minute wait step is already due`() {
        val decision = BootRestorePlanner.decide(bake(stepDurationMinutes = 0), startedAt)

        assertThat(decision).isEqualTo(BootRestorePlanner.Decision.Notify(1L, 0L))
    }

    @Test
    fun `each bake is decided on its own, and every row gets an answer`() {
        val now = startedAt + 2 * hour
        val bakes = listOf(
            bake(sessionId = 1L, stepDurationMinutes = 180),                    // ещё идёт
            bake(sessionId = 2L, stepDurationMinutes = 60),                     // подошла, пока телефон был выключен
            bake(sessionId = 3L, pausedAtWallClock = startedAt + 10 * 60_000L), // на паузе
            bake(sessionId = 4L, startedAtWallClock = startedAt - 30 * hour),   // брошена сутки с лишним назад
        )

        val plan = BootRestorePlanner.plan(bakes, now)

        assertThat(plan.map { it.second }).containsExactly(
            BootRestorePlanner.Decision.Notify(1L, hour),
            BootRestorePlanner.Decision.Notify(2L, 0L),
            BootRestorePlanner.Decision.Keep(3L),
            BootRestorePlanner.Decision.Forget(4L),
        ).inOrder()
        // Строка и решение не разъезжаются: воркеру нужны имя рецепта и
        // название шага именно той выпечки, чей срок он ставит.
        assertThat(plan.map { it.first.sessionId }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `an empty book after a reboot is not an error`() {
        assertThat(BootRestorePlanner.plan(emptyList(), startedAt)).isEmpty()
    }

    /** Имя работы одно на выпечку — второй срок не встаёт рядом с первым. */
    @Test
    fun `each bake reschedules under its own work name`() {
        assertThat(BootRestorePlanner.uniqueWorkName(7L)).isEqualTo("bake-step-end-7")
        assertThat(BootRestorePlanner.uniqueWorkName(7L)).isNotEqualTo(BootRestorePlanner.uniqueWorkName(71L))
    }
}
