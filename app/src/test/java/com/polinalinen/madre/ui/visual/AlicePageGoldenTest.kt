package com.polinalinen.madre.ui.visual

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.ui.screens.AlicePage
import com.polinalinen.madre.ui.theme.MadreTheme
import com.polinalinen.madre.viewmodel.fakes.sampleRecipe
import com.polinalinen.madre.voice.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class AlicePageGoldenTest {
    @get:Rule val rule = createComposeRule()
    private val bake = BakingSession.start(7, sampleRecipe("bread"))
    private var selected: String? = null
    private var disconnected = 0
    private fun render(state: VoiceUiState, sessions: List<BakingSession> = listOf(bake)) {
        rule.setContent { MadreTheme {
            AlicePage(state, sessions, {}, { selected = it }, {}, {}, { disconnected++ })
        } }
    }
    @Test fun aliceUnlinkedConsentIsNeverAutomatic() {
        render(VoiceUiState())
        rule.onNodeWithText("Не подключено").assertExists()
        rule.onNodeWithText("Разрешить для этой выпечки").assertDoesNotExist()
        assertNull(selected)
        rule.onNodeWithText("Выпечка").performScrollTo().performClick()
        rule.onNodeWithText("Разрешить для этой выпечки").performScrollTo().assertIsEnabled()
        rule.onRoot().captureRoboImage()
        rule.onNodeWithText("Разрешить для этой выпечки").performClick()
        assertEquals(bake.bakeKey, selected)
    }
    @Test fun aliceLoading() {
        render(VoiceUiState(VoiceStatus.LOADING))
        rule.onNodeWithText("Проверяем подключение…").assertExists()
        rule.onRoot().captureRoboImage()
    }
    @Test fun aliceConnectedNormal() {
        render(VoiceUiState(VoiceStatus.CONNECTED, bake.bakeKey))
        rule.onNodeWithText("Телефон на связи · выпечка доступна Алисе").assertExists()
        rule.onRoot().captureRoboImage()
        rule.onNodeWithText("Отключить Алису").performScrollTo().performClick()
        assertEquals(1, disconnected)
    }
    @Test fun aliceOfflineErrorIsVisible() {
        render(VoiceUiState(VoiceStatus.OFFLINE, bake.bakeKey, "Телефон не ответил. Результат неизвестен — проверь таймер."))
        rule.onNodeWithText("Телефон не подтвердил связь").assertExists()
        rule.onNodeWithText("Телефон не ответил.", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage()
    }
    @Test fun alicePairingCodeAndConsent() {
        render(VoiceUiState(VoiceStatus.WAITING_LINK, bake.bakeKey, preview = PairPreview("challenge", "ABCDEFG234", Long.MAX_VALUE, bake.bakeKey, "generation")))
        rule.onNodeWithText("Код из окна подключения").performScrollTo().performTextInput("ABCDEFG234")
        rule.onNodeWithText("Подтвердить чтение и управление").performScrollTo().assertIsDisplayed()
        rule.onRoot().captureRoboImage()
    }
    @Test fun aliceRevocationPendingCannotReselect() {
        render(VoiceUiState(VoiceStatus.REVOKE_PENDING))
        rule.onNodeWithText("На телефоне отключено · ждём отзыв доступа").assertExists()
        rule.onNodeWithText("Разрешить для этой выпечки").assertDoesNotExist()
        rule.onRoot().captureRoboImage()
    }
    @Test fun aliceEmptyNoFakeConnection() {
        render(VoiceUiState(), emptyList())
        rule.onNodeWithText("Сначала начни выпечку в рецепте. Ничего не выбрано автоматически.").assertExists()
        rule.onNodeWithText("Разрешить для этой выпечки").assertDoesNotExist()
        rule.onRoot().captureRoboImage()
    }
}
