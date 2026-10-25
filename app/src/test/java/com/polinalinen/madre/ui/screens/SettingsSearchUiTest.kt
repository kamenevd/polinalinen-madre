package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.first

/** Production content and actual preference persistence; no account, device or network effects. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class SettingsSearchUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @After fun clearControllers() { rule.activity.viewModelStore.clear() }
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private fun content(preferences: ReadingPreferences, onInterval: (Int) -> Unit = {}) {
        rule.setContent { RazvorotTheme(preferences) {
            SettingsContent("Полина", {}, {}, {}, "Соня", {}, 3, 7, 24, onInterval, true, {}, true, {},
                "Подпись книги", FamilyBookState.SignedOut, {}, {}, preferences = preferences)
        } }
    }
    @Test fun sixRowsReplaceSearchAndNoCredentialFieldsAreMounted() {
        content(ReadingPreferences(app,"settings-search-ui"))
        rule.onNodeWithContentDescription("Найти настройку").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        listOf("Семейная полка","Алиса","Закваска","Внешний вид","Уведомления и вибрация","О приложении").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithTag("settings-group").assertCountEquals(3)
    }
    @Test fun disclosureAndBackRestoreTheRoot() {
        content(ReadingPreferences(app,"settings-search-empty-ui"))
        rule.onNodeWithText("О приложении").performScrollTo().performClick()
        rule.onNodeWithText("В журнале").assertExists()
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.onNodeWithText("Закваска").assertExists()
    }
    @Test fun visibleRhythmChoiceSendsItsActualValueNotTheNextHiddenOption() {
        var chosen: Int? = null
        content(ReadingPreferences(app, "settings-search-rhythm-ui")) { chosen = it }
        rule.onNodeWithText("Закваска").performClick()
        rule.onNodeWithText("неделя").performScrollTo().performClick()
        assertEquals(168, chosen)
    }
    @Test fun themeChoiceUpdatesTheSameRootThemeAndSurvivesColdPreferences() {
        val preferences = ReadingPreferences(app, "settings-search-theme-ui")
        var current: Atmosphere? = null
        rule.setContent { RazvorotTheme(preferences) {
            val atmosphere = LocalAtmosphere.current
            SideEffect { current = atmosphere }
            SettingsContent("Полина", {}, {}, {}, "Соня", {}, 3, 7, 24, {}, true, {}, true, {},
                "Подпись книги", FamilyBookState.SignedOut, {}, {}, preferences = preferences)
        } }
        rule.onNodeWithText("Внешний вид").performClick()
        rule.onNodeWithText("Ночь").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Atmosphere.NIGHT, current)
        assertEquals(Atmosphere.NIGHT, ReadingPreferences(app, "settings-search-theme-ui").atmosphere)
        rule.onNodeWithText("Прокруткой").performScrollTo().performClick()
        assertEquals(ReadingMode.SCROLL, ReadingPreferences(app, "settings-search-theme-ui").mode)
    }
    @Test fun timerSoundOpensOnlyTheExistingBakingChannelForThisPackage() {
        openTimerSoundSettings(app)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        assertEquals(com.polinalinen.madre.notifications.MadreNotifier.CHANNEL_BAKING,
            intent.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
    }
    @Test fun actualSettingsSwitchesPersistThroughTheSharedApplicationRoomFlow() {
        lateinit var settings:com.polinalinen.madre.viewmodel.FamilySettingsViewModel
        lateinit var books:com.polinalinen.madre.viewmodel.FamilyBookViewModel
        rule.activityRule.scenario.onActivity { activity ->
            assertSame(app,activity.application)
            val provider=ViewModelProvider(activity,ViewModelProvider.AndroidViewModelFactory(app))
            settings=provider[com.polinalinen.madre.viewmodel.FamilySettingsViewModel::class.java]
            books=provider[com.polinalinen.madre.viewmodel.FamilyBookViewModel::class.java]
        }
        assertSame(app,settings.getApplication<MadreApplication>())
        assertSame(app.bookDisplaySettings,settings.bookDisplaySettings)
        rule.setContent { RazvorotTheme { SettingsScreen("Полина", {}, {},familySettingsViewModel=settings,familyBookViewModel=books) } }
        rule.onNodeWithText("Внешний вид").performClick()
        rule.onNodeWithContentDescription("Не гасить экран").performScrollTo().assertIsDisplayed().assertIsOn().performClick()
        try {
            rule.waitUntil(10_000) {
                // Also drain Android's main queue, where the actual ViewModel starts its Room write.
                rule.onNodeWithContentDescription("Не гасить экран").fetchSemanticsNode()
                !app.bookDisplaySettings.value.keepScreenOn
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val persisted = kotlinx.coroutines.runBlocking {
                app.familySettingsRepository.observeBookDisplaySettings().first()
            }
            throw AssertionError("Keep-screen action did not settle: persisted=$persisted published=${app.bookDisplaySettings.value}", failure)
        }
        rule.onNodeWithContentDescription("Не гасить экран").assertIsOff()
        rule.onNodeWithContentDescription("Назад").performClick()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        assertTrue(com.polinalinen.madre.notifications.MadreNotifier(app).canPost())
        rule.onNodeWithText("Уведомления и вибрация").performClick()
        rule.onNodeWithContentDescription("Ход выпечки на экране блокировки").performScrollTo().assertIsOff().performClick()
        rule.waitUntil(10_000) {
            rule.onNodeWithContentDescription("Ход выпечки на экране блокировки").fetchSemanticsNode()
            app.bookDisplaySettings.value.lockScreenProgress
        }
        rule.onNodeWithContentDescription("Ход выпечки на экране блокировки").assertIsOn()
        kotlinx.coroutines.runBlocking {
            val restored = com.polinalinen.madre.data.repository.FamilySettingsRepository(app.database)
                .observeBookDisplaySettings().first()
            assertFalse(restored.keepScreenOn)
            assertTrue(restored.lockScreenProgress)
        }
    }
}
