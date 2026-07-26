package com.polinalinen.madre.ui.photo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 11, «Стол оформления»: выбор оформления фотокарточки —
 * чистая модель, и правила выбора проверяются без Android. Главное правило —
 * штамп ровно один и его всегда можно снять.
 */
class PhotoDecorTest {

    @Test
    fun `stamp is single — picking another one replaces it`() {
        val decor = PhotoDecor().withStamp(PhotoStamp.WHEAT).withStamp(PhotoStamp.LOAF)
        assertThat(decor.stamp).isEqualTo(PhotoStamp.LOAF)
    }

    @Test
    fun `tapping the chosen stamp again takes it off`() {
        val decor = PhotoDecor().withStamp(PhotoStamp.ROSETTE).withStamp(PhotoStamp.ROSETTE)
        assertThat(decor.stamp).isNull()
    }

    @Test
    fun `corner is remembered even while no stamp is chosen`() {
        val decor = PhotoDecor().withCorner(StampCorner.TOP_LEFT)
        assertThat(decor.stamp).isNull()
        assertThat(decor.withStamp(PhotoStamp.WHEAT).stampCorner).isEqualTo(StampCorner.TOP_LEFT)
    }

    @Test
    fun `warm light toggles both ways`() {
        val warm = PhotoDecor(warm = true)
        assertThat(warm.toggleWarm().warm).isFalse()
        assertThat(warm.toggleWarm().toggleWarm().warm).isTrue()
    }

    @Test
    fun `frame switching keeps the rest of the choice`() {
        val decor = PhotoDecor(warm = false).withStamp(PhotoStamp.LOAF).withFrame(PhotoFrame.DECKLE)
        assertThat(decor.frame).isEqualTo(PhotoFrame.DECKLE)
        assertThat(decor.stamp).isEqualTo(PhotoStamp.LOAF)
        assertThat(decor.warm).isFalse()
    }

    @Test
    fun `plain decor means nothing at all is applied`() {
        assertThat(PhotoDecor.PLAIN.isPlain).isTrue()
        assertThat(PhotoDecor().isPlain).isFalse()
        assertThat(PhotoDecor.PLAIN.withStamp(PhotoStamp.WHEAT).isPlain).isFalse()
        assertThat(PhotoDecor.PLAIN.toggleWarm().isPlain).isFalse()
    }

    @Test
    fun `the book offers at least three paper frames besides the bare photo`() {
        assertThat(PhotoFrame.entries.filter { it != PhotoFrame.NONE }).hasSize(3)
    }

    @Test
    fun `there are exactly three stamps and four corners to put one in`() {
        assertThat(PhotoStamp.entries).hasSize(3)
        assertThat(StampCorner.entries).hasSize(4)
    }

    @Test
    fun `every option carries a human label — nothing renders as an empty chip`() {
        val labels = PhotoFrame.entries.map { it.label } +
            PhotoStamp.entries.map { it.label } +
            StampCorner.entries.map { it.label }
        labels.forEach { assertThat(it).isNotEmpty() }
    }
}
