package com.polinalinen.madre.shelf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShelfShareDecisionCycleTest {

    @Test
    fun `default decision is put with photo and labels are exact`() {
        assertThat(ShelfSharePolicy.DEFAULT_DECISION).isEqualTo(ShelfShareDecision.PUT_WITH_PHOTO)
        assertThat(ShelfSharePolicy.labelOf(ShelfShareDecision.PUT_WITH_PHOTO))
            .isEqualTo("на полке · с кадром")
        assertThat(ShelfSharePolicy.labelOf(ShelfShareDecision.KEEP)).isEqualTo("себе")
    }

    @Test
    fun `next cycles through two values`() {
        val first = ShelfSharePolicy.DEFAULT_DECISION
        val second = ShelfSharePolicy.next(first)
        val third = ShelfSharePolicy.next(second)
        val fourth = ShelfSharePolicy.next(third)

        assertThat(listOf(first, second, third, fourth)).containsExactly(
            ShelfShareDecision.PUT_WITH_PHOTO,
            ShelfShareDecision.KEEP,
            ShelfShareDecision.PUT_WITH_PHOTO,
            ShelfShareDecision.KEEP,
        ).inOrder()
    }
}
