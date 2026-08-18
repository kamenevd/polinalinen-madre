package com.polinalinen.madre.notifications

/**
 * Cycle 11: когда напомнить о кормлении. Чистая функция — считает только срок,
 * ничего не ставит в очередь (это делает FeedingReminderScheduler) и ничего не
 * знает ни про WorkManager, ни про Room.
 *
 * Точность здесь намеренно приблизительная: WorkManager разбудит книгу «около»
 * назначенного времени, а не секунда в секунду. Для закваски, которую кормят
 * раз в сутки, это ровно та точность, что нужна — exact alarm ради такого не
 * просят (баг v3 #5 как раз про поспешную возню с будильниками и батареей).
 */
sealed interface FeedingReminderPlan {
    /** Напоминаний быть не должно — очередь надо очистить. */
    data object Cancel : FeedingReminderPlan

    /** Разбудить через [delayMillis]; 0 — срок уже прошёл, показать сразу. */
    data class Schedule(val delayMillis: Long) : FeedingReminderPlan
}

object FeedingReminderPlanner {

    /**
     * Одно имя на закваску: перепланирование (новое кормление, другой интервал,
     * выключенные напоминания) заменяет прежнюю работу, а не добавляет вторую.
     */
    fun uniqueWorkName(configId: Long): String = "feeding-reminder-$configId"

    fun plan(
        remindersEnabled: Boolean,
        intervalHours: Int,
        lastFeedingMillis: Long?,
        nowMillis: Long,
    ): FeedingReminderPlan {
        if (!remindersEnabled || intervalHours <= 0) return FeedingReminderPlan.Cancel
        // Ни разу не кормили — напоминать не от чего: дневник ещё пуст.
        val lastFeeding = lastFeedingMillis ?: return FeedingReminderPlan.Cancel
        return when (val schedule = FeedingSchedule.calculate(lastFeeding, intervalHours, nowMillis)) {
            is FeedingSchedule.State.NotDue -> FeedingReminderPlan.Schedule(schedule.dueAtMillis - nowMillis)
            is FeedingSchedule.State.Due -> FeedingReminderPlan.Schedule(0L)
            else -> FeedingReminderPlan.Cancel
        }
    }
}
