package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 15: отсчёт шага идёт по монотонным часам (SystemClock.elapsedRealtime),
 * а не по стенным.
 *
 * Причина конкретная: 25 октября в 03:00 стрелки переводят на час назад, и
 * таймер, считавший от System.currentTimeMillis(), в этот миг дарит расстойке
 * лишний час — а весной наоборот отнимает. Перелёт с телефоном в кармане делает
 * то же самое. Монотонные часы этих скачков не знают: они считают время с
 * момента загрузки и никуда не прыгают.
 *
 * Здесь «сейчас» — просто числа: сессия часов не читает, поэтому проверить это
 * можно без Android и без ожидания в реальном времени.
 */
class TimerElapsedRealtimeTest {

    private val startElapsed = 5_000_000L        // 1ч 23м с момента загрузки телефона
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

    @Test
    fun `remaining time is measured off the monotonic clock`() {
        val s = session(minutes = 60)
        assertThat(s.remainingSeconds(startElapsed)).isEqualTo(3600)
        assertThat(s.remainingSeconds(startElapsed + 600_000L)).isEqualTo(3000)
        assertThat(s.remainingSeconds(startElapsed + 3_600_000L)).isEqualTo(0)
    }

    /**
     * Стенные часы прыгнули на час назад (переход на зимнее время), монотонные
     * не двигались: остаток обязан остаться прежним.
     */
    @Test
    fun `winding the wall clock back an hour does not give the step an extra hour`() {
        val s = session(minutes = 60)
        val tenMinutesIn = startElapsed + 600_000L

        val before = s.remainingSeconds(tenMinutesIn)
        // Единственное стенное поле сессии уехало вместе с системными часами —
        // на отсчёт это не влияет никак, оно в нём не участвует.
        val afterDstShift = s.copy(startedAtWallClock = startWallClock - 3_600_000L)

        assertThat(afterDstShift.remainingSeconds(tenMinutesIn)).isEqualTo(before)
        assertThat(afterDstShift.remainingSeconds(tenMinutesIn)).isEqualTo(3000)
    }

    /** И вперёд тоже: весенний перевод не должен обрывать шаг досрочно. */
    @Test
    fun `winding the wall clock forward an hour does not cut the step short`() {
        val s = session(minutes = 60)
        val tenMinutesIn = startElapsed + 600_000L
        val afterDstShift = s.copy(startedAtWallClock = startWallClock + 3_600_000L)

        assertThat(afterDstShift.remainingSeconds(tenMinutesIn)).isEqualTo(3000)
    }

    @Test
    fun `a paused step freezes its remainder no matter how far the monotonic clock runs`() {
        val pausedAt = startElapsed + 600_000L
        val paused = session(minutes = 60).togglePause(pausedAt)

        assertThat(paused.remainingSeconds(pausedAt)).isEqualTo(3000)
        assertThat(paused.remainingSeconds(pausedAt + 7_200_000L)).isEqualTo(3000)
    }

    /**
     * Ребут обнулил монотонные часы, и старое [BakingSession.startedAtElapsed]
     * стало числом из прошлой жизни телефона — больше текущего elapsedRealtime.
     * Без пересчёта шаг выглядел бы только что начатым.
     */
    @Test
    fun `after a reboot the step is rebased off the wall clock it survived on`() {
        val s = session(minutes = 60)
        // Прошло сорок минут шага, из них телефон был выключен; после загрузки
        // монотонные часы показывают всего 30 секунд.
        val afterBootElapsed = 30_000L
        val nowWallClock = startWallClock + 2_400_000L

        val restored = s.rebasedTo(afterBootElapsed, nowWallClock)

        assertThat(restored.remainingSeconds(afterBootElapsed)).isEqualTo(1200)
        // Стенная отметка начала шага — та же: по ней и восстанавливались.
        assertThat(restored.startedAtWallClock).isEqualTo(startWallClock)
    }

    @Test
    fun `a step that ran out while the phone was off comes back already finished`() {
        val s = session(minutes = 60)
        val afterBootElapsed = 30_000L
        val restored = s.rebasedTo(afterBootElapsed, startWallClock + 7_200_000L)

        assertThat(restored.remainingSeconds(afterBootElapsed)).isEqualTo(0)
    }

    @Test
    fun `a paused step keeps its frozen remainder across a reboot`() {
        val pausedAt = startElapsed + 600_000L
        val paused = session(minutes = 60).togglePause(pausedAt)
        val afterBootElapsed = 30_000L

        val restored = paused.rebasedTo(afterBootElapsed, startWallClock + 86_400_000L)

        assertThat(restored.isPaused).isTrue()
        assertThat(restored.remainingSeconds(afterBootElapsed)).isEqualTo(3000)
    }

    /**
     * Пауза сдвигает обе отметки начала шага на одну и ту же величину. Если бы
     * сдвигалась только монотонная, [BakingSession.rebasedTo] после ребута
     * вернул бы простоявшее время обратно в счёт.
     */
    @Test
    fun `resuming keeps both clocks pointing at the same moment`() {
        val started = session(minutes = 60)
        val resumed = started.togglePause(startElapsed + 600_000L)
            .togglePause(startElapsed + 900_000L) // пауза длиной пять минут

        assertThat(resumed.startedAtElapsed - started.startedAtElapsed).isEqualTo(300_000L)
        assertThat(resumed.startedAtWallClock - started.startedAtWallClock).isEqualTo(300_000L)

        val afterBootElapsed = 30_000L
        val restored = resumed.rebasedTo(afterBootElapsed, resumed.startedAtWallClock + 600_000L)
        assertThat(restored.remainingSeconds(afterBootElapsed)).isEqualTo(3000)
    }

    /** Отсчёт не уходит в минус и не оживает от часов, забежавших назад. */
    @Test
    fun `a monotonic clock reading before the start does not produce negative elapsed time`() {
        val s = session(minutes = 60)
        assertThat(s.elapsedSeconds(startElapsed - 60_000L)).isEqualTo(0)
        assertThat(s.remainingSeconds(startElapsed - 60_000L)).isEqualTo(3600)
    }
}
