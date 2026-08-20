package com.polinalinen.madre.sourdough

import com.polinalinen.madre.model.RuShortDate
import com.polinalinen.madre.notifications.FeedingSchedule
import java.util.TimeZone

/**
 * Факты одного кормления — ровно то, из чего собирается «Комментарий
 * закваски». Ничего сверх записанного здесь нет: ни фазы, ни предсказания
 * пика, ни «Мадре проголодалась».
 */
data class FeedingFacts(
    val savedAtMillis: Long,
    val intervalHours: Int,
    val previousFeedingMillis: Long?,
    val previousFlourGrams: Int?,
    val previousWaterGrams: Int?,
    val priorHydrationPercent: Int,
    /** true — прошлой сохранённой гидратации нет и взята стартовая из буклета. */
    val priorHydrationIsInitial: Boolean,
    val retainedStarterGrams: Int,
    val addedFlourGrams: Int,
    val addedWaterGrams: Int,
    val finalHydrationPercent: Int,
    /** Готовая строка о погоде или null — тогда о погоде не говорится вовсе. */
    val weather: String?,
)

/**
 * Cycle 26: «Комментарий закваски» больше не набирается руками и не выбирается
 * из штампов — он собирается из фактов в момент записи и остаётся при ней
 * навсегда. Это снимок, а не пересчитываемая строка: через месяц интервал в
 * настройках будет другим, а комментарий обязан рассказывать про тот день.
 *
 * Заметка человека (`notes`) остаётся отдельным полем и сюда не подмешивается.
 */
object FeedingComment {

    fun compose(facts: FeedingFacts, zone: TimeZone = TimeZone.getDefault()): String {
        val lines = mutableListOf<String>()
        lines += "Записано ${RuShortDate.dateTime(facts.savedAtMillis, zone)}."
        lines += "Расписание: каждые ${facts.intervalHours} ч."

        val previous = facts.previousFeedingMillis
        if (previous == null) {
            lines += "Прошлого кормления в дневнике нет — это первая запись."
        } else {
            val elapsed = runCatching { Math.subtractExact(facts.savedAtMillis, previous) }.getOrNull()
            if (elapsed == null || elapsed < 0) {
                lines += "Интервал между записями нельзя проверить из-за изменения времени на устройстве."
            } else {
                lines += "С прошлого кормления прошло ${FeedingSchedule.spokenDuration(elapsed)}."
                val due = FeedingSchedule.dueAtMillis(previous, facts.intervalHours)
                if (due == null) {
                    lines += "Срок посчитать не удалось — интервал кормления задан неверно."
                } else {
                    val dueLabel = RuShortDate.dateTime(due, zone)
                    lines += when (val punctuality = FeedingSchedule.classify(facts.savedAtMillis, due)) {
                        FeedingSchedule.Punctuality.OnTime ->
                            "Срок был $dueLabel — записано не позже срока."
                        is FeedingSchedule.Punctuality.Late ->
                            "Срок был $dueLabel — записано позже срока на " +
                                "${FeedingSchedule.spokenDuration(punctuality.byMillis)}."
                    }
                }
            }
            if (facts.previousFlourGrams != null && facts.previousWaterGrams != null) {
                lines += "В прошлый раз дали ${facts.previousFlourGrams} г муки и " +
                    "${facts.previousWaterGrams} г воды."
            }
        }

        lines += if (facts.priorHydrationIsInitial) {
            "Гидратация до кормления: ${facts.priorHydrationPercent}% " +
                "(стартовая гидратация ${HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT}%)."
        } else {
            "Гидратация до кормления: ${facts.priorHydrationPercent}%."
        }
        lines += "Оставили ${facts.retainedStarterGrams} г закваски, дали " +
            "${facts.addedFlourGrams} г муки и ${facts.addedWaterGrams} г воды."
        lines += "Гидратация после кормления: ${facts.finalHydrationPercent}%."

        // Погоды нет — о ней нет и слова. Пустая строка «погода неизвестна»
        // была бы такой же выдумкой, как выдуманный градус.
        facts.weather?.let { lines += "Погода за окном: $it." }

        return lines.joinToString(" ")
    }
}
