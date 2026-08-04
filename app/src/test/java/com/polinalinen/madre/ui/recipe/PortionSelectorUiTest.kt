package com.polinalinen.madre.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.model.RecipeScale
import com.polinalinen.madre.ui.screens.PortionSelector
import com.polinalinen.madre.ui.screens.portionLabel
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14: «на сколько печём» — вслух.
 *
 * Ячейка подписана «×2», и экранный диктор читает её как «умножить на два» —
 * при том что выбор порций меняет все граммы рецепта разом. Здесь проверяется,
 * что у каждой ячейки есть человеческое имя и что она честно говорит, выбрана
 * ли она.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class PortionSelectorUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun open(portions: Int = 1, onSelect: (Int) -> Unit = {}) {
        rule.setContent {
            MadreTheme { PortionSelector(portions = portions, onSelect = onSelect) }
        }
    }

    @Test
    fun `every portion cell says what it means, in words`() {
        open()
        rule.onNodeWithContentDescription("печём на 1 семья").assertIsDisplayed()
        rule.onNodeWithContentDescription("печём на 3 семьи").assertIsDisplayed()
        rule.onNodeWithContentDescription("печём на 5 семей").assertIsDisplayed()
    }

    @Test
    fun `the chosen portion announces that it is chosen`() {
        open(portions = 3)
        rule.onNodeWithContentDescription(portionLabel(3)).assertIsSelected()
    }

    @Test
    fun `tapping a cell by its spoken name chooses that many portions`() {
        var chosen = 0
        open(portions = 1, onSelect = { chosen = it })
        rule.onNodeWithContentDescription(portionLabel(4)).performClick()
        assertThat(chosen).isEqualTo(4)
    }

    @Test
    fun `every portion the shelf allows has a name of its own`() {
        open()
        (RecipeScale.MIN_PORTIONS..RecipeScale.MAX_PORTIONS).forEach { n ->
            rule.onNodeWithContentDescription(portionLabel(n)).assertIsDisplayed()
        }
    }
}
