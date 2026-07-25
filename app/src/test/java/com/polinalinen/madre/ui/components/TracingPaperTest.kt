package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 9, фича «Калька» (TracingPaper): лист появляется только
 * на юбилейных страницах, приписка Мадре осмысленна, отгибание уводит лист
 * в сторону жеста, а фактура волокон детерминированна.
 */
class TracingPaperTest {

    @Test
    fun `milestones are the first, tenth, twenty fifth and every fiftieth bake`() {
        listOf(1, 10, 25, 50, 100, 150, 500).forEach {
            assertThat(TracingPaper.isMilestone(it)).isTrue()
        }
        listOf(0, 2, 9, 11, 24, 26, 49, 51, 99).forEach {
            assertThat(TracingPaper.isMilestone(it)).isFalse()
        }
    }

    @Test
    fun `madre writes a note only on milestone pages`() {
        assertThat(TracingPaper.noteFor(0)).isNull()
        assertThat(TracingPaper.noteFor(7)).isNull()
        assertThat(TracingPaper.noteFor(1)).isNotEmpty()
        assertThat(TracingPaper.noteFor(10)).isNotEmpty()
        assertThat(TracingPaper.noteFor(25)).isNotEmpty()
        // У «круглых» юбилеев в приписке — номер выпечки.
        assertThat(TracingPaper.noteFor(50)).contains("50")
        assertThat(TracingPaper.noteFor(100)).contains("100")
    }

    @Test
    fun `the sheet slides off in the swipe direction and past the edge`() {
        assertThat(TracingPaper.foldDx(0f, 1f, 1000f)).isEqualTo(0f)
        assertThat(TracingPaper.foldDx(0.5f, 1f, 1000f)).isGreaterThan(0f)
        assertThat(TracingPaper.foldDx(0.5f, -1f, 1000f)).isLessThan(0f)
        // К концу жеста лист гарантированно за краем экрана.
        assertThat(TracingPaper.foldDx(1f, 1f, 1000f)).isGreaterThan(1000f)
    }

    @Test
    fun `the sheet tilts with the gesture but only slightly`() {
        assertThat(TracingPaper.foldRotationDeg(0f, 1f)).isEqualTo(0f)
        assertThat(TracingPaper.foldRotationDeg(1f, 1f)).isGreaterThan(0f)
        assertThat(TracingPaper.foldRotationDeg(1f, -1f)).isLessThan(0f)
        assertThat(TracingPaper.foldRotationDeg(1f, 1f)).isAtMost(20f)
    }

    @Test
    fun `tracing paper fades a little but never dissolves`() {
        assertThat(TracingPaper.foldAlpha(0f)).isEqualTo(1f)
        assertThat(TracingPaper.foldAlpha(1f)).isLessThan(1f)
        assertThat(TracingPaper.foldAlpha(1f)).isGreaterThan(0f)
    }

    @Test
    fun `fibre texture is deterministic and stays inside the sheet`() {
        val a = TracingPaper.fibres(seed = 5L)
        assertThat(a).isEqualTo(TracingPaper.fibres(seed = 5L))
        assertThat(a).isNotEqualTo(TracingPaper.fibres(seed = 6L))
        a.forEach { f ->
            assertThat(f.y).isAtLeast(0f)
            assertThat(f.y).isAtMost(1f)
            assertThat(f.x0).isAtLeast(0f)
            assertThat(f.x1).isGreaterThan(f.x0)
            assertThat(f.x1).isAtMost(1f)
            // Волокна еле заметны — калька остаётся прозрачной.
            assertThat(f.alpha).isAtMost(0.12f)
        }
    }
}
