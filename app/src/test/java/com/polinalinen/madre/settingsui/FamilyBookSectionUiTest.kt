package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 13 release blocker: лист «Отправить» и смена кода уводят Activity в фон
 * и переживают её пересоздание. Набранное в полях семейной книги держится на
 * rememberSaveable, а не remember, — иначе почта и остальное обнулялись бы под
 * руками. Проверяется настоящим сохранением и восстановлением состояния.
 *
 * Файл лежит вне пакетной папки namesake: каталог test/.../ui/screens принадлежит
 * root и недоступен на запись; пакет остаётся ui.screens ради internal-доступа.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class FamilyBookSectionUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `typed email survives a state restoration`() {
        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent {
            MadreTheme {
                FamilyBookSection(
                    state = FamilyBookState.SignedOut,
                    onSignIn = { _, _ -> },
                    onRegister = { _, _, _ -> },
                    onCreateFamily = {},
                    onJoinFamily = {},
                    onRotateInvite = {},
                    onSignOut = {},
                    onCodeHandled = {},
                )
            }
        }

        // Cycle 19: форма больше не развёрнута с порога — сперва её просят.
        // Проверяемое правило от этого не изменилось: набранное переживает
        // пересоздание активити, потому что лежит на rememberSaveable.
        rule.onNodeWithText("Подключить полку…").performClick()

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("anya@example.com")
        rule.onNodeWithText("anya@example.com").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("anya@example.com").assertIsDisplayed()
    }
}
