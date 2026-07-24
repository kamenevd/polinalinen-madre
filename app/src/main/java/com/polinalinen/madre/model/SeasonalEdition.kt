package com.polinalinen.madre.model

/**
 * DESIGN-V4.md Cycle 3, фича «Сезонная глава» (SeasonalEdition). Оглавление и
 * первая полоса реагируют на календарь — чистая функция от месяца, никаких
 * данных не требует (вычисляется из LocalDate.now()/Calendar на месте вызова).
 */
enum class Season { WINTER, SPRING, SUMMER, AUTUMN }

object SeasonalEdition {

    /** [month] — 1..12 (как java.util.Calendar.MONTH + 1 или LocalDate.monthValue). */
    fun seasonForMonth(month: Int): Season {
        require(month in 1..12) { "month must be 1..12, was $month" }
        return when (month) {
            12, 1, 2 -> Season.WINTER
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            else -> Season.AUTUMN // 9, 10, 11
        }
    }

    fun labelFor(season: Season): String = when (season) {
        Season.WINTER -> "Зимний выпуск"
        Season.SPRING -> "Весенняя глава"
        Season.SUMMER -> "Летний выпуск"
        Season.AUTUMN -> "Осенняя глава"
    }

    // Один рецепт на сезон — простой static mapping (не из recipes.json), по
    // духу сезона: синнабоны к зиме, блины к масленице/весне, фокачча к
    // летним пикникам, «Семейный» хлеб к осеннему урожаю.
    private val SEASON_RECIPE = mapOf(
        Season.WINTER to "cinnamon_buns",
        Season.SPRING to "pancakes",
        Season.SUMMER to "focaccia",
        Season.AUTUMN to "family_bread",
    )

    fun recipeIdFor(season: Season): String? = SEASON_RECIPE[season]
}
