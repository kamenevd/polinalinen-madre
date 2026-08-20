package com.polinalinen.madre.shelf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 27: выпечка на полке — всегда сама или один лист на готовности.
 */
class ShelfSharePolicyTest {

    @Test
    fun `always puts the bake on the shelf at once, without a sheet`() {
        val mode = ShelfShareMode.ALWAYS
        assertThat(ShelfSharePolicy.shouldShareOnComplete(mode, sharingAvailable = true)).isTrue()
        assertThat(ShelfSharePolicy.shouldAskOnComplete(mode, sharingAvailable = true)).isFalse()
        assertThat(ShelfSharePolicy.showOnShelfStamp(mode, sharingAvailable = true)).isTrue()
    }

    @Test
    fun `ask waits for the sheet and does not enqueue by itself`() {
        val mode = ShelfShareMode.ASK
        assertThat(ShelfSharePolicy.shouldShareOnComplete(mode, sharingAvailable = true)).isFalse()
        assertThat(ShelfSharePolicy.shouldAskOnComplete(mode, sharingAvailable = true)).isTrue()
        assertThat(ShelfSharePolicy.showOnShelfStamp(mode, sharingAvailable = true)).isFalse()
    }

    @Test
    fun `without an account there is nowhere to put a bake`() {
        assertThat(ShelfSharePolicy.shouldShareOnComplete(ShelfShareMode.ALWAYS, false)).isFalse()
        assertThat(ShelfSharePolicy.shouldAskOnComplete(ShelfShareMode.ASK, false)).isFalse()
        assertThat(ShelfSharePolicy.showOnShelfStamp(ShelfShareMode.ALWAYS, false)).isFalse()
    }

    @Test
    fun `put and put with a frame enqueue, keep does not`() {
        assertThat(ShelfSharePolicy.shouldEnqueue(ShelfShareDecision.PUT)).isTrue()
        assertThat(ShelfSharePolicy.shouldEnqueue(ShelfShareDecision.PUT_WITH_PHOTO)).isTrue()
        assertThat(ShelfSharePolicy.shouldEnqueue(ShelfShareDecision.KEEP)).isFalse()
        assertThat(ShelfSharePolicy.wantsPhoto(ShelfShareDecision.PUT_WITH_PHOTO)).isTrue()
        assertThat(ShelfSharePolicy.wantsPhoto(ShelfShareDecision.PUT)).isFalse()
    }

    @Test
    fun `unknown stored value falls back to always, never to a silent no`() {
        assertThat(ShelfSharePolicy.parse(null)).isEqualTo(ShelfShareMode.ALWAYS)
        assertThat(ShelfSharePolicy.parse("")).isEqualTo(ShelfShareMode.ALWAYS)
        assertThat(ShelfSharePolicy.parse("ASK")).isEqualTo(ShelfShareMode.ASK)
    }

    @Test
    fun `the sheet speaks the words the page will print`() {
        assertThat(ShelfSharePolicy.SHEET_TITLE).isEqualTo("Поставить на полку?")
        assertThat(ShelfSharePolicy.PUT_LABEL).isEqualTo("Поставить")
        assertThat(ShelfSharePolicy.PUT_WITH_PHOTO_LABEL).isEqualTo("Поставить с кадром")
        assertThat(ShelfSharePolicy.KEEP_LABEL).isEqualTo("Оставить себе")
        assertThat(ShelfSharePolicy.ON_SHELF_STAMP).isEqualTo("на полке")
        assertThat(ShelfSharePolicy.SETTING_LABEL).isEqualTo("Ставить выпечку на полку")
    }
}
