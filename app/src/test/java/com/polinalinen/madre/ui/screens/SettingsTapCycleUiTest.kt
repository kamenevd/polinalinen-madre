package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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
class SettingsTapCycleUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `feeding rhythm rotates without dialog`() {
        rule.setContent {
            var interval by mutableIntStateOf(24)
            MadreTheme {
                SettingsScreen(
                    myName = "Полина",
                    onMyNameChange = {},
                    onBack = {},
                    starterName = "Соня",
                    intervalHours = interval,
                    onIntervalHoursChange = { interval = it },
                )
            }
        }

        rule.onNodeWithText("Ваш ритм: раз в сутки").performClick()
        rule.onNodeWithText("Ваш ритм: раз в два дня").assertExists()
        rule.onAllNodesWithText("Оставить как есть").assertCountEquals(0)
    }

    @Test
    fun `look cycles by one tap`() {
        rule.setContent {
            var calm by mutableStateOf(true)
            MadreTheme {
                SettingsScreen(
                    myName = "Полина",
                    onMyNameChange = {},
                    onBack = {},
                    starterName = "Соня",
                    calmMode = calm,
                    onCalmModeChange = { calm = it },
                )
            }
        }

        val clickables = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
        val targetIndex = clickables.fetchSemanticsNodes().indexOfFirst {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Оформление: спокойное"
        }
        assertThat(targetIndex).isAtLeast(0)
        clickables[targetIndex].performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("живое").assertExists()
    }

    @Test
    fun `reminders row either cycles or stays intentionally fixed when denied by phone`() {
        rule.setContent {
            var reminders by mutableStateOf(true)
            MadreTheme {
                SettingsScreen(
                    myName = "Полина",
                    onMyNameChange = {},
                    onBack = {},
                    starterName = "Соня",
                    remindersEnabled = reminders,
                    onRemindersEnabledChange = { reminders = it },
                )
            }
        }

        val deniedNodes = rule.onAllNodesWithText("не разрешены телефоном").fetchSemanticsNodes()
        if (deniedNodes.isNotEmpty()) {
            val labels = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.label }
            assertThat(labels.any { it.startsWith("Напоминания:") }).isFalse()
        } else {
            rule.onNodeWithText("вкл").performClick()
            rule.onNodeWithText("выкл").assertExists()
        }
    }
}
