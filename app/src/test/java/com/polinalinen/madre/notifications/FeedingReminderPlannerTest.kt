package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 11, напоминание о кормлении: когда именно WorkManager должен разбудить
 * книгу — чистая функция, без WorkManager и без Room. Срок считается от
 * последнего кормления плюс выбранный интервал; выключенные напоминания и
 * пустой дневник не ставят ничего.
 */
class FeedingReminderPlannerTest {

    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `reminders switched off schedule nothing`() {
        val plan = FeedingReminderPlanner.plan(
            remindersEnabled = false,
            intervalHours = 24,
            lastFeedingMillis = now - hour,
            nowMillis = now,
        )
        assertThat(plan).isEqualTo(FeedingReminderPlan.Cancel)
    }

    @Test
    fun `a starter that was never fed schedules nothing`() {
        val plan = FeedingReminderPlanner.plan(
            remindersEnabled = true,
            intervalHours = 24,
            lastFeedingMillis = null,
            nowMillis = now,
        )
        assertThat(plan).isEqualTo(FeedingReminderPlan.Cancel)
    }

    @Test
    fun `the reminder lands one interval after the last feeding`() {
        val plan = FeedingReminderPlanner.plan(
            remindersEnabled = true,
            intervalHours = 24,
            lastFeedingMillis = now - hour,
            nowMillis = now,
        )
        assertThat(plan).isEqualTo(FeedingReminderPlan.Schedule(23 * hour))
    }

    @Test
    fun `a shorter interval moves the reminder closer`() {
        val plan = FeedingReminderPlanner.plan(
            remindersEnabled = true,
            intervalHours = 12,
            lastFeedingMillis = now - hour,
            nowMillis = now,
        )
        assertThat(plan).isEqualTo(FeedingReminderPlan.Schedule(11 * hour))
    }

    @Test
    fun `an overdue feeding is reminded about at once, not in the past`() {
        val plan = FeedingReminderPlanner.plan(
            remindersEnabled = true,
            intervalHours = 24,
            lastFeedingMillis = now - 30 * hour,
            nowMillis = now,
        )
        assertThat(plan).isEqualTo(FeedingReminderPlan.Schedule(0L))
    }

    @Test
    fun `the unique work name is stable per starter so replanning never duplicates`() {
        assertThat(FeedingReminderPlanner.uniqueWorkName(7L))
            .isEqualTo(FeedingReminderPlanner.uniqueWorkName(7L))
        assertThat(FeedingReminderPlanner.uniqueWorkName(7L)).isEqualTo("feeding-reminder-7")
        assertThat(FeedingReminderPlanner.uniqueWorkName(7L))
            .isNotEqualTo(FeedingReminderPlanner.uniqueWorkName(8L))
    }
}
