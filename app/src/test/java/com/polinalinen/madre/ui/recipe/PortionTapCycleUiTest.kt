package com.polinalinen.madre.ui.recipe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.ui.screens.PortionSelector
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class PortionTapCycleUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `portion selector runs one-two-three-four-five-one`() {
        rule.setContent {
            var portions by mutableIntStateOf(1)
            MadreTheme {
                PortionSelector(portions = portions, onSelect = { portions = it })
            }
        }

        rule.onNodeWithText("×1 семья").performClick()
        rule.onNodeWithText("×2 семьи").assertIsDisplayed()
        rule.onNodeWithText("×2 семьи").performClick()
        rule.onNodeWithText("×3 семьи").assertIsDisplayed()
        rule.onNodeWithText("×3 семьи").performClick()
        rule.onNodeWithText("×4 семьи").assertIsDisplayed()
        rule.onNodeWithText("×4 семьи").performClick()
        rule.onNodeWithText("×5 семей").assertIsDisplayed()
        rule.onNodeWithText("×5 семей").performClick()
        rule.onNodeWithText("×1 семья").assertIsDisplayed()
    }
}
