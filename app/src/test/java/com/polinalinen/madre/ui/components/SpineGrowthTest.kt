package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 2, фича «Растущий корешок» (SpineGrowth): толщина и
 * потёртости корешка — чистые функции от числа записей, проверяем рост,
 * границы и то, что толщина не растёт бесконечно (не резиновая книга).
 */
class SpineGrowthTest {

    @Test
    fun `thickness grows with more records`() {
        val thin = SpineGrowth.thicknessDp(recordCount = 0)
        val thick = SpineGrowth.thicknessDp(recordCount = 20)
        assertThat(thick).isGreaterThan(thin)
    }

    @Test
    fun `thickness never goes below the base for zero or negative records`() {
        assertThat(SpineGrowth.thicknessDp(recordCount = 0)).isEqualTo(SpineGrowth.thicknessDp(recordCount = -5))
    }

    @Test
    fun `thickness is capped for very long histories`() {
        val a = SpineGrowth.thicknessDp(recordCount = 500)
        val b = SpineGrowth.thicknessDp(recordCount = 5000)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `no worn lines for a fresh book`() {
        assertThat(SpineGrowth.wornLineCount(recordCount = 0)).isEqualTo(0)
        assertThat(SpineGrowth.wornLineCount(recordCount = 3)).isEqualTo(0)
    }

    @Test
    fun `worn lines appear and grow with history`() {
        assertThat(SpineGrowth.wornLineCount(recordCount = 4)).isEqualTo(1)
        assertThat(SpineGrowth.wornLineCount(recordCount = 8)).isEqualTo(2)
    }

    @Test
    fun `worn lines are capped for very long histories`() {
        val a = SpineGrowth.wornLineCount(recordCount = 200)
        val b = SpineGrowth.wornLineCount(recordCount = 2000)
        assertThat(a).isEqualTo(b)
    }
}
