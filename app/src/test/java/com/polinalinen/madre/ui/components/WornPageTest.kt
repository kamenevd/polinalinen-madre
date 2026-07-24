package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 4, фича «Затёртая страница» (WornPages): пороги износа —
 * 3 выпечки (край), 7 (отпечаток пальца), 15 (корешок) — и альфы-функции от
 * count(bakeRecords). Проверяем пороги, монотонный рост и потолки: страница
 * стареет, но никогда не становится нечитаемой.
 */
class WornPageTest {

    @Test
    fun `edge darkening appears exactly at three bakes`() {
        assertThat(WornPage.showsEdgeDarkening(0)).isFalse()
        assertThat(WornPage.showsEdgeDarkening(2)).isFalse()
        assertThat(WornPage.showsEdgeDarkening(3)).isTrue()
        assertThat(WornPage.showsEdgeDarkening(100)).isTrue()
    }

    @Test
    fun `fingerprint appears exactly at seven bakes`() {
        assertThat(WornPage.showsFingerprint(6)).isFalse()
        assertThat(WornPage.showsFingerprint(7)).isTrue()
    }

    @Test
    fun `spine wear appears exactly at fifteen bakes`() {
        assertThat(WornPage.showsSpineWear(14)).isFalse()
        assertThat(WornPage.showsSpineWear(15)).isTrue()
    }

    @Test
    fun `untouched recipe has a perfectly clean page`() {
        assertThat(WornPage.edgeAlpha(0)).isEqualTo(0f)
        assertThat(WornPage.edgeAlpha(2)).isEqualTo(0f)
        assertThat(WornPage.tocAlpha(0)).isEqualTo(0f)
        assertThat(WornPage.tocAlpha(2)).isEqualTo(0f)
    }

    @Test
    fun `edge alpha grows with bake count but never exceeds its ceiling`() {
        var previous = 0f
        (3..40).forEach { count ->
            val alpha = WornPage.edgeAlpha(count)
            assertThat(alpha).isAtLeast(previous)
            assertThat(alpha).isAtMost(0.6f)
            previous = alpha
        }
        assertThat(WornPage.edgeAlpha(1000)).isEqualTo(0.6f)
    }

    @Test
    fun `toc tint stays subtle so chapter text keeps winning`() {
        var previous = 0f
        (3..40).forEach { count ->
            val alpha = WornPage.tocAlpha(count)
            assertThat(alpha).isAtLeast(previous)
            assertThat(alpha).isAtMost(0.08f)
            previous = alpha
        }
        assertThat(WornPage.tocAlpha(1000)).isEqualTo(0.08f)
    }

    @Test
    fun `a well-baked chapter is visibly darker than a fresh one`() {
        assertThat(WornPage.tocAlpha(10)).isGreaterThan(WornPage.tocAlpha(3))
        assertThat(WornPage.edgeAlpha(10)).isGreaterThan(WornPage.edgeAlpha(3))
    }
}
