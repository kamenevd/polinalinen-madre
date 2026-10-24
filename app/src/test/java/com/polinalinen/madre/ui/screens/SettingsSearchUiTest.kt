package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production content and actual preference persistence; no account, device or network effects. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class SettingsSearchUiTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private fun content(preferences: ReadingPreferences, onInterval: (Int) -> Unit = {}) {
        rule.setContent { RazvorotTheme(preferences) {
            SettingsContent("Полина", {}, {}, {}, "Соня", {}, 3, 7, 24, onInterval, true, {}, true, {},
                "Подпись книги", FamilyBookState.SignedOut, {}, {}, preferences = preferences)
        } }
    }
    @Test fun actualSearchHidesUnrelatedRowsAndClearRestoresThem() {
        content(ReadingPreferences(app, "settings-search-ui"))
        rule.onNodeWithContentDescription("Найти настройку").performTextInput("увед")
        rule.onNodeWithText("Напоминания").assertExists()
        rule.onNodeWithText("Звук таймера").assertExists()
        rule.onNodeWithText("В журнале").assertDoesNotExist()
        rule.onNodeWithText("ЧТЕНИЕ").assertDoesNotExist()
        rule.onNodeWithContentDescription("Очистить поиск").performClick()
        rule.onNodeWithText("В журнале").assertExists()
        rule.onNodeWithText("ЧТЕНИЕ").assertExists()
    }
    @Test fun noMatchHasAWorkingRecoveryAction() {
        content(ReadingPreferences(app, "settings-search-empty-ui"))
        rule.onNodeWithContentDescription("Найти настройку").performTextInput("зефир")
        rule.onNodeWithText("ТАКОЙ НАСТРОЙКИ НЕТ").assertExists()
        rule.onNodeWithText("Очистить поиск").performScrollTo().performClick()
        rule.onNodeWithText("В журнале").assertExists()
    }
    @Test fun visibleRhythmChoiceSendsItsActualValueNotTheNextHiddenOption() {
        var chosen: Int? = null
        content(ReadingPreferences(app, "settings-search-rhythm-ui")) { chosen = it }
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
        rule.onNodeWithText("Ночь").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Atmosphere.NIGHT, current)
        assertEquals(Atmosphere.NIGHT, ReadingPreferences(app, "settings-search-theme-ui").atmosphere)
        rule.onNodeWithText("Прокрутка").performScrollTo().performClick()
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
}
