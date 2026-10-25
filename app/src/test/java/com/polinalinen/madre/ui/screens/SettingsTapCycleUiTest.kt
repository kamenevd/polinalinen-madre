package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.RazvorotTheme as MadreTheme
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
    fun `feeding rhythm directly selects the requested interval without a dialog`() {
        rule.setContent {
            var interval by remember { mutableIntStateOf(24) }
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

        rule.onNodeWithText("Закваска").performClick()
        rule.onNodeWithText("неделя").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText("2 дня").performScrollTo().performClick().assertIsSelected()
        rule.onAllNodesWithText("Оставить как есть").assertCountEquals(0)
    }

    @Test
    fun `calm mode toggles through its real switch`() {
        rule.setContent {
            var calm by remember { mutableStateOf(true) }
            MadreTheme {
                SettingsScreen(
                    myName = "Полина",
                    onMyNameChange = {},
                    onBack = {},
                    starterName = "Соня",
                    readingPreferences = remember { ReadingPreferences(androidx.test.core.app.ApplicationProvider.getApplicationContext(),"tap-reduced").apply{setReduced(true)} },
                    calmMode = calm,
                    onCalmModeChange = { calm = it },
                )
            }
        }

        rule.onNodeWithText("Внешний вид").performScrollTo().performClick()
        val control = rule.onNodeWithContentDescription("Меньше движения")
        control.assertIsOn().performScrollTo().performClick()
        rule.waitForIdle()
        control.assertIsOff()
        rule.onNodeWithText("Спокойный режим").assertDoesNotExist()
    }

    @Test
    fun `reminders switch is actionable only when the phone allows notifications`() {
        rule.setContent {
            var reminders by remember { mutableStateOf(true) }
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

        rule.onNodeWithText("Уведомления и вибрация").performClick()
        val deniedNodes = rule.onAllNodesWithText("Уведомления выключены в телефоне").fetchSemanticsNodes()
        if (deniedNodes.isNotEmpty()) {
            val labels = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.label }
            assertThat(labels.any { it == "Напоминать о закваске" || it.startsWith("Напоминать о закваске:") }).isFalse()
            rule.onNodeWithContentDescription("Напоминать о закваске").assertDoesNotExist()
        } else {
            val control = rule.onNodeWithContentDescription("Напоминать о закваске")
            control.assertIsOn().performScrollTo().performClick().assertIsOff()
        }
    }
}
