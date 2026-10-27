package com.polinalinen.madre.model

/** Номер текущей выпечки — не id сессии и не счётчик записей в книге. */
object BakeNumbers {
    const val COOLDOWN_MILLIS = 30 * 60 * 1000L
    const val LIMIT_MESSAGE = "Девять выпечек — максимум. Впишите или выбросьте одну"
    fun allocate(occupied: Collection<Int>, freedAt: Map<Int, Long>, now: Long): Int? {
        val free = (1..9).filter { it !in occupied }
        return free.firstOrNull { number ->
            freedAt[number]?.let { it != Long.MIN_VALUE && (now < it || now - it < COOLDOWN_MILLIS) } != true
        } ?: free.firstOrNull()
    }
    fun word(number: Int): String = listOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять").getOrElse(number) { "не назначен" }
    fun ordinal(number: Int): String = listOf("", "первая", "вторая", "третья", "четвёртая", "пятая", "шестая", "седьмая", "восьмая", "девятая").getOrElse(number) { "без номера" }
    fun genitive(number: Int): String = listOf("", "первой", "второй", "третьей", "четвёртой", "пятой", "шестой", "седьмой", "восьмой", "девятой").getOrElse(number) { "этой выпечкой" }
    fun label(number: Int): String = if (number in 1..9) "№$number" else "Без номера"
}
