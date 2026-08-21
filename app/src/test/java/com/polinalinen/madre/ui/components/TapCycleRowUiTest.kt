package com.polinalinen.madre.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class TapCycleRowUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `four taps perform four steps without gate swallowing`() {
        val options = listOf("один", "два", "три", "четыре")
        rule.setContent {
            var current by remember { mutableStateOf(options.first()) }
            MadreTheme {
                TapCycleRow(
                    label = "Круг",
                    value = current,
                    onClick = { current = TapCycle.next(options, current) },
                )
            }
        }

        repeat(4) { rule.onNodeWithText("Круг").performClick() }
        rule.onNodeWithText("один").assertExists()
        rule.onAllNodesWithText("Оставить как есть").assertCountEquals(0)
    }

    @Test
    fun `tap cycle row is a button with click label and finger target`() {
        rule.setContent {
            MadreTheme {
                TapCycleRow(label = "Оформление", value = "спокойное", onClick = {})
            }
        }
        val node = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Оформление: спокойное" }

        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        val heightDp = node.size.height / rule.density.density
        assertWithMessage("tap cycle height, dp").that(heightDp).isAtLeast(48f)
    }
}
