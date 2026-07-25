package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 7, фича «Крошки между страниц» (Crumbs): количество
 * частиц растёт с числом выпечек (с потолком), россыпь детерминированна от
 * seed, а смахивание уносит крошки вбок и гарантированно за нижний край.
 */
class CrumbsTest {

    @Test
    fun `an unbaked recipe keeps a clean page`() {
        assertThat(Crumbs.crumbCount(0)).isEqualTo(0)
        assertThat(Crumbs.dustCount(0)).isEqualTo(0)
        assertThat(Crumbs.crumbCount(-3)).isEqualTo(0)
    }

    @Test
    fun `more bakes leave more crumbs`() {
        assertThat(Crumbs.crumbCount(1)).isGreaterThan(0)
        assertThat(Crumbs.crumbCount(5)).isGreaterThan(Crumbs.crumbCount(1))
        assertThat(Crumbs.dustCount(5)).isGreaterThan(Crumbs.dustCount(1))
    }

    @Test
    fun `crumbs are capped so the page stays readable`() {
        assertThat(Crumbs.crumbCount(1000)).isEqualTo(Crumbs.MAX_CRUMBS)
        assertThat(Crumbs.dustCount(1000)).isEqualTo(Crumbs.MAX_DUST)
    }

    @Test
    fun `scatter is deterministic for the same seed`() {
        val a = Crumbs.scatter(seed = 42L, crumbs = 10, dust = 20)
        val b = Crumbs.scatter(seed = 42L, crumbs = 10, dust = 20)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(Crumbs.scatter(seed = 43L, crumbs = 10, dust = 20))
    }

    @Test
    fun `scatter produces requested particles inside the page`() {
        val particles = Crumbs.scatter(seed = 7L, crumbs = 12, dust = 30)
        assertThat(particles).hasSize(42)
        assertThat(particles.count { it.isDust }).isEqualTo(30)
        particles.forEach {
            assertThat(it.x).isAtLeast(0f)
            assertThat(it.x).isAtMost(1f)
            assertThat(it.y).isAtLeast(0f)
            assertThat(it.y).isAtMost(1f)
            assertThat(it.sizeDp).isGreaterThan(0f)
        }
    }

    @Test
    fun `dust is finer than crumbs`() {
        val particles = Crumbs.scatter(seed = 7L, crumbs = 12, dust = 30)
        val maxDust = particles.filter { it.isDust }.maxOf { it.sizeDp }
        val minCrumb = particles.filter { !it.isDust }.minOf { it.sizeDp }
        assertThat(maxDust).isLessThan(minCrumb)
    }

    @Test
    fun `sweep starts still and accelerates downward past the bottom edge`() {
        val height = 2000f
        assertThat(Crumbs.sweepDy(0f, y = 0.5f, heightPx = height)).isEqualTo(0f)
        val mid = Crumbs.sweepDy(0.5f, y = 0.5f, heightPx = height)
        val end = Crumbs.sweepDy(1f, y = 0.5f, heightPx = height)
        assertThat(mid).isGreaterThan(0f)
        // Ускорение: вторая половина пути длиннее первой.
        assertThat(end - mid).isGreaterThan(mid)
        // К концу частица гарантированно ниже кромки экрана.
        assertThat(0.5f * height + end).isGreaterThan(height)
    }

    @Test
    fun `even a crumb at the very bottom still falls off screen`() {
        val height = 2000f
        val end = Crumbs.sweepDy(1f, y = 1f, heightPx = height)
        assertThat(1f * height + end).isGreaterThan(height)
    }

    @Test
    fun `sweep carries crumbs in the swipe direction`() {
        assertThat(Crumbs.sweepDx(0.5f, drift = 0.5f, direction = 1f, widthPx = 1000f)).isGreaterThan(0f)
        assertThat(Crumbs.sweepDx(0.5f, drift = 0.5f, direction = -1f, widthPx = 1000f)).isLessThan(0f)
        assertThat(Crumbs.sweepDx(0f, drift = 0.5f, direction = 1f, widthPx = 1000f)).isEqualTo(0f)
    }

    @Test
    fun `crumbs fade only towards the end of the flight`() {
        assertThat(Crumbs.sweepAlpha(0f)).isEqualTo(1f)
        assertThat(Crumbs.sweepAlpha(0.5f)).isGreaterThan(0.8f)
        assertThat(Crumbs.sweepAlpha(1f)).isEqualTo(0f)
    }
}
