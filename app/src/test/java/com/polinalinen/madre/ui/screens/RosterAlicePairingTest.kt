package com.polinalinen.madre.ui.screens

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.integration.v2.*
import com.polinalinen.madre.ui.kit.RazvorotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pure UI callbacks: no Application startup, SDK owner, real account or network. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = Application::class)
class RosterAlicePairingTest {
    @get:Rule val rule = createComposeRule()
    private var state by mutableStateOf(RosterUiState())
    private var identityReady by mutableStateOf(false)
    private val actions = mutableListOf<String>()
    private val switch = "Управлять голосом · все текущие выпечки"
    private fun render() {
        rule.setContent { RazvorotTheme {
            RosterAlicePage(state, {}, { actions += "consent" }, { actions += "cancel" },
                { actions += "check" }, { actions += "continue" }, identityReady,
                accountStep = { YandexAccountPanel(YandexAccountState.SignedOut, false,
                    { actions += "login" }, {}, {}) })
        } }
    }
    private fun tap(text: String) {
        // The kit's fence uses JVM wall time (not the Compose/Robolectric clock).
        Thread.sleep(650)
        val node = rule.onNodeWithText(text)
        if (text !in setOf("Да · разрешить", "Нет")) node.performScrollTo()
        node.performClick()
        rule.waitForIdle()
    }
    private fun noImplicitAction() { rule.waitForIdle(); assertTrue(actions.isEmpty()) }

    @Test fun identityCallbackAndRecompositionNeedExplicitContinue() {
        state = RosterUiState(RosterStatus.ERROR, pairStatus = InstallationPairStatus.NEEDS_IDENTITY)
        render(); noImplicitAction()
        rule.onNodeWithText("Продолжить подключение").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { identityReady = true }
        noImplicitAction() // SDK bind/return does not grant or continue consent.
        rule.onNodeWithContentDescription(switch).performScrollTo().assertIsNotEnabled()
        tap("Продолжить подключение")
        assertEquals(listOf("continue"), actions)
        tap("Отменить попытку")
        assertEquals(listOf("continue", "cancel"), actions)
    }

    @Test fun pendingCheckAndReturnPreserveDeadlineAndNeverCreateConsent() {
        val deadline = System.currentTimeMillis() + 240_000
        state = RosterUiState(RosterStatus.ERROR, pendingExpiresAt = deadline,
            pairStatus = InstallationPairStatus.AUTHORIZATION_PENDING)
        render(); noImplicitAction()
        tap("Проверить подключение")
        assertEquals(listOf("check"), actions)
        rule.runOnIdle { state = state.copy(message = "Привязка ещё не подтверждена") }
        rule.waitForIdle()
        assertEquals(deadline, state.pendingExpiresAt)
        assertEquals(listOf("check"), actions)
        rule.onNodeWithText("Алиса подключена · навык «Голос закваски»").assertDoesNotExist()
        rule.onNodeWithText("Подключить заново").assertDoesNotExist()
        rule.onNodeWithContentDescription(switch).performScrollTo().assertIsNotEnabled()
        tap("Отменить попытку")
        assertEquals(listOf("check", "cancel"), actions)
    }

    @Test fun terminalAndLocallyExpiredAttemptsRequireCancelBeforeNewConsent() {
        state = RosterUiState(RosterStatus.ERROR, pairStatus = InstallationPairStatus.EXPIRED)
        render()
        for (pair in listOf(InstallationPairStatus.EXPIRED, InstallationPairStatus.INTERRUPTED,
            InstallationPairStatus.AUTHORIZATION_PENDING)) {
            rule.runOnIdle { state = state.copy(pairStatus = pair, pendingExpiresAt = 1L) }
            noImplicitAction()
            rule.onNodeWithText("Проверить подключение").assertDoesNotExist()
            rule.onNodeWithText("Повторить связь").assertDoesNotExist()
            rule.onNodeWithContentDescription(switch).performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("Отменить попытку").performScrollTo().assertIsDisplayed()
        }
        tap("Отменить попытку")
        assertEquals(listOf("cancel"), actions)
        rule.runOnIdle { state = RosterUiState() } // Cancel acknowledged by fake controller.
        rule.onNodeWithContentDescription(switch).performScrollTo().performClick()
        rule.onNodeWithText("Разрешить для всех текущих выпечек?").assertIsDisplayed()
        assertEquals(listOf("cancel"), actions)
        tap("Да · разрешить")
        assertEquals(listOf("cancel", "consent"), actions)
    }

    @Test fun transportRetryUsesContinuationAndNeverNewWarning() {
        state = RosterUiState(RosterStatus.ERROR, retryPair = true)
        render(); noImplicitAction()
        rule.onNodeWithContentDescription(switch).performScrollTo().assertIsNotEnabled()
        tap("Повторить эту попытку")
        assertEquals(listOf("continue"), actions)
        rule.onNodeWithText("Да · разрешить").assertDoesNotExist()
        tap("Отменить попытку")
        assertEquals(listOf("continue", "cancel"), actions)
    }

    @Test fun offlineCheckAndAllErrorRevocationsRemainExplicit() {
        state = RosterUiState(RosterStatus.OFFLINE, consent = true)
        render(); noImplicitAction()
        tap("Повторить связь")
        assertEquals(listOf("check"), actions)
        tap("ОТКЛЮЧИТЬ АЛИСУ")
        for (status in listOf(RosterStatus.ERROR, RosterStatus.ATTACHING, RosterStatus.REVOKE_PENDING)) {
            rule.runOnIdle { state = RosterUiState(status) }
            tap(if (status == RosterStatus.REVOKE_PENDING) "Повторить отзыв доступа" else "ОТКЛЮЧИТЬ АЛИСУ")
        }
        assertEquals(listOf("check", "cancel", "cancel", "cancel", "cancel"), actions)
    }

    @Test fun openingAndWarningDismissalNeverLaunchLoginOrConsent() {
        render(); noImplicitAction()
        rule.onNodeWithText("Выберите выпечку").assertDoesNotExist()
        rule.onNodeWithText("СЛЫШИТ ВСЕ ТЕКУЩИЕ ВЫПЕЧКИ · 0").assertExists()
        rule.onNodeWithContentDescription(switch).performScrollTo().performClick()
        noImplicitAction()
        tap("Нет")
        noImplicitAction()
        rule.onNodeWithContentDescription(switch).performScrollTo().performClick()
        tap("Да · разрешить")
        assertEquals(listOf("consent"), actions)
    }
}
