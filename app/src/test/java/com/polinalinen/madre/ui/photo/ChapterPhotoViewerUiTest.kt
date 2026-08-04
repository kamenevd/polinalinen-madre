package com.polinalinen.madre.ui.photo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.model.ChapterPhoto
import com.polinalinen.madre.ui.components.ChapterPhotoViewer
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14: fullscreen-просмотр фотографий ОДНОЙ главы.
 *
 * Проверяется то, что нельзя увидеть глазами в сборочной среде без эмулятора:
 * viewer открывается на последнем снимке, листает ровно свою главу, упирается
 * в её границы, честно говорит про пропавший файл и закрывается.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class ChapterPhotoViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(id: Long, takenAt: Long) = ChapterPhoto(
        recordId = id,
        path = "/nowhere/$id.jpg",
        takenAtMillis = takenAt,
        recipeName = "Бородинский",
    )

    /** Снимки приходят уже отсортированными: сначала самый свежий. */
    private val chapter = listOf(
        photo(3, 1_700_000_300_000L),
        photo(2, 1_700_000_200_000L),
        photo(1, 1_700_000_100_000L),
    )

    private fun open(photos: List<ChapterPhoto> = chapter, onDismiss: () -> Unit = {}) {
        rule.setContent {
            MadreTheme {
                ChapterPhotoViewer(photos = photos, chapterName = "Бородинский", onDismiss = onDismiss)
            }
        }
    }

    @Test
    fun `the viewer opens on the newest photo of the chapter`() {
        open()
        rule.onNodeWithText("1 из 3").assertIsDisplayed()
    }

    @Test
    fun `swiping walks along this chapter and only this one`() {
        open()
        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("2 из 3").assertIsDisplayed()

        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithText("1 из 3").assertIsDisplayed()
    }

    /** За первым снимком главы ничего нет — листать назад некуда. */
    @Test
    fun `the newest photo is the near edge, and it holds`() {
        open()
        repeat(2) {
            rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).performTouchInput { swipeRight() }
            rule.waitForIdle()
        }
        rule.onNodeWithText("1 из 3").assertIsDisplayed()
    }

    @Test
    fun `the oldest photo is the far edge, and it holds too`() {
        open()
        repeat(5) {
            rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).performTouchInput { swipeLeft() }
            rule.waitForIdle()
        }
        rule.onNodeWithText("3 из 3").assertIsDisplayed()
    }

    /**
     * Файл могли удалить из галереи телефона, а запись в книге осталась. Пустая
     * чёрная страница здесь была бы неотличима от «приложение сломалось».
     */
    @Test
    fun `a photo whose file is gone says so instead of showing nothing`() {
        open()
        rule.onNodeWithText("файла больше нет").assertIsDisplayed()
    }

    @Test
    fun `every page announces itself to a screen reader`() {
        open()
        rule.onNodeWithContentDescription("Бородинский", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a single photo chapter does not count itself out loud`() {
        open(photos = listOf(photo(1, 1_700_000_100_000L)))
        rule.onNodeWithText("1 из 1").assertDoesNotExist()
    }

    @Test
    fun `closing is one tap away`() {
        var dismissed = false
        open(onDismiss = { dismissed = true })
        rule.onNodeWithText("Закрыть").performClick()
        assertThat(dismissed).isTrue()
    }
}
