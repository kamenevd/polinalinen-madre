package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 4, фича «Правка от руки» (HandwrittenEdit): bitmap
 * правок привязан к recipeId через имя файла в internal storage. Имя —
 * чистая функция: детерминированная, безопасная для файловой системы,
 * разные рецепты не перезаписывают правки друг друга.
 */
class HandwrittenEditTest {

    @Test
    fun `file name is deterministic for the same recipe`() {
        assertThat(HandwrittenEdit.fileNameFor("focaccia"))
            .isEqualTo(HandwrittenEdit.fileNameFor("focaccia"))
    }

    @Test
    fun `file name carries the handwritten prefix and png extension`() {
        val name = HandwrittenEdit.fileNameFor("focaccia")
        assertThat(name).startsWith("handwritten_")
        assertThat(name).endsWith(".png")
    }

    @Test
    fun `different recipes never share an edits file`() {
        assertThat(HandwrittenEdit.fileNameFor("focaccia"))
            .isNotEqualTo(HandwrittenEdit.fileNameFor("ciabatta"))
    }

    @Test
    fun `path separators and dots cannot escape the files dir`() {
        val name = HandwrittenEdit.fileNameFor("../../etc/passwd")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("\\")
        assertThat(name).doesNotContain("..")
    }

    @Test
    fun `letters and digits of the recipe id survive sanitising`() {
        assertThat(HandwrittenEdit.fileNameFor("recipe42")).isEqualTo("handwritten_recipe42.png")
    }

    @Test
    fun `ink stays translucent enough to keep the book text readable`() {
        assertThat(HandwrittenEdit.INK_ALPHA).isLessThan(1f)
        assertThat(HandwrittenEdit.INK_ALPHA).isAtLeast(0.5f)
    }
}
