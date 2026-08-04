package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 14: закваску зовут так, как её назвали в семье, — и одинаково везде.
 *
 * Имя приходит из поля ввода, то есть может быть каким угодно: пустым, из
 * одних пробелов, в три строки, длиной в абзац. Все эти случаи обязаны
 * приводиться к одному виду ЗДЕСЬ, а не в каждом экране по-своему, иначе
 * дневник, колофон и напоминание разойдутся в написании одного имени.
 */
class StarterNameTest {

    @Test
    fun `a plain name passes through untouched`() {
        assertThat(StarterName.sanitize("Соня")).isEqualTo("Соня")
    }

    @Test
    fun `stray spaces around the name are not part of it`() {
        assertThat(StarterName.sanitize("  Соня  ")).isEqualTo("Соня")
    }

    @Test
    fun `a name is one line, however it was pasted`() {
        assertThat(StarterName.sanitize("Левито\nМадре")).isEqualTo("Левито Мадре")
        assertThat(StarterName.sanitize("Левито\t \tМадре")).isEqualTo("Левито Мадре")
    }

    @Test
    fun `an empty name falls back to the name she was born with`() {
        assertThat(StarterName.sanitize("")).isEqualTo(StarterName.DEFAULT)
        assertThat(StarterName.sanitize("   ")).isEqualTo(StarterName.DEFAULT)
        assertThat(StarterName.sanitize("\n\t")).isEqualTo(StarterName.DEFAULT)
    }

    /** Имя едет в заголовок уведомления и в строку колофона — абзац туда не влезет. */
    @Test
    fun `a very long name is cut to something a line can hold`() {
        val long = "Закваска которую мы завели прошлой осенью на ржаной обдирной муке"
        val short = StarterName.sanitize(long)
        assertThat(short.length).isAtMost(StarterName.MAX_LENGTH)
        assertThat(short).isEqualTo(long.take(StarterName.MAX_LENGTH).trim())
    }

    @Test
    fun `cutting a long name never leaves a trailing space`() {
        // Обрез приходится ровно на пробел — «Мадре ...» с хвостом не сохраняем.
        val cut = StarterName.sanitize("Мадре из Болоньи двадцать первого года")
        assertThat(cut).isEqualTo(cut.trim())
        assertThat(cut).doesNotContain("  ")
    }

    @Test
    fun `the default name is what the book has always called her`() {
        assertThat(StarterName.DEFAULT).isEqualTo("Мадре")
    }

    /**
     * Одно имя — одни и те же тексты. Дневник, полка фотографий и напоминание
     * берут строки отсюда, поэтому переименование доезжает во все три сразу.
     */
    @Test
    fun `the diary, the gallery and the reminder all speak the same name`() {
        val name = "Соня"
        assertThat(StarterName.diaryTitle(name)).isEqualTo("Соня пишет:")
        assertThat(StarterName.homeLabel(name)).isEqualTo("Соня пишет")
        assertThat(StarterName.feedingPhotoCaption(name)).isEqualTo("Кормление · Соня")
        assertThat(StarterName.hungryTitle(name)).isEqualTo("Соня: пора кормить")
    }

    @Test
    fun `every phrase sanitises its input, so no screen can print a raw blank`() {
        listOf(
            StarterName.diaryTitle("  "),
            StarterName.homeLabel(""),
            StarterName.feedingPhotoCaption("\n"),
            StarterName.hungryTitle("   "),
        ).forEach { phrase -> assertThat(phrase).contains(StarterName.DEFAULT) }
    }

    /**
     * Напоминание не склоняет имя по родам: «Борис проголодалась» — не то, что
     * книга должна написать человеку в шторку.
     */
    @Test
    fun `the reminder never guesses the gender of a name`() {
        assertThat(StarterName.hungryTitle("Борис")).isEqualTo("Борис: пора кормить")
    }
}
