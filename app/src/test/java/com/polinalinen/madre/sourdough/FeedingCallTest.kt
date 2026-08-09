package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 18: кнопка «Покормить» на первой полосе. Что она говорит и насколько
 * громко — считается здесь, без Compose: «главное действие страницы» и «уже
 * покормили, вот когда» — это про закваску, а не про раскладку.
 */
class FeedingCallTest {

    @Test
    fun `a hungry starter asks out loud`() {
        assertThat(FeedingCall.isFresh(GrowthPhase.HUNGRY)).isFalse()
        assertThat(FeedingCall.isFresh(GrowthPhase.DECLINING)).isFalse()
    }

    /**
     * Пока закваска растёт и стоит на пике, кормить её рано: свежее кормление
     * сбрасывает поднявшуюся культуру, и предлагать это главным действием
     * страницы — плохой совет.
     */
    @Test
    fun `a rising starter has been fed recently enough`() {
        assertThat(FeedingCall.isFresh(GrowthPhase.LAG)).isTrue()
        assertThat(FeedingCall.isFresh(GrowthPhase.GROWING)).isTrue()
        assertThat(FeedingCall.isFresh(GrowthPhase.PEAK)).isTrue()
    }

    /**
     * Пустой дневник — не «недавно кормили». Первое кормление тоже кормление,
     * и звать на него надо в полный голос.
     */
    @Test
    fun `an empty diary is not a recent feeding`() {
        assertThat(FeedingCall.isFresh(GrowthPhase.EMPTY)).isFalse()
    }

    /**
     * «Покормить» требует винительного падежа, а склонять чужое имя книге
     * нечем — та же причина, по которой [StarterName.feedingPhotoCaption]
     * ставит точку вместо падежа, а [StarterName.hungryTitle] обходится без
     * «проголодалась».
     *
     * Имя книги несклоняемое, и с ним кнопка зовёт закваску по имени. Всякое
     * другое — «Покормить Борис» или угаданное «Покормить Бориса» — она
     * писать не станет и честно скажет «закваску».
     */
    @Test
    fun `the button calls the starter by name when it can`() {
        assertThat(FeedingCall.label("Мадре")).isEqualTo("Покормить Мадре")
        assertThat(FeedingCall.label("  мадре ")).isEqualTo("Покормить Мадре")
        assertThat(FeedingCall.label("")).isEqualTo("Покормить Мадре")
    }

    @Test
    fun `a name it cannot decline is not mangled`() {
        assertThat(FeedingCall.label("Борис")).isEqualTo("Покормить закваску")
        assertThat(FeedingCall.label("Соня")).isEqualTo("Покормить закваску")
    }

    @Test
    fun `a fresh starter says when it was fed`() {
        assertThat(FeedingCall.sinceLabel(3f)).isEqualTo("кормили 3ч назад")
        assertThat(FeedingCall.sinceLabel(0.5f)).isEqualTo("кормили 30м назад")
    }

    /** Времени кормления может не быть вовсе — дневник только заводят. */
    @Test
    fun `an empty diary has nothing to say about when`() {
        assertThat(FeedingCall.sinceLabel(null)).isNull()
    }
}
