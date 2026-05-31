package com.polinalinen.madre.ui

/**
 * Русское склонение числительных.
 *
 * 1 шаг, 2 шага, 5 шагов, 11 шагов, 21 шаг, 22 шага, 25 шагов
 */
fun russianPlural(count: Int, one: String, few: String, many: String): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** «шаг / шага / шагов» */
fun pluralSteps(count: Int): String = russianPlural(count, "шаг", "шага", "шагов")
