package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 10, фича «Зеркальный отпечаток» (InkMirror): след бледнее
 * самой пометы, выцветает монотонно и до конца, отпечатывается только самая
 * свежая из непросохших помет.
 */
class InkMirrorTest {

    private val hour = 3_600_000L

    @Test
    fun `a fresh imprint is visible but fainter than the note itself`() {
        assertThat(InkMirror.alphaFor(0L)).isEqualTo(InkMirror.MAX_ALPHA)
        // Пометы пишутся полной краской (alpha 1); след — заведомо бледный.
        assertThat(InkMirror.MAX_ALPHA).isAtMost(0.3f)
    }

    @Test
    fun `the imprint fades monotonically and disappears completely`() {
        val samples = (0..48).map { InkMirror.alphaFor(it * hour) }
        samples.zipWithNext().forEach { (fresh, older) ->
            assertThat(older).isAtMost(fresh)
        }
        assertThat(InkMirror.alphaFor(InkMirror.FADE_MILLIS)).isEqualTo(0f)
        assertThat(InkMirror.alphaFor(InkMirror.FADE_MILLIS * 10)).isEqualTo(0f)
    }

    @Test
    fun `an age from the future counts as fresh`() {
        assertThat(InkMirror.alphaFor(-hour)).isEqualTo(InkMirror.MAX_ALPHA)
    }

    @Test
    fun `wet window opens at once and closes before the fade limit`() {
        assertThat(InkMirror.isWet(0L)).isTrue()
        assertThat(InkMirror.isWet(hour)).isTrue()
        assertThat(InkMirror.isWet(InkMirror.FADE_MILLIS)).isFalse()
    }

    @Test
    fun `only the freshest wet note leaves an imprint`() {
        val now = 100L * hour
        val timestamps = listOf(
            now - 90 * hour, // давно высохла
            now - 10 * hour, // влажная, но не самая свежая
            now - 1 * hour, // самая свежая
        )
        assertThat(InkMirror.freshestWetIndex(now, timestamps)).isEqualTo(2)
    }

    @Test
    fun `dry pages leave no imprint at all`() {
        val now = 1_000L * hour
        assertThat(InkMirror.freshestWetIndex(now, emptyList())).isNull()
        assertThat(InkMirror.freshestWetIndex(now, listOf(now - 500 * hour))).isNull()
    }
}
