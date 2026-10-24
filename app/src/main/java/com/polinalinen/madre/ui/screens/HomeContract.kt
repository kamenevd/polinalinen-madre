package com.polinalinen.madre.ui.screens

object TocLine {
    /** A11y и тесты: «Хлебушек домашний, рецепт 1». */
    fun contentDescription(name: String, index: Int): String =
        "$name, рецепт $index"

    /**
     * Однострочное оглавление для тестов и логов.
     * Визуальные точки на экране рисует [TocLeaders]; здесь — фиксированные
     * лидеры, чтобы assert не зависел от ширины.
     */
    fun format(name: String, index: Int, leaderWidth: Int = 24): String {
        val dots = ".".repeat(leaderWidth.coerceAtLeast(3))
        return "$name$dots$index"
    }
}

object Home {
    const val LIST_TAG = "home-list"

    fun chapterRowTag(recipeId: String): String = "home-chapter-$recipeId"
}

internal object HomeCopy {
    const val BAKERY = "Домашняя пекарня"
}

