package com.polinalinen.madre.model

import java.time.LocalDate
import java.time.YearMonth

/**
 * Cycle 14: русские даты — одним списком месяцев на всю книгу.
 *
 * До этого цикла список месяцев лежал внутри BookStatsScreen приватной
 * константой, и календарю ритма пришлось бы завести второй такой же. Два
 * списка месяцев в одном приложении расходятся ровно тогда, когда их правят.
 *
 * Падежей два, и оба нужны: календарь называет месяц («август 2026»), подпись
 * фотокарточки — день («3 августа»).
 */
object RuDate {

    /** «3 августа» — родительный падеж, как в подписи под снимком. */
    fun dayAndMonth(date: LocalDate): String = "${date.dayOfMonth} ${GENITIVE[date.monthValue - 1]}"

    /** «август 2026» — именительный падеж, как в шапке календаря. */
    fun monthAndYear(month: YearMonth): String = "${NOMINATIVE[month.monthValue - 1]} ${month.year}"

    /** «авг» — подпись над колонкой года, где на целое слово места нет. */
    fun monthShort(monthValue: Int): String = SHORT[monthValue - 1]

    private val NOMINATIVE = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )

    private val SHORT = listOf(
        "янв", "фев", "мар", "апр", "май", "июн",
        "июл", "авг", "сен", "окт", "ноя", "дек",
    )

    private val GENITIVE = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
}
