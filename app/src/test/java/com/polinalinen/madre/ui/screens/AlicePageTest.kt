package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.RazvorotTheme as MadreTheme
import com.polinalinen.madre.voice.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class AlicePageTest {
    @get:Rule val rule = createComposeRule()
    private val bake = com.polinalinen.madre.model.BakingSession.start(7,
        com.polinalinen.madre.viewmodel.fakes.sampleRecipe())
    private fun render(state: VoiceUiState) {
        val sessions = if (state.selectedBake == bake.bakeKey) listOf(bake) else emptyList()
        rule.setContent { MadreTheme { AlicePage(state, sessions, {}, {}, {}, {}, {}) } }
    }
    @Test fun unlinked() { render(VoiceUiState()); rule.onNodeWithText("Не подключено").assertExists() }
    @Test fun loading() { render(VoiceUiState(VoiceStatus.LOADING)); rule.onNodeWithText("Проверяем подключение…").assertExists() }
    @Test fun offline() { render(VoiceUiState(VoiceStatus.OFFLINE)); rule.onNodeWithText("Телефон не подтвердил связь").assertExists() }
    @Test fun connected() { render(VoiceUiState(VoiceStatus.CONNECTED, selectedBake = bake.bakeKey)); rule.onNodeWithText("Телефон на связи · выпечка доступна Алисе").assertExists() }
    @Test fun error() { render(VoiceUiState(VoiceStatus.ERROR, message = "Код истёк. Подключи навык заново.")); rule.onNodeWithText("Код истёк. Подключи навык заново.").assertIsDisplayed() }
    @Test fun disconnectPendingNeverSaysConnected() {
        render(VoiceUiState(VoiceStatus.REVOKE_PENDING))
        rule.onNodeWithText("Телефон на связи · выпечка доступна Алисе").assertDoesNotExist()
        rule.onNodeWithText("Повторить отзыв доступа").performScrollTo().assertHasClickAction()
    }
}
