package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class FeedingScheduleTest {
    private val hour = FeedingSchedule.HOUR_MILLIS

    @Test fun beforeBoundary_isNotDue() {
        assertThat(FeedingSchedule.calculate(1_000L, 24, 1_000L + 24 * hour - 1))
            .isEqualTo(FeedingSchedule.State.NotDue(1_000L + 24 * hour, 1L))
    }

    @Test fun exactBoundary_isDue() {
        assertThat(FeedingSchedule.calculate(1_000L, 24, 1_000L + 24 * hour))
            .isEqualTo(FeedingSchedule.State.Due(1_000L + 24 * hour, 0L))
    }

    @Test fun overdue_isDueWithPositiveOverdueMillis() {
        assertThat(FeedingSchedule.calculate(1_000L, 24, 1_000L + 24 * hour + 1))
            .isEqualTo(FeedingSchedule.State.Due(1_000L + 24 * hour, 1L))
        assertThat(FeedingSchedule.calculate(1_000L, 24, 1_000L + 24 * hour + 90 * 60_000L + 500L))
            .isEqualTo(FeedingSchedule.State.Due(1_000L + 24 * hour, 90 * 60_000L + 500L))
    }

    @Test fun clockBeforeLastFeeding_isHonestAnomaly() {
        assertThat(FeedingSchedule.calculate(2_000L, 24, 1_999L))
            .isEqualTo(FeedingSchedule.State.ClockBeforeLastFeeding(2_000L))
    }

    @Test fun invalidAndNeverFed_haveSafeStates() {
        assertThat(FeedingSchedule.calculate(1L, 0, 1L)).isEqualTo(FeedingSchedule.State.InvalidInterval)
        assertThat(FeedingSchedule.calculate(1L, -1, 1L)).isEqualTo(FeedingSchedule.State.InvalidInterval)
        assertThat(FeedingSchedule.calculate(null, 24, 1L)).isEqualTo(FeedingSchedule.State.NeverFed)
    }

    @Test fun hugeIntervalDoesNotOverflowAndDueTimestampOverflowIsSafe() {
        assertThat(FeedingSchedule.calculate(1L, Int.MAX_VALUE, 1L))
            .isInstanceOf(FeedingSchedule.State.NotDue::class.java)
        assertThat(FeedingSchedule.calculate(Long.MAX_VALUE - hour, 2, Long.MAX_VALUE - hour))
            .isEqualTo(FeedingSchedule.State.InvalidInterval)
    }

    /** Срок — ровно «время записи + интервал», без округлений и запасов. */
    @Test fun dueAtIsExactlySavedPlusInterval() {
        assertThat(FeedingSchedule.dueAtMillis(1_000L, 24)).isEqualTo(1_000L + 24 * hour)
        assertThat(FeedingSchedule.dueAtMillis(1_000L, 72)).isEqualTo(1_000L + 72 * hour)
        assertThat(FeedingSchedule.dueAtMillis(1_000L, 0)).isNull()
        assertThat(FeedingSchedule.dueAtMillis(Long.MAX_VALUE - hour, 2)).isNull()
    }

    /**
     * Граница принадлежит сроку: ровно в срок — успели. Проверяются все три
     * соседние миллисекунды, потому что именно на них расходятся «вовремя» и
     * «опоздала», а разницу в одну миллисекунду глазами не увидеть.
     */
    @Test fun punctualityBoundaryIsDueInclusive() {
        val due = 1_700_000_000_000L
        assertThat(FeedingSchedule.classify(due - 1, due)).isEqualTo(FeedingSchedule.Punctuality.OnTime)
        assertThat(FeedingSchedule.classify(due, due)).isEqualTo(FeedingSchedule.Punctuality.OnTime)
        assertThat(FeedingSchedule.classify(due + 1, due))
            .isEqualTo(FeedingSchedule.Punctuality.Late(1L))
        assertThat(FeedingSchedule.classify(due + 90 * 60_000L, due))
            .isEqualTo(FeedingSchedule.Punctuality.Late(90 * 60_000L))
    }

    @Test fun spokenDurationIsHumanAndSecondless() {
        assertThat(FeedingSchedule.spokenDuration(0)).isEqualTo("меньше минуты")
        assertThat(FeedingSchedule.spokenDuration(59_000)).isEqualTo("меньше минуты")
        assertThat(FeedingSchedule.spokenDuration(40 * 60_000L)).isEqualTo("40 мин")
        assertThat(FeedingSchedule.spokenDuration(hour)).isEqualTo("1 ч")
        assertThat(FeedingSchedule.spokenDuration(23 * hour + 45 * 60_000L)).isEqualTo("23 ч 45 мин")
        assertThat(FeedingSchedule.spokenDuration(24 * hour)).isEqualTo("1 дн")
        assertThat(FeedingSchedule.spokenDuration(76 * hour)).isEqualTo("3 дн 4 ч")
    }

    /**
     * Точный местный срок словами. Пояс задаётся явно: в Москве и в Лиссабоне
     * один и тот же момент называется разным часом, и книга обязана называть
     * тот, в котором стоит телефон.
     */
    @Test fun nextFeedingLabelIsExactLocalDateTime() {
        val savedAt = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow")).apply {
            clear()
            set(2026, Calendar.AUGUST, 18, 8, 30, 0)
        }.timeInMillis
        val due = FeedingSchedule.dueAtMillis(savedAt, 24)!!

        assertThat(FeedingSchedule.nextFeedingLabel(due, TimeZone.getTimeZone("Europe/Moscow")))
            .isEqualTo("Следующее кормление: 19 августа, 08:30")
        // Тот же момент, другой пояс телефона — другой названный час.
        assertThat(FeedingSchedule.nextFeedingLabel(due, TimeZone.getTimeZone("Europe/Lisbon")))
            .isEqualTo("Следующее кормление: 19 августа, 06:30")
    }

    /** Интервал переносит запись через полночь — дата обязана перевернуться. */
    @Test fun nextFeedingLabelRollsOverToTheNextDate() {
        val savedAt = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow")).apply {
            clear()
            set(2026, Calendar.AUGUST, 31, 23, 0, 0)
        }.timeInMillis
        val due = FeedingSchedule.dueAtMillis(savedAt, 2)!!

        assertThat(FeedingSchedule.nextFeedingLabel(due, TimeZone.getTimeZone("Europe/Moscow")))
            .isEqualTo("Следующее кормление: 1 сентября, 01:00")
    }
}
