package com.polinalinen.madre.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Смоук-тесты на BakingCompleteScreen (Cycle 1 wrap-up, экран 6).
 * Проверяем только сессию-null (без активной выпечки в BakingViewModel) —
 * это тот путь, что не требует поднимать реальную BakingSession в тесте,
 * и он же — единственный гарантированно воспроизводимый сценарий (deep-link,
 * возврат после смерти процесса, см. комментарий в BakingCompleteScreen.kt).
 */
@RunWith(AndroidJUnit4::class)
class BakingCompleteScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fallbackTitleShownWhenSessionIsMissing() {
        composeTestRule.setContent {
            MadreTheme {
                BakingCompleteScreen(sessionId = null, onHome = {})
            }
        }
        composeTestRule.onNodeWithText("испечено").assertExists()
    }

    @Test
    fun homeButtonInvokesCallback() {
        var homeClicked = false
        composeTestRule.setContent {
            MadreTheme {
                BakingCompleteScreen(sessionId = null, onHome = { homeClicked = true })
            }
        }
        composeTestRule.onNodeWithText("На главную").performClick()
        assert(homeClicked) { "onHome должен был сработать по тапу на кнопку" }
    }
}
