package com.polinalinen.madre.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RuShortDate {
    private val locale = Locale("ru")

    fun visible(millis: Long, currentYear: Int): String {
        val year = SimpleDateFormat("yyyy", locale).format(Date(millis)).toInt()
        val pattern = if (year == currentYear) "d MMM" else "d MMM yyyy"
        return SimpleDateFormat(pattern, locale).format(Date(millis))
    }

    fun accessible(millis: Long): String =
        SimpleDateFormat("d MMMM yyyy, HH:mm", locale).format(Date(millis))

    /**
     * Cycle 26: точный местный срок — «19 августа, 08:30». Пояс передаётся
     * явно, чтобы правило проверялось тестом, а не наблюдением за телефоном:
     * по умолчанию это тот пояс, в котором телефон живёт прямо сейчас.
     */
    fun dateTime(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("d MMMM, HH:mm", locale).apply { timeZone = zone }.format(Date(millis))
}
