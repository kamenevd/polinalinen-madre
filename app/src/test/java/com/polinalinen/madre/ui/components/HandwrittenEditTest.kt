package com.polinalinen.madre.ui.components

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 4, фича «Правка от руки» (HandwrittenEdit): файл правок
 * привязан к recipeId через имя в internal storage. Имя — чистая функция:
 * детерминированная, безопасная для файловой системы, разные рецепты не
 * перезаписывают правки друг друга.
 *
 * Cycle 11: правки стали вектором, и вся механика «шаг назад / шаг вперёд»
 * плюс формат хранения — тоже чистые функции, проверяются здесь без Android.
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

    @Test
    fun `vector history lives next to the legacy bitmap but in its own file`() {
        assertThat(HandwrittenEdit.strokesFileNameFor("focaccia")).endsWith(".strokes")
        assertThat(HandwrittenEdit.strokesFileNameFor("focaccia"))
            .isNotEqualTo(HandwrittenEdit.fileNameFor("focaccia"))
    }

    @Test
    fun `strokes file name is sanitised the same way as the bitmap one`() {
        val name = HandwrittenEdit.strokesFileNameFor("../../etc/passwd")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("..")
    }

    @Test
    fun `fresh history offers neither step back nor step forward`() {
        val history = HandwrittenEdit.StrokeHistory()
        assertThat(history.canUndo).isFalse()
        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `undo removes exactly the last finished stroke`() {
        val history = HandwrittenEdit.StrokeHistory().add(STROKE_A).add(STROKE_B).undo()
        assertThat(history.strokes).containsExactly(STROKE_A)
        assertThat(history.canRedo).isTrue()
    }

    @Test
    fun `undo can walk back past the very first stroke`() {
        val history = HandwrittenEdit.StrokeHistory().add(STROKE_A).add(STROKE_B).undo().undo()
        assertThat(history.strokes).isEmpty()
        assertThat(history.canUndo).isFalse()
    }

    @Test
    fun `undo on an empty page changes nothing instead of throwing`() {
        val history = HandwrittenEdit.StrokeHistory().undo()
        assertThat(history.strokes).isEmpty()
        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `redo brings back the stroke that was taken off`() {
        val history = HandwrittenEdit.StrokeHistory().add(STROKE_A).undo().redo()
        assertThat(history.strokes).containsExactly(STROKE_A)
        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `redo without anything undone changes nothing`() {
        val history = HandwrittenEdit.StrokeHistory().add(STROKE_A)
        assertThat(history.redo()).isEqualTo(history)
    }

    @Test
    fun `a new stroke after undo drops the redo branch`() {
        val history = HandwrittenEdit.StrokeHistory().add(STROKE_A).add(STROKE_B).undo().add(STROKE_C)
        assertThat(history.strokes).containsExactly(STROKE_A, STROKE_C).inOrder()
        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `an empty stroke is never written into history`() {
        assertThat(HandwrittenEdit.StrokeHistory().add(emptyList()).strokes).isEmpty()
    }

    @Test
    fun `history survives a round trip through the saved format`() {
        val strokes = listOf(STROKE_A, STROKE_B, STROKE_C)
        val restored = HandwrittenEdit.decode(HandwrittenEdit.encode(strokes))
        assertThat(restored).hasSize(strokes.size)
        restored.forEachIndexed { i, stroke ->
            assertThat(stroke).hasSize(strokes[i].size)
            stroke.forEachIndexed { j, point ->
                assertThat(point.x).isWithin(0.001f).of(strokes[i][j].x)
                assertThat(point.y).isWithin(0.001f).of(strokes[i][j].y)
            }
        }
    }

    @Test
    fun `an empty page encodes to nothing and decodes back to nothing`() {
        assertThat(HandwrittenEdit.encode(emptyList())).isEmpty()
        assertThat(HandwrittenEdit.decode("")).isEmpty()
    }

    @Test
    fun `a damaged edits file loses the broken lines, not the whole page`() {
        val raw = HandwrittenEdit.encode(listOf(STROKE_A)) + "\nnot-a-stroke\n1234\n"
        assertThat(HandwrittenEdit.decode(raw)).hasSize(1)
    }

    private companion object {
        val STROKE_A = listOf(Offset(0.1f, 0.2f), Offset(0.3f, 0.4f))
        val STROKE_B = listOf(Offset(0.5f, 0.5f), Offset(0.6f, 0.55f), Offset(0.7f, 0.6f))
        val STROKE_C = listOf(Offset(0f, 1f))
    }
}
