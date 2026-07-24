package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.sourdough.GrowthPhase
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 6, фича «Дыхание книги» (BookBreath): первая полоса
 * дышит тише дневника (1.002 против 1.004), период — от фазы закваски.
 * Проверяем маппинг фаз и то, что амплитуды остаются «едва заметными» —
 * в пределах, заданных дизайном (scale 1.000–1.004).
 */
class BookBreathTest {

    @Test
    fun `peak breathes fast — bake with me now`() {
        assertThat(BookBreath.periodMillisFor(GrowthPhase.PEAK)).isEqualTo(2000)
    }

    @Test
    fun `hungry breathes anxiously — the shortest period`() {
        val hungry = BookBreath.periodMillisFor(GrowthPhase.HUNGRY)
        assertThat(hungry).isEqualTo(1500)
        GrowthPhase.entries.forEach { phase ->
            assertThat(BookBreath.periodMillisFor(phase)).isAtLeast(hungry)
        }
    }

    @Test
    fun `calm phases sleep with a slow four second breath`() {
        listOf(GrowthPhase.EMPTY, GrowthPhase.LAG, GrowthPhase.GROWING, GrowthPhase.DECLINING).forEach { phase ->
            assertThat(BookBreath.periodMillisFor(phase)).isEqualTo(4000)
        }
    }

    @Test
    fun `home page breathes softer than the diary and both stay barely visible`() {
        assertThat(BookBreath.HOME_AMPLITUDE).isLessThan(BookBreath.DIARY_AMPLITUDE)
        listOf(BookBreath.HOME_AMPLITUDE, BookBreath.DIARY_AMPLITUDE).forEach { amplitude ->
            assertThat(amplitude).isGreaterThan(1.000f)
            assertThat(amplitude).isAtMost(1.004f)
        }
    }
}
