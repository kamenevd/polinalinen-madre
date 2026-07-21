package com.polinalinen.madre.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Смоук-тесты на NotificationsScreen (Cycle 1 wrap-up, экран 7).
 * BakingViewModel в тесте создаётся пустым (нет активных выпечек) — реалистичный
 * старт для свежего процесса, поэтому здесь проверяем именно пустое состояние
 * ленты и заметку из дневника закваски (не зависящую от сессий/истории).
 */
@RunWith(AndroidJUnit4::class)
class NotificationsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyStateShownWhenNothingIsHappening() {
        composeTestRule.setContent {
            MadreTheme {
                NotificationsScreen(
                    phase = GrowthPhase.LAG,
                    starterHeadline = "проснулась, потягиваюсь",
                    recentBakes = emptyList(),
                    onBack = {},
                    onOpenStarter = {},
                    onOpenTimer = {},
                )
            }
        }
        composeTestRule.onNodeWithText("«проснулась, потягиваюсь»").assertExists()
        composeTestRule.onNodeWithText(
            "здесь пока тихо — записки появятся, когда что-то происходит на кухне"
        ).assertExists()
    }

    @Test
    fun backLinkInvokesCallback() {
        var backClicked = false
        composeTestRule.setContent {
            MadreTheme {
                NotificationsScreen(
                    phase = GrowthPhase.PEAK,
                    starterHeadline = "я на пике! пеки со мной сейчас",
                    recentBakes = emptyList(),
                    onBack = { backClicked = true },
                    onOpenStarter = {},
                    onOpenTimer = {},
                )
            }
        }
        // PageLabel рендерит текст через .uppercase() — сверяем без учёта регистра.
        composeTestRule.onNodeWithText("← Первая полоса", ignoreCase = true).performClick()
        assert(backClicked) { "onBack должен был сработать по тапу на ссылку" }
    }
}
