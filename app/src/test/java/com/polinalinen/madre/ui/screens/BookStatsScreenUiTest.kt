package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.model.MonthRhythm
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.components.ChapterPhotoViewer
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Cycle 14: Полка — календарь текущего месяца и главы с лицами.
 *
 * Проверяется проводка, а не арифметика (она в MonthRhythmTest/ChapterPhotosTest):
 * календарь показывает ЭТОТ месяц целиком, глава со снимком открывается во весь
 * экран, глава без единого читаемого снимка уходит в список попыток, а открытая
 * глава переживает пересоздание активити.
 *
 * Файлы здесь настоящие (TemporaryFolder): вся суть отбора снимков в том, что
 * запись в книге и файл на диске — разные вещи, и подделанная проверка файла
 * проверяла бы сама себя.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class BookStatsScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val photos = TemporaryFolder()

    private val thisMonth: YearMonth = YearMonth.now()

    private fun recipe(id: String, name: String) = Recipe(
        id = id, name = name, emoji = "", description = "",
        ingredients = emptyMap(), timeline = emptyList(),
    )

    private fun millisOn(date: LocalDate): Long =
        date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Настоящий файл на диске — такой же, как снимок, лежащий в галерее. */
    private fun realPhoto(name: String): String =
        photos.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath

    /** Путь, по которому файла нет: снимок удалили с телефона. */
    private fun deletedPhoto(name: String): String = photos.root.resolve(name).absolutePath

    private fun record(
        id: Long,
        recipeId: String,
        name: String,
        photoPath: String?,
        day: Int = 1,
    ) = BakeRecordEntity(
        id = id,
        recipeId = recipeId,
        recipeName = name,
        portions = 1,
        completedAtMillis = millisOn(thisMonth.atDay(day)),
        photoPath = photoPath,
    )

    /** Полка длинная: до плитки главы надо доехать, иначе её и в композиции нет. */
    private fun tapChapter(recipeId: String) {
        val tile = BookStats.chapterTileTag(recipeId)
        rule.onNodeWithTag(BookStats.LIST_TAG).performScrollToNode(hasTestTag(tile))
        rule.onNodeWithTag(tile).performClick()
        rule.waitForIdle()
    }

    private fun shelf(records: List<BakeRecordEntity>): @androidx.compose.runtime.Composable () -> Unit = {
        MadreTheme {
            BookStatsScreen(
                ownerLabel = "вы",
                isMe = true,
                recipes = listOf(recipe("bread", "Бородинский"), recipe("focaccia", "Фокачча")),
                records = records,
                onBack = {},
            )
        }
    }

    private fun open(records: List<BakeRecordEntity>) {
        rule.setContent { shelf(records)() }
    }

    @Test
    fun `the rhythm names the month it is showing`() {
        open(emptyList())
        rule.onNodeWithText(MonthRhythm.title(thisMonth)).assertIsDisplayed()
    }

    /** Календарь показывает месяц целиком — включая последнее его число. */
    @Test
    fun `the whole month is on the page, quiet days and all`() {
        open(emptyList())
        rule.onNodeWithText("${thisMonth.lengthOfMonth()}").assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun `an empty month says so instead of drawing a lie`() {
        open(emptyList())
        rule.onNodeWithText("в этом месяце здесь пока не пекли").assertIsDisplayed()
    }

    @Test
    fun `a month with bakes counts them under the calendar`() {
        open(listOf(record(1, "bread", "Бородинский", photoPath = null)))
        rule.onNodeWithText("в этом месяце: 1 выпечка").assertIsDisplayed()
    }

    @Test
    fun `tapping a chapter with photos opens it fullscreen`() {
        open(listOf(record(1, "bread", "Бородинский", photoPath = realPhoto("1.jpg"))))
        tapChapter("bread")
        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).assertIsDisplayed()
        rule.onNodeWithText("файла больше нет").assertDoesNotExist()
    }

    /** Глава без снимка не притворяется, что он у неё есть. */
    @Test
    fun `a chapter baked without a camera falls back to its list of attempts`() {
        open(listOf(record(1, "bread", "Бородинский", photoPath = null)))
        tapChapter("bread")
        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).assertDoesNotExist()
        rule.onNodeWithText("Закрыть").assertIsDisplayed()
    }

    @Test
    fun `a chapter never baked cannot be opened at all`() {
        open(listOf(record(1, "bread", "Бородинский", photoPath = realPhoto("1.jpg"))))
        tapChapter("focaccia")
        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).assertDoesNotExist()
        rule.onNodeWithText("Закрыть").assertDoesNotExist()
    }

    /**
     * Главное в этой правке: последний снимок удалили с телефона, а под ним
     * лежат целые. Глава открывается на ближайшей целой фотографии, и «файла
     * больше нет» человек не видит вовсе — ему есть что показать.
     */
    @Test
    fun `a chapter whose newest photo is gone opens on the newest one still there`() {
        open(
            listOf(
                record(1, "bread", "Бородинский", photoPath = realPhoto("old.jpg"), day = 1),
                record(2, "bread", "Бородинский", photoPath = deletedPhoto("gone.jpg"), day = 2),
            )
        )
        tapChapter("bread")
        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).assertIsDisplayed()
        rule.onNodeWithText("файла больше нет").assertDoesNotExist()
        // Целый снимок ровно один — считать его вслух незачем.
        rule.onNodeWithText("1 из 2").assertDoesNotExist()
    }

    /** Все снимки главы удалили — это глава без фотографий, а не пустой viewer. */
    @Test
    fun `a chapter whose photos were all deleted falls back to its list of attempts`() {
        open(
            listOf(
                record(1, "bread", "Бородинский", photoPath = deletedPhoto("gone-1.jpg"), day = 1),
                record(2, "bread", "Бородинский", photoPath = deletedPhoto("gone-2.jpg"), day = 2),
            )
        )
        tapChapter("bread")
        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).assertDoesNotExist()
        rule.onNodeWithText("файла больше нет").assertDoesNotExist()
        rule.onNodeWithText("Закрыть").assertIsDisplayed()
    }

    // --- пересоздание активити ---

    private fun threePhotoChapter() = listOf(
        record(1, "bread", "Бородинский", photoPath = realPhoto("1.jpg"), day = 1),
        record(2, "bread", "Бородинский", photoPath = realPhoto("2.jpg"), day = 2),
        record(3, "bread", "Бородинский", photoPath = realPhoto("3.jpg"), day = 3),
    )

    /**
     * Поворот экрана посреди просмотра — не повод захлопнуть книгу. До этой
     * правки открытая глава жила в remember и умирала вместе с активити:
     * человек возвращался на Полку и искал плитку заново.
     */
    @Test
    fun `an opened chapter survives the activity being recreated`() {
        // Файлы создаются один раз, до setContent: composable-лямбду Compose
        // зовёт на каждой рекомпозиции, и создавать в ней файлы нельзя.
        val records = threePhotoChapter()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { shelf(records)() }
        tapChapter("bread")
        rule.onNodeWithText("1 из 3").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).assertIsDisplayed()
        rule.onNodeWithText("1 из 3").assertIsDisplayed()
    }

    /** И страница остаётся той же: вернуться должны туда, где стояли. */
    @Test
    fun `the page being looked at survives the recreation too`() {
        val records = threePhotoChapter()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { shelf(records)() }
        tapChapter("bread")
        rule.onNodeWithTag(ChapterPhotoViewer.PAGER_TAG).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("2 из 3").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithText("2 из 3").assertIsDisplayed()
    }

    /** Список попыток тоже открыт человеком — и тоже не должен захлопываться. */
    @Test
    fun `an opened list of attempts survives the recreation`() {
        val restoration = StateRestorationTester(rule)
        val records = listOf(record(1, "bread", "Бородинский", photoPath = null))
        restoration.setContent { shelf(records)() }
        tapChapter("bread")
        rule.onNodeWithText("Закрыть").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithText("Закрыть").assertIsDisplayed()
    }
}
