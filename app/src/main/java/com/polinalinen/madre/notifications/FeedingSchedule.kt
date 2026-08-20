package com.polinalinen.madre.notifications

import com.polinalinen.madre.model.RuShortDate
import java.util.TimeZone

/** One wall-clock schedule truth shared by Home, Diary and WorkManager planning. */
object FeedingSchedule {
    const val HOUR_MILLIS = 3_600_000L

    sealed interface State {
        data object NeverFed : State
        data object InvalidInterval : State
        data class ClockBeforeLastFeeding(val lastFeedingMillis: Long) : State
        data class NotDue(val dueAtMillis: Long, val remainingMillis: Long) : State
        data class Due(val dueAtMillis: Long, val overdueMillis: Long) : State
    }

    /**
     * Cycle 26: срок — это ровно «время записи + интервал из настроек», без
     * «примерно». null — интервал не задан или срок не помещается в Long.
     */
    fun dueAtMillis(lastFeedingMillis: Long, intervalHours: Int): Long? {
        if (intervalHours <= 0) return null
        return try {
            Math.addExact(lastFeedingMillis, Math.multiplyExact(intervalHours.toLong(), HOUR_MILLIS))
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun calculate(lastFeedingMillis: Long?, intervalHours: Int, nowMillis: Long): State {
        if (lastFeedingMillis == null) return State.NeverFed
        if (nowMillis < lastFeedingMillis) return State.ClockBeforeLastFeeding(lastFeedingMillis)
        val dueAt = dueAtMillis(lastFeedingMillis, intervalHours) ?: return State.InvalidInterval
        return if (nowMillis >= dueAt) State.Due(dueAt, nowMillis - dueAt)
        else State.NotDue(dueAt, dueAt - nowMillis)
    }

    /**
     * Успели или опоздали. Ровно в срок — успели: граница принадлежит сроку.
     *
     * Здесь не говорится ни слова о том, приходило ли напоминание: WorkManager
     * волен задержать работу на часы, и приписывать книге сработавшее
     * уведомление, которого человек не видел, нельзя.
     */
    sealed interface Punctuality {
        data object OnTime : Punctuality
        data class Late(val byMillis: Long) : Punctuality
    }

    fun classify(savedAtMillis: Long, dueAtMillis: Long): Punctuality =
        if (savedAtMillis <= dueAtMillis) Punctuality.OnTime
        else Punctuality.Late(savedAtMillis - dueAtMillis)

    /** Одна строка на всю книгу: и первая полоса, и дневник зовут её. */
    fun nextFeedingLabel(dueAtMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "Следующее кормление: ${RuShortDate.dateTime(dueAtMillis, zone)}"

    /**
     * Unified next-feeding status copy for Home and StarterDiary.
     *
     * - `NotDue` keeps exact local datetime from `dateTime(dueAtMillis)`.
     * - `Due` (exact boundary or overdue) uses the shared overdue wording.
     * - `InvalidInterval`, `ClockBeforeLastFeeding`, and `NeverFed` keep their
     *   dedicated statuses, consistent across all screens.
     */
    fun nextFeedingStatus(state: State, zone: TimeZone = TimeZone.getDefault()): String = when (state) {
        State.NeverFed -> "Кормлений пока нет — запишите первое, когда покормите"
        State.InvalidInterval -> "Интервал кормления не задан корректно — проверьте настройки"
        is State.ClockBeforeLastFeeding -> "Время телефона раньше последнего кормления — проверьте часы"
        is State.NotDue -> nextFeedingLabel(state.dueAtMillis, zone)
        is State.Due -> "Кормление уже по вашему расписанию"
    }

    /**
     * Прожитое время словами: «3 дн 4 ч», «23 ч 45 мин», «40 мин», «меньше
     * минуты». Секунды не показываются — это книга, а не секундомер.
     */
    fun spokenDuration(millis: Long): String {
        val total = if (millis < 0) 0L else millis
        val minutes = total / 60_000L
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val restMinutes = minutes % 60
        return when {
            days > 0 && hours > 0 -> "$days дн $hours ч"
            days > 0 -> "$days дн"
            hours > 0 && restMinutes > 0 -> "$hours ч $restMinutes мин"
            hours > 0 -> "$hours ч"
            minutes > 0 -> "$minutes мин"
            else -> "меньше минуты"
        }
    }
}
