package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 3, фича «Кляксы» (InkBlot): форма/положение кляксы —
 * чистая функция от seed, seed — чистая функция от id события. Проверяем
 * формулу seed'а, детерминированность спеки и что она остаётся в разумных
 * границах (не съезжает за пределы страницы, альфа не рвёт полупрозрачность).
 */
class InkBlotTest {

    @Test
    fun `seed follows the id times 31 plus offset formula`() {
        assertThat(InkBlot.seedFor(eventId = 5L, offset = 1)).isEqualTo(156L)
        assertThat(InkBlot.seedFor(eventId = 0L, offset = 0)).isEqualTo(0L)
        assertThat(InkBlot.seedFor(eventId = 100L, offset = 7)).isEqualTo(3107L)
    }

    @Test
    fun `same seed always produces the same spec`() {
        val a = InkBlot.specFor(seed = 42L)
        val b = InkBlot.specFor(seed = 42L)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `different feeding ids produce different blots`() {
        val a = InkBlot.specFor(InkBlot.seedFor(eventId = 1L, offset = 0))
        val b = InkBlot.specFor(InkBlot.seedFor(eventId = 2L, offset = 0))
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `different offsets on the same event id also diverge`() {
        val diary = InkBlot.specFor(InkBlot.seedFor(eventId = 9L, offset = 1))
        val formulary = InkBlot.specFor(InkBlot.seedFor(eventId = 9L, offset = 211))
        assertThat(diary).isNotEqualTo(formulary)
    }

    @Test
    fun `position stays within the drawing area`() {
        repeat(50) { i ->
            val spec = InkBlot.specFor(seed = i.toLong() * 17)
            assertThat(spec.cxFraction).isAtLeast(0.0f)
            assertThat(spec.cxFraction).isAtMost(1.0f)
            assertThat(spec.cyFraction).isAtLeast(0.0f)
            assertThat(spec.cyFraction).isAtMost(1.0f)
        }
    }

    @Test
    fun `alpha stays within the 0,15 to 0,30 spec range`() {
        repeat(50) { i ->
            val spec = InkBlot.specFor(seed = i.toLong() * 23)
            assertThat(spec.alpha).isAtLeast(0.15f)
            assertThat(spec.alpha).isAtMost(0.30f)
        }
    }

    @Test
    fun `blot has at least three lobes to read as an irregular shape`() {
        repeat(50) { i ->
            val spec = InkBlot.specFor(seed = i.toLong() * 29)
            assertThat(spec.radiusLobes.size).isAtLeast(3)
        }
    }
}
