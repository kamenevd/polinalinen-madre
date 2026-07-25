package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 8, фича «Книжный жучок» (BookWorm): событие редкое, но
 * чаще на давно не открытых главах; бросок детерминирован seed-ом, траектория
 * не выходит за поле страницы, пасхалка склоняется по счётчику.
 */
class BookWormTest {

    @Test
    fun `the worm is a rare guest on fresh pages`() {
        assertThat(BookWorm.chance(0)).isEqualTo(BookWorm.BASE_CHANCE)
        assertThat(BookWorm.chance(-5)).isEqualTo(BookWorm.BASE_CHANCE)
        assertThat(BookWorm.BASE_CHANCE).isLessThan(0.15f)
    }

    @Test
    fun `old pages attract worms but never guarantee one`() {
        assertThat(BookWorm.chance(30)).isGreaterThan(BookWorm.chance(0))
        assertThat(BookWorm.chance(10_000)).isEqualTo(BookWorm.MAX_CHANCE)
        assertThat(BookWorm.MAX_CHANCE).isLessThan(0.5f)
    }

    @Test
    fun `the dice roll is deterministic for a seed`() {
        (0L..50L).forEach { seed ->
            assertThat(BookWorm.appears(seed, 10)).isEqualTo(BookWorm.appears(seed, 10))
        }
    }

    @Test
    fun `across many opens the worm shows up about as often as promised`() {
        val freshRate = (0 until 2000).count { BookWorm.appears(it.toLong(), 0) }
        val oldRate = (0 until 2000).count { BookWorm.appears(it.toLong(), 90) }
        // База ~8% (раз в 12 открытий): широкие статистические рамки.
        assertThat(freshRate).isGreaterThan(60)
        assertThat(freshRate).isLessThan(340)
        // На старой странице — заметно чаще.
        assertThat(oldRate).isGreaterThan(freshRate)
    }

    @Test
    fun `the run stays on the left margin`() {
        (0L..20L).forEach { seed ->
            var p = 0f
            while (p <= 1f) {
                val x = BookWorm.xFraction(p, seed)
                assertThat(x).isGreaterThan(0f)
                assertThat(x).isLessThan(0.15f)
                p += 0.05f
            }
        }
    }

    @Test
    fun `the worm runs top to bottom without touching header or footer`() {
        assertThat(BookWorm.yFraction(0f)).isWithin(0.001f).of(0.08f)
        assertThat(BookWorm.yFraction(1f)).isWithin(0.001f).of(0.92f)
        assertThat(BookWorm.yFraction(0.7f)).isGreaterThan(BookWorm.yFraction(0.3f))
        assertThat(BookWorm.yFraction(2f)).isAtMost(0.92f)
    }

    @Test
    fun `the wiggle differs from worm to worm`() {
        // Хоть у каких-то двух жучков траектории в одной точке различаются.
        val xs = (0L..10L).map { BookWorm.xFraction(0.5f, it) }
        assertThat(xs.distinct().size).isGreaterThan(1)
    }

    @Test
    fun `the easter egg caption counts the catches`() {
        assertThat(BookWorm.caughtCaption(1)).isEqualTo("поймали книжного жучка")
        assertThat(BookWorm.caughtCaption(3)).isEqualTo("книжный жучок пойман — уже 3")
    }
}
