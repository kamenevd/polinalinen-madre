package com.polinalinen.madre.sourdough

/**
 * Cycle 26: что считается настоящим кормлением.
 *
 * Раньше форма читала пустые поля как `toIntOrNull() ?: 0` и заводила запись
 * «0 г муки, 0 г воды» — запись уходила в Room, сдвигала дату последнего
 * кормления и переставляла напоминание, а убрать её из книги было нечем.
 *
 * Теперь ноль не набирается вовсе: каждая из трёх масс живёт в своих пределах,
 * у каждой есть значение по умолчанию и шаг в 5 г. Быстрый путь — ползунок по
 * шкале, точный — набрать вес с кухонных весов руками (он редко кратен пяти).
 * Гидратация здесь не спрашивается: её считает [HydrationMath].
 */
object FeedingInput {

    /** Шаг ползунка и кнопок «+5 г» / «−5 г» — один на все три массы. */
    const val STEP_GRAMS = 5

    data class Range(val min: Int, val max: Int, val default: Int) {

        fun clamp(grams: Int): Int = grams.coerceIn(min, max)

        fun up(grams: Int): Int = clamp(grams + STEP_GRAMS)

        fun down(grams: Int): Int = clamp(grams - STEP_GRAMS)

        /**
         * Точный вес с весов. Не кратен пяти — и это законно: 47 г закваски в
         * банке бывают, а ползунок такого не наберёт.
         */
        fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in min..max }

        /**
         * Промежуточных делений на шкале — столько, чтобы ползунок ходил ровно
         * по пятёркам: краёв два, между ними (max−min)/шаг − 1 засечка.
         */
        val sliderSteps: Int get() = (max - min) / STEP_GRAMS - 1
    }

    /** Сколько закваски оставили в банке. */
    val STARTER = Range(min = 5, max = 250, default = 50)

    /** Сколько муки добавлено. */
    val FLOUR = Range(min = 10, max = 500, default = 100)

    /** Сколько воды добавлено. */
    val WATER = Range(min = 5, max = 300, default = 50)
}

/** Подписи трёх масс на форме кормления — инфинитив, без рода читателя. */
object FeedingMassCopy {
    const val STARTER = "Оставить закваски"
    const val FLOUR = "Добавить муки"
    const val WATER = "Добавить воды"
}
