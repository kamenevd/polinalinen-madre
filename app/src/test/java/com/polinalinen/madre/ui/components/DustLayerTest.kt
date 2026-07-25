package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 8, фича «Пыль на страницах» (DustLayer): пыль появляется
 * только на давно не открытых главах, густеет с потолком, россыпь
 * детерминированна, а смахивание разносит пылинки вбок с ранним таянием.
 */
class DustLayerTest {

    private val day = 86_400_000L

    @Test
    fun `a freshly opened page stays clean`() {
        assertThat(DustLayer.veilAlpha(0)).isEqualTo(0f)
        assertThat(DustLayer.moteCount(0)).isEqualTo(0)
        assertThat(DustLayer.veilAlpha(DustLayer.DUST_AFTER_DAYS - 1)).isEqualTo(0f)
        assertThat(DustLayer.moteCount(DustLayer.DUST_AFTER_DAYS - 1)).isEqualTo(0)
    }

    @Test
    fun `dust settles after a week and thickens with time`() {
        assertThat(DustLayer.veilAlpha(DustLayer.DUST_AFTER_DAYS)).isGreaterThan(0f)
        assertThat(DustLayer.moteCount(DustLayer.DUST_AFTER_DAYS)).isGreaterThan(0)
        assertThat(DustLayer.veilAlpha(30)).isGreaterThan(DustLayer.veilAlpha(DustLayer.DUST_AFTER_DAYS))
        assertThat(DustLayer.moteCount(30)).isGreaterThan(DustLayer.moteCount(DustLayer.DUST_AFTER_DAYS))
    }

    @Test
    fun `dust is capped so a forgotten chapter stays readable`() {
        assertThat(DustLayer.veilAlpha(10_000)).isAtMost(0.10f)
        assertThat(DustLayer.moteCount(10_000)).isEqualTo(DustLayer.MAX_MOTES)
    }

    @Test
    fun `days are counted in full days and a never opened book has none`() {
        val now = 1_700_000_000_000L
        assertThat(DustLayer.daysSince(0L, now)).isEqualTo(0)
        assertThat(DustLayer.daysSince(now - 12 * day, now)).isEqualTo(12)
        assertThat(DustLayer.daysSince(now - day / 2, now)).isEqualTo(0)
        assertThat(DustLayer.daysSince(now + day, now)).isEqualTo(0)
    }

    @Test
    fun `scatter is deterministic for the same seed`() {
        val a = DustLayer.scatter(seed = 42L, count = 40)
        val b = DustLayer.scatter(seed = 42L, count = 40)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(DustLayer.scatter(seed = 43L, count = 40))
    }

    @Test
    fun `scatter keeps motes inside the page and faint`() {
        val motes = DustLayer.scatter(seed = 7L, count = 60)
        assertThat(motes).hasSize(60)
        motes.forEach {
            assertThat(it.x).isAtLeast(0f)
            assertThat(it.x).isAtMost(1f)
            assertThat(it.y).isAtLeast(0f)
            assertThat(it.y).isAtMost(1f)
            assertThat(it.sizeDp).isGreaterThan(0f)
            assertThat(it.alpha).isAtMost(0.3f)
        }
    }

    @Test
    fun `sweep carries dust in the swipe direction`() {
        assertThat(DustLayer.sweepDx(0.5f, drift = 0.5f, direction = 1f, widthPx = 1000f)).isGreaterThan(0f)
        assertThat(DustLayer.sweepDx(0.5f, drift = 0.5f, direction = -1f, widthPx = 1000f)).isLessThan(0f)
        assertThat(DustLayer.sweepDx(0f, drift = 0.5f, direction = 1f, widthPx = 1000f)).isEqualTo(0f)
    }

    @Test
    fun `dust lifts up instead of falling`() {
        assertThat(DustLayer.sweepDy(0.5f, drift = 0.5f, heightPx = 2000f)).isLessThan(0f)
        // isWithin: до начала жеста подъём нулевой (у float бывает -0.0).
        assertThat(DustLayer.sweepDy(0f, drift = 0.5f, heightPx = 2000f)).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `dust fades early unlike crumbs`() {
        assertThat(DustLayer.sweepAlpha(0f)).isEqualTo(1f)
        assertThat(DustLayer.sweepAlpha(0.5f)).isLessThan(0.3f)
        assertThat(DustLayer.sweepAlpha(1f)).isEqualTo(0f)
        // Крошки в той же точке жеста ещё почти целы — характеры разные.
        assertThat(DustLayer.sweepAlpha(0.5f)).isLessThan(Crumbs.sweepAlpha(0.5f))
    }
}
