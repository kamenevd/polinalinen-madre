package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 3, фича «Экслибрис» (Bookplate): имя семьи вписывается
 * один раз и остаётся навсегда — стоит убедиться, что мусор (пробелы по
 * краям, случайные переносы, разрастание в бесконечность) не долетает до Room.
 */
class BookplateNameTest {

    @Test
    fun `trims leading and trailing whitespace`() {
        assertThat(BookplateName.sanitize("  Ивановы  ")).isEqualTo("Ивановы")
    }

    @Test
    fun `collapses internal runs of whitespace into a single space`() {
        assertThat(BookplateName.sanitize("семья   Ивановых\n\nи  Петровых")).isEqualTo("семья Ивановых и Петровых")
    }

    @Test
    fun `blank input sanitizes to empty string`() {
        assertThat(BookplateName.sanitize("   \n\t  ")).isEmpty()
    }

    @Test
    fun `caps length at MAX_LENGTH`() {
        val long = "а".repeat(200)
        assertThat(BookplateName.sanitize(long).length).isEqualTo(BookplateName.MAX_LENGTH)
    }

    @Test
    fun `short names are left untouched`() {
        assertThat(BookplateName.sanitize("Мадре")).isEqualTo("Мадре")
    }
}
