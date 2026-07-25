package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 10, фича «Чтение при свече» (Candlelight): свеча горит
 * только после заката, пламя дрожит в узких пределах и без шва по циклу,
 * полумрак не съедает читаемость.
 */
class CandlelightTest {

    @Test
    fun `the candle burns after sunset and until early morning`() {
        assertThat(Candlelight.isCandleTime(21)).isTrue()
        assertThat(Candlelight.isCandleTime(23)).isTrue()
        assertThat(Candlelight.isCandleTime(0)).isTrue()
        assertThat(Candlelight.isCandleTime(5)).isTrue()
    }

    @Test
    fun `daylight needs no candle`() {
        (6..20).forEach { hour ->
            assertThat(Candlelight.isCandleTime(hour)).isFalse()
        }
    }

    @Test
    fun `the flame trembles within narrow bounds but visibly`() {
        val samples = (0..200).map { Candlelight.flicker(it / 200f) }
        samples.forEach { f ->
            assertThat(f).isAtLeast(0.9f)
            assertThat(f).isAtMost(1.1f)
        }
        // Дрожание видно — это не константа.
        assertThat(samples.max() - samples.min()).isGreaterThan(0.02f)
    }

    @Test
    fun `the flicker loop has no seam`() {
        assertThat(abs(Candlelight.flicker(0f) - Candlelight.flicker(1f))).isLessThan(1e-3f)
    }

    @Test
    fun `the light pool covers the reading area and breathes with the flame`() {
        val steady = Candlelight.radiusPx(1000f, 1f)
        assertThat(steady).isEqualTo(600f)
        assertThat(Candlelight.radiusPx(1000f, 1.1f)).isGreaterThan(steady)
        assertThat(Candlelight.radiusPx(1000f, 0.9f)).isLessThan(steady)
    }

    @Test
    fun `dusk edges stay translucent and the glow stays subtle`() {
        // Полумрак, не темнота: текст у края всё ещё различим.
        assertThat(Candlelight.SCRIM_ALPHA).isAtMost(0.55f)
        listOf(0.9f, 1f, 1.1f).forEach { f ->
            assertThat(Candlelight.glowAlpha(f)).isAtLeast(0.04f)
            assertThat(Candlelight.glowAlpha(f)).isAtMost(0.14f)
        }
        // Пламя вспыхнуло — ядро теплее.
        assertThat(Candlelight.glowAlpha(1.1f)).isGreaterThan(Candlelight.glowAlpha(0.9f))
    }
}
