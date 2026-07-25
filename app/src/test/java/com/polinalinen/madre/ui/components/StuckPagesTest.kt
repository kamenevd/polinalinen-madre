package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 10, фича «Слипшиеся страницы» (StuckPages): событие
 * редкое и детерминированное, расклеенное не слипается заново, медленный жест
 * работает, а рывок — почти нет, хаптика тикает по десятым, находка под
 * склейкой детерминирована.
 */
class StuckPagesTest {

    @Test
    fun `appearance is deterministic and never on a freed page`() {
        repeat(100) { i ->
            val seed = i.toLong()
            assertThat(StuckPages.appears(seed, alreadyFreed = false))
                .isEqualTo(StuckPages.appears(seed, alreadyFreed = false))
            assertThat(StuckPages.appears(seed, alreadyFreed = true)).isFalse()
        }
    }

    @Test
    fun `stuck pages are a rare event`() {
        val hits = (0 until 10_000).count { StuckPages.appears(it.toLong(), alreadyFreed = false) }
        // Около CHANCE: заметно реже книжного жучка, но не исчезающе редко.
        assertThat(hits).isAtLeast(400)
        assertThat(hits).isAtMost(1_000)
    }

    @Test
    fun `slow strokes count in full and direction does not matter`() {
        val maxStep = 16f
        assertThat(StuckPages.peelGain(10f, maxStep)).isEqualTo(10f)
        assertThat(StuckPages.peelGain(-10f, maxStep)).isEqualTo(10f)
    }

    @Test
    fun `a fast swipe wastes everything above the per-event cap`() {
        val maxStep = 16f
        // Один рывок на 900px приносит не больше шага…
        assertThat(StuckPages.peelGain(900f, maxStep)).isEqualTo(maxStep)
        // …а те же 900px медленными порциями по 10px — целиком.
        val slow = (1..90).map { StuckPages.peelGain(10f, maxStep) }.sum()
        assertThat(slow).isEqualTo(900f)
        assertThat(slow).isGreaterThan(StuckPages.peelGain(900f, maxStep) * 10)
    }

    @Test
    fun `unsticking takes more than one pass along the edge`() {
        assertThat(StuckPages.requiredTravelPx(1000f)).isGreaterThan(1000f)
    }

    @Test
    fun `haptics tick once per tenth of progress`() {
        assertThat(StuckPages.hapticTicks(0.00f, 0.05f)).isEqualTo(0)
        assertThat(StuckPages.hapticTicks(0.05f, 0.12f)).isEqualTo(1)
        assertThat(StuckPages.hapticTicks(0.12f, 0.19f)).isEqualTo(0)
        assertThat(StuckPages.hapticTicks(0.19f, 0.45f)).isEqualTo(3)
        // Прогресс за пределами [0,1] не даёт лишних щелчков.
        assertThat(StuckPages.hapticTicks(0.95f, 3f)).isEqualTo(1)
    }

    @Test
    fun `the flap loosens slightly while peeling and leaves after release`() {
        val width = 100f
        // Во время расклейки кромка лишь отходит, не убегая.
        assertThat(StuckPages.flapDx(peel = 1f, release = 0f, flapWidthPx = width)).isAtMost(width * 0.3f)
        // После release кромка целиком за краем.
        assertThat(StuckPages.flapDx(peel = 1f, release = 1f, flapWidthPx = width)).isAtLeast(width)
        assertThat(StuckPages.flapRotationDeg(0f)).isEqualTo(0f)
    }

    @Test
    fun `the glue drop stays on the strip and is translucent`() {
        repeat(50) { i ->
            val drop = StuckPages.dropFor(i.toLong())
            assertThat(drop.y).isAtLeast(0.2f)
            assertThat(drop.y).isAtMost(0.8f)
            assertThat(drop.alpha).isAtMost(0.35f)
            assertThat(drop.alpha).isGreaterThan(0f)
        }
        assertThat(StuckPages.dropFor(7L)).isEqualTo(StuckPages.dropFor(7L))
    }

    @Test
    fun `the hidden note is deterministic and comes from the book`() {
        assertThat(StuckPages.hiddenNote(42L)).isEqualTo(StuckPages.hiddenNote(42L))
        repeat(20) { i ->
            assertThat(StuckPages.HIDDEN_NOTES).contains(StuckPages.hiddenNote(i.toLong()))
        }
    }

    @Test
    fun `prefs key is scoped per recipe`() {
        assertThat(StuckPages.prefsKey("rye")).isEqualTo("stuck_pages_freed_rye")
        assertThat(StuckPages.prefsKey("rye")).isNotEqualTo(StuckPages.prefsKey("wheat"))
    }
}
