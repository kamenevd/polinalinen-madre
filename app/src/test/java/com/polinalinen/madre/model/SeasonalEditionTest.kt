package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 3, фича «Сезонная глава» (SeasonalEdition): маппинг
 * месяц→сезон и сезон→рецепт — статические чистые функции, проверяем границы
 * (декабрь = зима, а не декабрь = осень) и что каждый сезон отмечает ровно
 * один существующий рецепт.
 */
class SeasonalEditionTest {

    @Test
    fun `december january february are winter`() {
        assertThat(SeasonalEdition.seasonForMonth(12)).isEqualTo(Season.WINTER)
        assertThat(SeasonalEdition.seasonForMonth(1)).isEqualTo(Season.WINTER)
        assertThat(SeasonalEdition.seasonForMonth(2)).isEqualTo(Season.WINTER)
    }

    @Test
    fun `march april may are spring`() {
        assertThat(SeasonalEdition.seasonForMonth(3)).isEqualTo(Season.SPRING)
        assertThat(SeasonalEdition.seasonForMonth(4)).isEqualTo(Season.SPRING)
        assertThat(SeasonalEdition.seasonForMonth(5)).isEqualTo(Season.SPRING)
    }

    @Test
    fun `june july august are summer`() {
        assertThat(SeasonalEdition.seasonForMonth(6)).isEqualTo(Season.SUMMER)
        assertThat(SeasonalEdition.seasonForMonth(7)).isEqualTo(Season.SUMMER)
        assertThat(SeasonalEdition.seasonForMonth(8)).isEqualTo(Season.SUMMER)
    }

    @Test
    fun `september october november are autumn`() {
        assertThat(SeasonalEdition.seasonForMonth(9)).isEqualTo(Season.AUTUMN)
        assertThat(SeasonalEdition.seasonForMonth(10)).isEqualTo(Season.AUTUMN)
        assertThat(SeasonalEdition.seasonForMonth(11)).isEqualTo(Season.AUTUMN)
    }

    @Test
    fun `month outside 1 to 12 throws`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SeasonalEdition.seasonForMonth(0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SeasonalEdition.seasonForMonth(13)
        }
    }

    @Test
    fun `every season has a distinct labelled recipe`() {
        val recipeIds = Season.entries.map { SeasonalEdition.recipeIdFor(it) }
        assertThat(recipeIds).doesNotContain(null)
        assertThat(recipeIds.toSet()).hasSize(Season.entries.size)
    }

    @Test
    fun `labels are non-empty and distinct per season`() {
        val labels = Season.entries.map { SeasonalEdition.labelFor(it) }
        assertThat(labels.toSet()).hasSize(Season.entries.size)
        labels.forEach { assertThat(it).isNotEmpty() }
    }
}
