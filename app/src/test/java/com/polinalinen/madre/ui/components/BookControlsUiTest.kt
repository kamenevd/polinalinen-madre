package com.polinalinen.madre.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 12: правила, которые нельзя проверить глазами и которые уже однажды
 * разъехались по экранам — размер мишени, роль для TalkBack, один тап на одно
 * действие и обязательный спрос перед необратимым.
 *
 * Эмулятора в сборочной среде нет, поэтому Compose крутится на Robolectric —
 * тем же способом, что и золотой снимок экслибриса (BookplateGoldenTest).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class BookControlsUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a book button is never smaller than a finger`() {
        rule.setContent { MadreTheme { BookButton(label = "Начать выпечку", onClick = {}) } }
        rule.onNodeWithText("Начать выпечку").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `a quiet text action is a finger tall too`() {
        rule.setContent { MadreTheme { TextAction(label = "убрать", onClick = {}) } }
        rule.onNodeWithText("убрать").assertHeightIsAtLeast(48.dp)
    }

    /**
     * Cycle 18: тихое действие — всё-таки действие, и читать его надо не
     * прищуриваясь. 13sp набирались мельче любой подписи на странице, и
     * «заглянуть ещё раз» под строкой об отвалившейся сети выглядело частью
     * той же строки, а не выходом из неё.
     *
     * Размер снимается с настоящей раскладки текста, а не с константы рядом:
     * константа, сверенная сама с собой, ничего не проверяет.
     */
    @Test
    fun `a quiet text action is set at reading size`() {
        rule.setContent { MadreTheme { TextAction(label = "заглянуть ещё раз", onClick = {}) } }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("заглянуть ещё раз")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        assertThat(layouts.first().layoutInput.style.fontSize).isEqualTo(15.sp)
    }

    @Test
    fun `controls announce themselves as buttons`() {
        rule.setContent {
            MadreTheme {
                Column {
                    BookButton(label = "Готово", onClick = {})
                    TextAction(label = "повторить", onClick = {})
                }
            }
        }
        listOf("Готово", "повторить").forEach { label ->
            val node = rule.onNodeWithText(label).fetchSemanticsNode()
            assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
            assertThat(node.config.getOrNull(SemanticsActions.OnClick)).isNotNull()
        }
    }

    /**
     * Двойной тап по «Дальше» перескакивал шаг выпечки — TapGate ловит второе
     * нажатие, и здесь это проверяется на настоящем нажатии, а не на числах.
     */
    @Test
    fun `a hurried double tap counts as one`() {
        var taps = 0
        rule.setContent { MadreTheme { BookButton(label = "Дальше →", onClick = { taps++ }) } }
        rule.onNodeWithText("Дальше →").performClick()
        rule.onNodeWithText("Дальше →").performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun `nothing irreversible happens without an explicit yes`() {
        var cancelled = false
        rule.setContent {
            MadreTheme {
                var open by remember { mutableStateOf(true) }
                if (open) {
                    ConfirmDialog(
                        title = "Бросить выпечку?",
                        message = "вернуться к этому шагу будет уже нельзя",
                        confirmLabel = "Бросить",
                        dismissLabel = "Остаться",
                        onConfirm = { cancelled = true; open = false },
                        onDismiss = { open = false },
                    )
                }
            }
        }

        // Спрос виден, и до ответа ничего не произошло.
        rule.onNodeWithText("Бросить выпечку?").assertIsDisplayed()
        assertThat(cancelled).isFalse()

        rule.onNodeWithText("Бросить").performClick()
        assertThat(cancelled).isTrue()
    }

    @Test
    fun `staying is the safe answer`() {
        var cancelled = false
        var dismissed = false
        rule.setContent {
            MadreTheme {
                ConfirmDialog(
                    title = "Бросить выпечку?",
                    message = "вернуться к этому шагу будет уже нельзя",
                    confirmLabel = "Бросить",
                    dismissLabel = "Остаться",
                    onConfirm = { cancelled = true },
                    onDismiss = { dismissed = true },
                )
            }
        }
        rule.onNodeWithText("Остаться").performClick()
        assertThat(dismissed).isTrue()
        assertThat(cancelled).isFalse()
    }
}
