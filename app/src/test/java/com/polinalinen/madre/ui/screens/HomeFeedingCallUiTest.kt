package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.notifications.FeedingSchedule
import com.polinalinen.madre.sourdough.GrowthPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 18: покормить закваску — один тап с первой полосы.
 *
 * До этого дорога была на три экрана: первая полоса → дневник → форма
 * кормления. Кормление — то, что делают каждый день, и оно стоит первым
 * действием страницы, сразу под строкой от Мадре.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class HomeFeedingCallUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val recipes by lazy { frontPageRecipes() }

    private fun awaitTableOfContents() {
        rule.awaitOnPage("оглавление") {
            recipes.isNotEmpty() &&
                rule.onAllNodesWithTag(Home.chapterRowTag(recipes.first().id))
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `feeding a hungry starter takes one tap`() {
        var openedFeeding = 0
        rule.showFrontPage(phase = GrowthPhase.HUNGRY, onOpenFeeding = { openedFeeding++ })
        awaitTableOfContents()

        rule.onNodeWithText("Покормить Мадре").performClick()
        assertThat(openedFeeding).isEqualTo(1)
    }

    /**
     * До личного срока кормление не предлагается: книга показывает, когда
     * будет следующая попытка, когда кормили последний раз, и какая
     * гидратация подтверждена, но кнопку не рисует.
     */
    @Test
    fun `a starter fed before the due time shows status but no feed button`() {
        var openedFeeding = 0
        val nowMillis = 1_700_000_000_000L
        val threeHoursAgo = nowMillis - 3 * 3_600_000L
        val expectedLastFeeding = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(threeHoursAgo))
        rule.showFrontPage(
            phase = GrowthPhase.GROWING,
            lastFeedingMillis = threeHoursAgo,
            latestHydrationPercent = 80,
            nowMillis = nowMillis,
            onOpenFeeding = { openedFeeding++ },
        )
        awaitTableOfContents()

        // Cycle 26: точный местный срок вместо «примерно через 12–24 часа» —
        // время последней записи плюс интервал из настроек, ни минутой иначе.
        rule.onNodeWithText(FeedingSchedule.nextFeedingLabel(threeHoursAgo + 24 * 3_600_000L))
            .assertIsDisplayed()
        rule.onNodeWithText("Последнее: $expectedLastFeeding").assertIsDisplayed()
        rule.onNodeWithText("Гидратация закваски: 80%").assertIsDisplayed()
        rule.onAllNodesWithText("Покормить Мадре").assertCountEquals(0)
        assertThat(openedFeeding).isEqualTo(0)
    }

    /** Пустой дневник ничего не сообщает о прошлом кормлении — его не было. */
    @Test
    fun `an untouched diary does not invent a last feeding`() {
        rule.showFrontPage(phase = GrowthPhase.EMPTY, lastFeedingMillis = null)
        awaitTableOfContents()

        rule.onNodeWithText("Покормить Мадре").assertIsDisplayed()
        assertThat(
            rule.onAllNodesWithText("кормили", substring = true).fetchSemanticsNodes()
        ).isEmpty()
    }

    @Test
    fun `a due feeding shows the action`() {
        val now = 1_700_000_000_000L
        rule.showFrontPage(
            phase = GrowthPhase.HUNGRY,
            lastFeedingMillis = now - 24 * 3_600_000L,
            intervalHours = 24,
            nowMillis = now,
        )
        awaitTableOfContents()

        rule.onNodeWithText("Кормление уже по вашему расписанию").assertIsDisplayed()
        rule.onNodeWithText("Покормить Мадре").assertIsDisplayed()
    }

    @Test
    fun `an overdue feeding still shows action and phrase`() {
        val now = 1_700_000_000_000L
        rule.showFrontPage(
            phase = GrowthPhase.HUNGRY,
            lastFeedingMillis = now - 26 * 3_600_000L,
            intervalHours = 24,
            nowMillis = now,
        )
        awaitTableOfContents()

        rule.onNodeWithText("Кормление уже по вашему расписанию").assertIsDisplayed()
        rule.onNodeWithText("Покормить Мадре").assertIsDisplayed()
    }

    @Test
    fun `invalid interval is honest and offers no action`() {
        rule.showFrontPage(
            phase = GrowthPhase.EMPTY,
            lastFeedingMillis = 1_000L,
            intervalHours = 0,
            nowMillis = 2_000L,
        )
        awaitTableOfContents()

        rule.onNodeWithText("Интервал кормления не задан корректно — проверьте настройки")
            .assertIsDisplayed()
        rule.onAllNodesWithText("Покормить Мадре").assertCountEquals(0)
    }

    @Test
    fun `clock before last feeding is honest and offers no action`() {
        rule.showFrontPage(
            phase = GrowthPhase.EMPTY,
            lastFeedingMillis = 2_000L,
            nowMillis = 1_999L,
        )
        awaitTableOfContents()

        rule.onNodeWithText("Время телефона раньше последнего кормления — проверьте часы")
            .assertIsDisplayed()
        rule.onAllNodesWithText("Покормить Мадре").assertCountEquals(0)
    }
}
