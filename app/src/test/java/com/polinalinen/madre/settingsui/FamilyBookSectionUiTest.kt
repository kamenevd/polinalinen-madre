package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.ui.kit.RazvorotTheme as MadreTheme
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
    fun `retired credential form stays absent after state restoration`() {
        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent {
            MadreTheme {
                FamilyBookSection(
                    state = FamilyBookState.SignedOut,
                    onSignIn = { _, _ -> },
                    onRegister = { _, _, _ -> },
                    onCreateFamily = {},
                    onJoinFamily = {},
                )
            }
        }

        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        restorationTester.emulateSavedInstanceStateRestore()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Семейная полка доступна после входа с Яндекс ID. Книга работает и без входа.").assertIsDisplayed()
    }

    @Test
    fun `legacy password APIs do not expose a credential form`() {
        rule.setContent {
            MadreTheme {
                FamilyBookSection(
                    state = FamilyBookState.SignedOut,
                    onSignIn = { _, _ -> },
                    onRegister = { _, _, _ -> },
                    onCreateFamily = {},
                    onJoinFamily = {},
                )
            }
        }
        rule.onNodeWithText(com.polinalinen.madre.account.PasswordReset.ACTION_LABEL).assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Вход недоступен в этой сборке.").assertIsDisplayed()
    }
}
