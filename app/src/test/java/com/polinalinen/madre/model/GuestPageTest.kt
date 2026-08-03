package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.remote.GuestNoteRecord
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 7, фича «Гостевая страница» (GuestPage): сборка отзывов
 * гостей из записей PocketBase — подпись, детерминированная «рука» гостя
 * (наклон и чернила от имени) и адрес публичной формы для QR.
 */
class GuestPageTest {

    private fun record(author: String = "Оля", text: String = "хлеб — чудо") =
        GuestNoteRecord(recipeId = "r1", author = author, text = text, createdAt = "2026-07-25 10:00:00.000Z")

    @Test
    fun `records become notes with an author signature`() {
        val notes = GuestPage.from(listOf(record()))
        assertThat(notes).hasSize(1)
        assertThat(notes[0].text).isEqualTo("хлеб — чудо")
        assertThat(notes[0].authorLabel).isEqualTo("Оля · гость книги")
    }

    @Test
    fun `blank reviews are not glued into the book`() {
        val notes = GuestPage.from(listOf(record(text = "   "), record(text = "вкусно")))
        assertThat(notes).hasSize(1)
        assertThat(notes[0].text).isEqualTo("вкусно")
    }

    @Test
    fun `a nameless guest still gets a dignified signature`() {
        assertThat(GuestPage.authorLabel("")).isEqualTo("гость книги")
        assertThat(GuestPage.authorLabel("  ")).isEqualTo("гость книги")
        assertThat(GuestPage.authorLabel(" Ваня ")).isEqualTo("Ваня · гость книги")
    }

    @Test
    fun `each guest writes with the same hand every time`() {
        assertThat(GuestPage.slantFor("Оля")).isEqualTo(GuestPage.slantFor("Оля"))
        assertThat(GuestPage.inkFor("Оля")).isEqualTo(GuestPage.inkFor("Оля"))
    }

    @Test
    fun `guest hands stay within readable bounds`() {
        listOf("Оля", "Ваня", "бабушка", "", "гость с длинным именем").forEach { name ->
            val slant = GuestPage.slantFor(name)
            assertThat(kotlin.math.abs(slant)).isAtLeast(0.8f)
            assertThat(kotlin.math.abs(slant)).isAtMost(2.4f)
            assertThat(GuestPage.inkFor(name)).isAtLeast(0)
            assertThat(GuestPage.inkFor(name)).isAtMost(1)
        }
    }

    @Test
    fun `guest url points at the pocketbase form and survives cyrillic`() {
        val url = GuestPage.guestUrl("https://madre-api.kdnfx.space", "pane-bianco", "Пане бьянко")
        assertThat(url).startsWith("https://madre-api.kdnfx.space/guest.html?recipe=pane-bianco&name=")
        // Кириллица и пробел не должны уехать в query сырыми.
        assertThat(url).doesNotContain("Пане")
        assertThat(url).doesNotContain(" ")
    }

    @Test
    fun `guest url does not double the slash after base`() {
        val url = GuestPage.guestUrl("https://madre-api.kdnfx.space/", "r1", "Хлеб")
        assertThat(url).contains("kdnfx.space/guest.html")
        assertThat(url).doesNotContain("kdnfx.space//")
    }
}
