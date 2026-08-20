package com.polinalinen.madre.shelf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShelfSharePolicyTest {

    @Test
    fun `put with photo enqueues and keep does not`() {
        assertThat(ShelfSharePolicy.shouldEnqueue(ShelfShareDecision.PUT_WITH_PHOTO)).isTrue()
        assertThat(ShelfSharePolicy.shouldEnqueue(ShelfShareDecision.KEEP)).isFalse()
        assertThat(ShelfSharePolicy.wantsPhoto(ShelfShareDecision.PUT_WITH_PHOTO)).isTrue()
        assertThat(ShelfSharePolicy.wantsPhoto(ShelfShareDecision.KEEP)).isFalse()
    }

    @Test
    fun `decision cycle has two values and wraps`() {
        val first = ShelfSharePolicy.DEFAULT_DECISION
        val second = ShelfSharePolicy.next(first)
        val third = ShelfSharePolicy.next(second)

        assertThat(first).isEqualTo(ShelfShareDecision.PUT_WITH_PHOTO)
        assertThat(second).isEqualTo(ShelfShareDecision.KEEP)
        assertThat(third).isEqualTo(first)
    }

    @Test
    fun `labels stay exact and no prefs api remains`() {
        assertThat(ShelfSharePolicy.labelOf(ShelfShareDecision.PUT_WITH_PHOTO))
            .isEqualTo("на полке · с кадром")
        assertThat(ShelfSharePolicy.labelOf(ShelfShareDecision.KEEP)).isEqualTo("себе")
    }
}
