package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Склонения на разворотe Полки: «раз в 21 день», но «раз в 11 дней».
 *
 * Числа 11, 12, 14 и 21 — те самые, на которых русское склонение ломается чаще
 * всего: 11 выглядит как «один», 21 — как «много», 12 и 14 — как «два» и
 * «четыре». Формуляр книги считает интервалы месяцами, так что до них доходит.
 */
class BookStatsWordsTest {

    @Test
    fun `days are declined the way Russian actually declines them`() {
        assertThat(dayWord(1)).isEqualTo("день")
        assertThat(dayWord(2)).isEqualTo("дня")
        assertThat(dayWord(5)).isEqualTo("дней")
        assertThat(dayWord(11)).isEqualTo("дней")
        assertThat(dayWord(12)).isEqualTo("дней")
        assertThat(dayWord(14)).isEqualTo("дней")
        assertThat(dayWord(21)).isEqualTo("день")
        assertThat(dayWord(22)).isEqualTo("дня")
    }

    @Test
    fun `bakes are declined too, on the same treacherous numbers`() {
        assertThat(bakeWord(1)).isEqualTo("выпечка")
        assertThat(bakeWord(2)).isEqualTo("выпечки")
        assertThat(bakeWord(5)).isEqualTo("выпечек")
        assertThat(bakeWord(11)).isEqualTo("выпечек")
        assertThat(bakeWord(12)).isEqualTo("выпечек")
        assertThat(bakeWord(14)).isEqualTo("выпечек")
        assertThat(bakeWord(21)).isEqualTo("выпечка")
        assertThat(bakeWord(24)).isEqualTo("выпечки")
    }

    /** Ноль — тоже число, и книга не должна на нём заикаться. */
    @Test
    fun `zero is a number the book can say`() {
        assertThat(dayWord(0)).isEqualTo("дней")
        assertThat(bakeWord(0)).isEqualTo("выпечек")
    }
}
