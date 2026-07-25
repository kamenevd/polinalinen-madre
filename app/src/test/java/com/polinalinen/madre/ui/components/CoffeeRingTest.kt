package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 9, фича «След от кружки» (CoffeeRing): круг появляется
 * только после прерванной выпечки, старые пятна сливаются (потолок),
 * геометрия детерминированна, след бледный и целиком на странице.
 */
class CoffeeRingTest {

    @Test
    fun `a finished book page has no coffee rings`() {
        assertThat(CoffeeRing.ringCount(0)).isEqualTo(0)
        assertThat(CoffeeRing.ringCount(-1)).isEqualTo(0)
    }

    @Test
    fun `each interrupted bake leaves a ring up to the cap`() {
        assertThat(CoffeeRing.ringCount(1)).isEqualTo(1)
        assertThat(CoffeeRing.ringCount(2)).isEqualTo(2)
        assertThat(CoffeeRing.ringCount(100)).isEqualTo(CoffeeRing.MAX_RINGS)
    }

    @Test
    fun `ring geometry is deterministic for the same seed`() {
        assertThat(CoffeeRing.specFor(42L)).isEqualTo(CoffeeRing.specFor(42L))
        assertThat(CoffeeRing.specFor(42L)).isNotEqualTo(CoffeeRing.specFor(43L))
    }

    @Test
    fun `the ring stays on the page near the right margin and is faint`() {
        repeat(50) { i ->
            val spec = CoffeeRing.specFor(i.toLong())
            // Кружку ставят у правого поля, но след не свисает с краёв.
            assertThat(spec.cx).isAtLeast(0.5f)
            assertThat(spec.cx + spec.radius).isAtMost(1f)
            assertThat(spec.cy - spec.radius).isAtLeast(0f)
            assertThat(spec.cy + spec.radius).isAtMost(1f)
            // Высохший кофе бледный — текст рецепта остаётся читаемым.
            assertThat(spec.alpha).isAtMost(0.2f)
            assertThat(spec.alpha).isGreaterThan(0f)
            // Кольцо с разрывом, но узнаваемо круглое.
            assertThat(spec.gapSweepDeg).isAtLeast(40f)
            assertThat(spec.gapSweepDeg).isAtMost(110f)
            assertThat(spec.squash).isAtLeast(0.8f)
            assertThat(spec.squash).isAtMost(1f)
        }
    }

    @Test
    fun `prefs key is scoped per recipe`() {
        assertThat(CoffeeRing.prefsKey("rye")).isEqualTo("coffee_rings_rye")
        assertThat(CoffeeRing.prefsKey("rye")).isNotEqualTo(CoffeeRing.prefsKey("wheat"))
    }
}
