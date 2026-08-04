package com.polinalinen.madre.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Один день календаря: дата и сколько раз в этот день пекли. */
data class MonthDay(val date: LocalDate, val bakes: Int)

/**
 * Месяц целиком — ровно столько дней, сколько в нём есть, и ни одного чужого.
 *
 * [leadingBlanks] — пустые клетки перед первым числом, чтобы первое число
 * встало на свой день недели. Неделя начинается с понедельника.
 */
data class MonthGrid(
    val month: YearMonth,
    val leadingBlanks: Int,
    val days: List<MonthDay>,
) {
    val maxBakes: Int get() = days.maxOfOrNull { it.bakes } ?: 0
    val totalBakes: Int get() = days.sumOf { it.bakes }
}

/**
 * Cycle 14: ритм выпечки — календарь текущего месяца.
 *
 * До этого цикла на Полке стояли двенадцать безымянных квадратов «по неделям».
 * По ним нельзя было сказать ни какого числа пекли, ни даже в каком месяце:
 * записи июля и августа сливались в один столбик, а тихие дни не показывались
 * вовсе. Календарь показывает месяц как месяц — с пустыми днями, потому что
 * дни, когда не пекли, — тоже часть ритма.
 */
object MonthRhythm {

    /** Неделя начинается с понедельника — как в календаре на стене. */
    val WEEKDAY_LETTERS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    /** Сколько ступеней насыщенности у клетки, не считая пустой. */
    const val LEVELS = 3

    fun build(bakeMillis: List<Long>, month: YearMonth, zone: ZoneId): MonthGrid {
        // Чужой месяц отсекается сразу, а не при отрисовке: соседние месяцы не
        // имеют права подмешаться в этот календарь ни одной выпечкой.
        val perDay = bakeMillis
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .filter { YearMonth.from(it) == month }
            .groupingBy { it.dayOfMonth }
            .eachCount()

        val days = (1..month.lengthOfMonth()).map { day ->
            MonthDay(date = month.atDay(day), bakes = perDay[day] ?: 0)
        }
        return MonthGrid(
            month = month,
            // DayOfWeek.value: понедельник — 1, значит смещение на единицу меньше.
            leadingBlanks = month.atDay(1).dayOfWeek.value - 1,
            days = days,
        )
    }

    /**
     * Насыщенность клетки: 0 — не пекли, дальше 1..[LEVELS] относительно самого
     * занятого дня месяца. Единственная выпечка за месяц — самая тёмная клетка,
     * а не бледная треть: относительно этого месяца она и есть максимум.
     */
    fun intensity(bakes: Int, maxBakes: Int): Int {
        if (bakes <= 0 || maxBakes <= 0) return 0
        val share = bakes.toDouble() / maxBakes
        return Math.ceil(share * LEVELS).toInt().coerceIn(1, LEVELS)
    }

    fun title(month: YearMonth): String = RuDate.monthAndYear(month)
}
