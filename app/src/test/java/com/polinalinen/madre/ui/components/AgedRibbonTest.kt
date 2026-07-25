package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 9, фича «Ветхое ляссе» (AgedRibbon): лента новой книги
 * целая и яркая, с выпечками выцветает и обтрёпывается — с потолками, чтобы
 * даже древняя книга не потеряла ляссе совсем. Бахрома детерминированна.
 */
class AgedRibbonTest {

    @Test
    fun `a new book keeps its ribbon crisp and bright`() {
        assertThat(AgedRibbon.fadeFraction(0)).isEqualTo(0f)
        assertThat(AgedRibbon.notchCount(0)).isEqualTo(0)
        assertThat(AgedRibbon.threadCount(0)).isEqualTo(0)
        assertThat(AgedRibbon.notchCount(AgedRibbon.FRAY_AFTER_BAKES - 1)).isEqualTo(0)
    }

    @Test
    fun `the ribbon fades with every bake but never loses its colour`() {
        assertThat(AgedRibbon.fadeFraction(1)).isGreaterThan(0f)
        assertThat(AgedRibbon.fadeFraction(20)).isGreaterThan(AgedRibbon.fadeFraction(5))
        assertThat(AgedRibbon.fadeFraction(10_000)).isEqualTo(AgedRibbon.MAX_FADE)
        assertThat(AgedRibbon.MAX_FADE).isLessThan(0.5f)
    }

    @Test
    fun `fraying starts after the first bakes and grows with a ceiling`() {
        assertThat(AgedRibbon.notchCount(AgedRibbon.FRAY_AFTER_BAKES)).isEqualTo(1)
        assertThat(AgedRibbon.notchCount(30)).isGreaterThan(AgedRibbon.notchCount(10))
        assertThat(AgedRibbon.notchCount(10_000)).isEqualTo(AgedRibbon.MAX_NOTCHES)
    }

    @Test
    fun `loose threads appear only on a well-read book and are capped at three`() {
        assertThat(AgedRibbon.threadCount(20)).isEqualTo(0)
        assertThat(AgedRibbon.threadCount(35)).isEqualTo(1)
        assertThat(AgedRibbon.threadCount(10_000)).isEqualTo(3)
    }

    @Test
    fun `fringe is deterministic for the same seed`() {
        val a = AgedRibbon.notches(seed = 42L, count = 7)
        val b = AgedRibbon.notches(seed = 42L, count = 7)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(AgedRibbon.notches(seed = 43L, count = 7))
        assertThat(AgedRibbon.threads(seed = 42L, count = 3))
            .isEqualTo(AgedRibbon.threads(seed = 42L, count = 3))
    }

    @Test
    fun `notches stay on the edge and bite modestly`() {
        val notches = AgedRibbon.notches(seed = 7L, count = AgedRibbon.MAX_NOTCHES)
        assertThat(notches).hasSize(AgedRibbon.MAX_NOTCHES)
        notches.forEach {
            assertThat(it.position).isAtLeast(0f)
            assertThat(it.position).isAtMost(1f)
            assertThat(it.depth).isAtLeast(0.15f)
            assertThat(it.depth).isAtMost(0.5f)
            assertThat(it.halfWidth).isGreaterThan(0f)
            assertThat(it.halfWidth).isAtMost(0.05f)
        }
        // Зазубрины идут слева направо по краю — раскиданы, а не в одной точке.
        assertThat(notches.map { it.position }).isInOrder()
    }

    @Test
    fun `empty fringe for zero count`() {
        assertThat(AgedRibbon.notches(seed = 1L, count = 0)).isEmpty()
        assertThat(AgedRibbon.threads(seed = 1L, count = 0)).isEmpty()
    }
}
