package com.polinalinen.madre.settingsui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.ui.screens.StarterNameField
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14: поле имени закваски в колофоне.
 *
 * Тонкое место — спор между тем, что набирают, и тем, что уже сохранено.
 * Пустого имени книга не хранит, поэтому в базе после стирания мгновенно
 * оказывается «Мадре»; если поле начнёт показывать сохранённое значение, это
 * слово подставится прямо под палец на середине переименования.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class StarterNameFieldUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the field opens on the name the book already knows`() {
        rule.setContent { MadreTheme { StarterNameField(persisted = "Соня", onChange = {}) } }
        rule.onNodeWithText("Соня").assertIsDisplayed()
    }

    @Test
    fun `every keystroke reaches the book`() {
        val typed = mutableListOf<String>()
        rule.setContent { MadreTheme { StarterNameField(persisted = "", onChange = { typed += it }) } }
        // Пустое поле показывает подсказку отдельной строкой — набирать надо
        // в само поле ввода, а не в неё.
        rule.onNode(hasSetTextAction()).performTextInput("Соня")
        assertThat(typed.last()).isEqualTo("Соня")
    }

    /** Главное: стёртая строка остаётся стёртой, а не подставляет «Мадре». */
    @Test
    fun `clearing the field leaves it empty instead of filling it back in`() {
        var saved = "Соня"
        rule.setContent {
            MadreTheme {
                // Персистентное значение ведёт себя как настоящее: пустое имя
                // база не хранит и сразу отвечает именем по умолчанию.
                StarterNameField(
                    persisted = saved,
                    onChange = { saved = StarterName.sanitize(it) },
                )
            }
        }
        rule.onNodeWithText("Соня").performTextClearance()
        rule.waitForIdle()

        // В книге лежит имя по умолчанию — безымянной закваска не осталась…
        assertThat(saved).isEqualTo(StarterName.DEFAULT)
        // …но в поле человек видит подсказку, а не подставленное слово.
        rule.onNodeWithText(StarterName.DEFAULT).assertIsDisplayed()
        rule.onNodeWithText("Соня").assertDoesNotExist()
    }

    @Test
    fun `a name longer than a line cannot even be typed in`() {
        var saved = ""
        rule.setContent { MadreTheme { StarterNameField(persisted = "", onChange = { saved = it }) } }
        rule.onNode(hasSetTextAction())
            .performTextInput("Закваска которую мы завели прошлой осенью на ржаной муке")
        assertThat(saved.length).isAtMost(StarterName.MAX_LENGTH)
    }
}
