package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.sourdough.GrowthPhase
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
     * Кнопка остаётся нажимаемой и после кормления — «недавно кормили» не
     * повод запрещать. Меняется голос: главным действием страницы это уже не
     * стоит, и книга честно говорит, когда кормили.
     */
    @Test
    fun `a freshly fed starter says when it was fed and still lets you feed it`() {
        var openedFeeding = 0
        val threeHoursAgo = System.currentTimeMillis() - 3 * 3_600_000L
        rule.showFrontPage(
            phase = GrowthPhase.GROWING,
            lastFeedingMillis = threeHoursAgo,
            onOpenFeeding = { openedFeeding++ },
        )
        awaitTableOfContents()

        rule.onNodeWithText("кормили 3ч назад").assertIsDisplayed()
        rule.onNodeWithText("Покормить Мадре").performClick()
        assertThat(openedFeeding).isEqualTo(1)
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
}
