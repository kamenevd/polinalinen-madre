package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.model.MonthRhythm
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
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
 * экран, а глава без снимка не притворяется, что он у неё есть.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class BookStatsScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val thisMonth: YearMonth = YearMonth.now()

    private fun recipe(id: String, name: String) = Recipe(
        id = id, name = name, emoji = "", description = "",
        ingredients = emptyMap(), timeline = emptyList(),
    )

    private fun millisOn(date: LocalDate): Long =
        date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun record(id: Long, recipeId: String, name: String, photoPath: String?) = BakeRecordEntity(
        id = id,
        recipeId = recipeId,
        recipeName = name,
        portions = 1,
        completedAtMillis = millisOn(thisMonth.atDay(1)),
        photoPath = photoPath,
    )

    /** Полка длинная: до плитки главы надо доехать, иначе её и в композиции нет. */
    private fun tapChapter(recipeId: String) {
        val tile = BookStats.chapterTileTag(recipeId)
        rule.onNodeWithTag(BookStats.LIST_TAG).performScrollToNode(hasTestTag(tile))
        rule.onNodeWithTag(tile).performClick()
        rule.waitForIdle()
    }

    private fun open(records: List<BakeRecordEntity>) {
        rule.setContent {
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

    /**
     * Глава со снимком открывается во весь экран. Файла на диске нет — значит
     * viewer обязан сказать об этом, а не показать пустую страницу.
     */
    @Test
    fun `tapping a chapter with photos opens it fullscreen`() {
        open(listOf(record(1, "bread", "Бородинский", photoPath = "/nowhere/1.jpg")))
        tapChapter("bread")
        rule.onNodeWithText("файла больше нет").assertIsDisplayed()
    }

    /** Глава без снимка не притворяется, что он у неё есть. */
    @Test
    fun `a chapter baked without a camera falls back to its list of attempts`() {
        open(listOf(record(1, "bread", "Бородинский", photoPath = null)))
        tapChapter("bread")
        rule.onNodeWithText("файла больше нет").assertDoesNotExist()
        rule.onNodeWithText("Закрыть").assertIsDisplayed()
    }

    @Test
    fun `a chapter never baked cannot be opened at all`() {
        open(listOf(record(1, "bread", "Бородинский", photoPath = "/nowhere/1.jpg")))
        tapChapter("focaccia")
        rule.onNodeWithText("файла больше нет").assertDoesNotExist()
        rule.onNodeWithText("Закрыть").assertDoesNotExist()
    }
}
