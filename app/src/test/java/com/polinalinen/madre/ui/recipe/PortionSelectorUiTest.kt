package com.polinalinen.madre.ui.recipe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.ui.screens.PortionSelector
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14: «на сколько печём» — вслух.
 *
 * Cycle 28: вместо рамки из пяти ячеек здесь одна строка-крутилка.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class PortionSelectorUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun open(initial: Int = 1) {
        rule.setContent {
            var portions by mutableIntStateOf(initial)
            MadreTheme { PortionSelector(portions = portions, onSelect = { portions = it }) }
        }
    }

    @Test
    fun `current value is named with words`() {
        open()
        rule.onNodeWithText("×1 семья").assertIsDisplayed()
    }

    @Test
    fun `tap cycles portions and wraps back to one`() {
        open(initial = 4)
        rule.onNodeWithText("×4 семьи").performClick()
        rule.onNodeWithText("×5 семей").assertIsDisplayed()
        rule.onNodeWithText("×5 семей").performClick()
        rule.onNodeWithText("×1 семья").assertIsDisplayed()
    }

    @Test
    fun `selector stays one button with click label`() {
        open()
        val node = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.config.getOrNull(SemanticsActions.OnClick)?.label == "На сколько печём: ×1 семья" }
        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
    }
}
