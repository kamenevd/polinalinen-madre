package com.polinalinen.madre.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingStorageTapCycleUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `storage row cycles kitchen and cold`() {
        rule.setContent {
            MadreTheme {
                FeedingFormScreen(
                    onSave = { _, _, _, _, _, _ -> },
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("Где стоит закваска").performScrollTo().assertExists()
        rule.onNodeWithText("Кухня").performScrollTo().performClick()
        rule.onNodeWithText("Холод").assertExists()
        rule.onNodeWithText("Холод").performClick()
        rule.onNodeWithText("Кухня").assertExists()
    }

    @Test
    fun `storage row is one button with click label and finger target`() {
        rule.setContent {
            MadreTheme {
                FeedingFormScreen(
                    onSave = { _, _, _, _, _, _ -> },
                    onBack = {},
                )
            }
        }

        val node = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Где стоит закваска: Кухня" }

        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        val heightDp = node.size.height / rule.density.density
        assertWithMessage("storage cycle height, dp").that(heightDp).isAtLeast(48f)
    }
}
