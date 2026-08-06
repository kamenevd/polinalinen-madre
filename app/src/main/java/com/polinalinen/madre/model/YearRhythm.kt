package com.polinalinen.madre.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Один день года: дата и сколько записей в этот день — выпечки и кормления вместе. */
data class YearDay(val date: LocalDate, val events: Int)

/**
 * Год целиком: [weeks] — колонки слева направо, в каждой ровно семь слотов,
 * понедельник сверху. null — день, до которого книга ещё не дошла: он есть в
 * календаре, но записи в нём быть не может, и рисовать его нечем.
 */
data class YearGrid(
    val weeks: List<List<YearDay?>>,
    val from: LocalDate,
    val to: LocalDate,
) {
    val totalEvents: Int get() = weeks.sumOf { week -> week.sumOf { it?.events ?: 0 } }
    val activeDays: Int get() = weeks.sumOf { week -> week.count { (it?.events ?: 0) > 0 } }
}

/**
 * Cycle 15: ритм года — 52 недели в строке, семь дней в столбце.
 *
 * Календарь одного месяца (Cycle 14) отвечал на вопрос «когда в этом месяце»,
 * но не отвечал на «часто ли вообще»: первого числа он обнулялся, и год жизни
 * книги в нём было не увидеть. Год показывает привычку — полосы, провалы и то,
 * что после отпуска закваску кормить перестали.
 *
 * Считаются и выпечки, и кормления: книга живёт и тем, и другим, а день, в
 * который закваску покормили, — не тихий день.
 */
object YearRhythm {

    /** Сколько недельных колонок в году. */
    const val WEEKS = 52

    const val WEEK_LENGTH = 7

    /** Сколько ступеней насыщенности у клетки, не считая пустой. */
    const val LEVELS = 3

    /**
     * Окно заканчивается сегодняшним днём и начинается понедельником той недели,
     * что была 51 неделю назад — так последняя колонка всегда текущая неделя, а
     * все колонки полные по семь слотов.
     */
    fun build(eventMillis: List<Long>, today: LocalDate, zone: ZoneId): YearGrid {
        val from = today.with(DayOfWeek.MONDAY).minusWeeks((WEEKS - 1).toLong())

        // Отсекаем чужие даты здесь, а не при отрисовке: записи старше года и
        // записи из будущего (сбитые часы, чужой часовой пояс) не имеют права
        // подмешаться в эту картинку ни одной клеткой.
        val perDay = eventMillis
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .filter { !it.isBefore(from) && !it.isAfter(today) }
            .groupingBy { it }
            .eachCount()

        val weeks = (0 until WEEKS).map { week ->
            val weekStart = from.plusWeeks(week.toLong())
            (0 until WEEK_LENGTH).map { offset ->
                val date = weekStart.plusDays(offset.toLong())
                if (date.isAfter(today)) null else YearDay(date, perDay[date] ?: 0)
            }
        }
        return YearGrid(weeks = weeks, from = from, to = today)
    }

    /**
     * Насыщенность клетки — АБСОЛЮТНАЯ, не относительно самого занятого дня.
     *
     * В месяце относительная шкала была права: там сравнивать не с чем, кроме
     * соседних дней. В году она бы врала — один день, когда пекли пять раз,
     * перекрасил бы все обычные дни в самый бледный тон, и привычка исчезла бы
     * с картинки ради одного всплеска.
     */
    fun intensity(events: Int): Int = if (events <= 0) 0 else events.coerceAtMost(LEVELS)

    /**
     * Подпись месяца над колонкой: месяц называется там, где начинается —
     * в той неделе, куда попало его первое число. Первая колонка обычно
     * начинается с середины месяца и остаётся без подписи: называть месяц,
     * от которого видно четыре дня, незачем.
     */
    fun monthLabels(grid: YearGrid): List<String?> = grid.weeks.map { week ->
        week.filterNotNull()
            .firstOrNull { it.date.dayOfMonth == 1 }
            ?.let { RuDate.monthShort(it.date.monthValue) }
    }
}
