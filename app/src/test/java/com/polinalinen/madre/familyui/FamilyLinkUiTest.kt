package com.polinalinen.madre.familyui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.integration.v2.*
import com.polinalinen.madre.ui.screens.*
import com.polinalinen.madre.viewmodel.FamilyOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = Application::class)
class FamilyLinkUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun removeRequiresConfirmationAndDefaultsToRotatingLink() {
        var overlay by mutableStateOf<FamilyOverlay?>(FamilyOverlay.Member("member2"))
        var removed: Pair<String, Boolean>? = null
        rule.setContent { FamilyFixtureTheme { FamilyPanelContent(panelFixture(), overlay,
            fixtureActions(show = { overlay = it }, remove = { id, rotate -> removed = id to rotate })) } }
        rule.onNodeWithText("Убрать из семьи").performScrollTo().performClick()
        assertNull(removed)
        rule.onNodeWithTag("family-remove-rotate").assertIsOn()
        rule.onNodeWithText("УБРАТЬ", substring = false).performClick()
        assertEquals("member2" to true, removed)
    }
    @Test fun selfLabelEditorForMemberDoesNotOfferOwnerActionsOrYandexPortrait() {
        rule.setContent { FamilyFixtureTheme { FamilyPanelContent(panelFixture().copy(userId = "member1"), FamilyOverlay.Member("member1"), fixtureActions()) } }
        rule.onNodeWithTag("family-member-name").assertExists().performTextReplacement("Моя подпись")
        rule.onNodeWithText("Сохранить подпись").assertIsEnabled()
        rule.onNodeWithText("Сделать владельцем").assertDoesNotExist()
        rule.onNodeWithText("Убрать из семьи").assertDoesNotExist()
        rule.onNodeWithText("Почта").assertDoesNotExist(); rule.onNodeWithText("Пароль").assertDoesNotExist()
        rule.onNodeWithText("Код приглашения").assertDoesNotExist()
    }
    @Test fun joinIsAnExplicitSeparateActionAndHonestEmptyAuthNameIsNotInvented() {
        var joined = 0
        rule.setContent { FamilyFixtureTheme { FamilyInvitationContent(InvitationFlow.State(InvitationFlow.Phase.READY, previewFixture()), "", {}, { joined++ }, {}, {}, {}, {}) } }
        assertEquals(0, joined)
        rule.onNodeWithText("Вы вошли: имя не указано · Яндекс ID").assertExists()
        rule.onNodeWithText("Присоединиться").performClick(); assertEquals(1, joined)
    }
    @Test fun signedOutInvitationContainsOnlyOfficialSignInNotJoinOrEmailCode() {
        rule.setContent { FamilyFixtureTheme { FamilyInvitationContent(InvitationFlow.State(InvitationFlow.Phase.SIGN_IN, previewFixture()), "", {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("Присоединиться").assertDoesNotExist()
        rule.onNodeWithText("После входа вернёмся к этому приглашению.").assertExists()
        rule.onNodeWithText("Почта").assertDoesNotExist(); rule.onNodeWithText("Пароль").assertDoesNotExist()
    }
    @Test fun pendingHasSingleForegroundRefreshAndNoAccessOrJoinButton() {
        var refresh = 0
        rule.setContent { FamilyFixtureTheme { FamilyInvitationContent(InvitationFlow.State(InvitationFlow.Phase.PENDING, previewFixture()), "Марина", {}, {}, {}, { refresh++ }, {}, {}) } }
        assertEquals(0, refresh)
        rule.onNodeWithText("Ждём подтверждения").assertExists()
        rule.onNodeWithText("Присоединиться").assertDoesNotExist()
        rule.onNodeWithText("Проверить подтверждение").performClick(); assertEquals(1, refresh)
    }
    @Test fun transferAndLeaveRequiresChosenOwnerAndNeverDefaultsToOldest() {
        rule.setContent { FamilyFixtureTheme { FamilyPanelContent(panelFixture(), FamilyOverlay.Leave, fixtureActions()) } }
        rule.onNodeWithText("ПЕРЕДАТЬ И УЙТИ").assertIsNotEnabled()
        rule.onNodeWithTag("family-successor-member1").performClick()
        rule.onNodeWithText("ПЕРЕДАТЬ И УЙТИ").assertIsEnabled()
    }
    @Test fun shareAndCopyAreSeparateExplicitActionsWithPlatformSendIntent() {
        var shared = 0; var copied = 0
        rule.setContent { FamilyFixtureTheme { FamilyPanelContent(panelFixture(), FamilyOverlay.Invite,
            fixtureActions(share = { shared++ }, copy = { copied++ })) } }
        assertEquals(0, copied); assertEquals(0, shared)
        rule.onNodeWithText("Поделиться ссылкой").performScrollTo().performClick(); assertEquals(1, shared); assertEquals(0, copied)
        rule.onNodeWithText("Копировать").performScrollTo().performClick(); assertEquals(1, copied)
        val intent = familyShareIntent(panelFixture().invite!!, "Каменевы", "Дима")
        assertEquals(Intent.ACTION_SEND, intent.action); assertEquals("text/plain", intent.type)
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP"))
        assertFalse(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("SYNTHETIC_PB"))
    }
    @Test fun oneTapCreationUsesSafeNameWithoutNameDialog() {
        var created: String? = null
        rule.setContent { FamilyFixtureTheme { FamilyPanelContent(FamilyPanelState(signedIn = true, accountName = "Дима"), null,
            fixtureActions().copy(create = { created = it })) } }
        rule.onNodeWithText("Создать семейную полку").performScrollTo().performClick()
        assertEquals("Наша семья", created)
        rule.onNodeWithText("Название семьи").assertDoesNotExist()
    }
    @Test fun ownerPendingDecisionsSurviveWithoutApprovalToggle() {
        val state = panelFixture().copy(family = familyFixture().copy(pending = listOf(
            FamilyRequest("request1", "applicant3", "Марина", "2026-10-25T10:00:00Z"))))
        rule.setContent { FamilyFixtureTheme { FamilyPanelContent(state, null, fixtureActions()) } }
        rule.onNodeWithTag("family-approval").assertDoesNotExist()
        rule.onNodeWithText("Марина просит войти").performScrollTo().assertExists()
        rule.onNodeWithText("Принять").assertExists()
        rule.onNodeWithText("Отклонить").assertExists()
    }
    @Test fun createdInviteUsesServerSeatsAndQrHasNoAutomaticShareOrCopy() {
        var shared = 0; var copied = 0
        rule.setContent { FamilyFixtureTheme { FamilyPanelContent(panelFixture(), FamilyOverlay.CreatedInvite,
            fixtureActions(share = { shared++ }, copy = { copied++ })) } }
        rule.onNodeWithText("Полка создана", ignoreCase = true).assertExists()
        rule.onNodeWithText("ещё 4 мест", substring = true).assertExists()
        rule.onNodeWithText("QR-код").performScrollTo().performClick()
        rule.onNodeWithTag("family-invite-qr").assertExists()
        assertEquals(0, shared); assertEquals(0, copied)
    }
    @Test fun emailMigrationCardKeepsPersonalBookAndDoesNotExposeLegacyForm() {
        rule.setContent { FamilyFixtureTheme { FamilyEmptyContent(FamilyPanelState(migration = true), {}, {}) } }
        rule.onNodeWithText("Перейдите на Яндекс ID — семья сохранится").assertExists()
        rule.onNodeWithText("Почта").assertDoesNotExist(); rule.onNodeWithText("Пароль").assertDoesNotExist()
    }
}
