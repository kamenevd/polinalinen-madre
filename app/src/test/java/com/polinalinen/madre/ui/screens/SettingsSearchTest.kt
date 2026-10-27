package com.polinalinen.madre.ui.screens

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun aliasesAreCaseInsensitiveAndAllWordsMustMatchTheSameActualRow() {
        assertTrue(settingsMatches(SettingsEntry.RHYTHM, "  РИТМ   кормления "))
        assertTrue(settingsMatches(SettingsEntry.TEXT_SIZE, "шрифт"))
        assertTrue(settingsMatches(SettingsEntry.THEME, "ночь"))
        assertFalse(settingsMatches(SettingsEntry.THEME, "ночь закваска"))
        assertFalse(settingsMatches(SettingsEntry.SHELF, "экспорт"))
    }
    @Test fun emptyQueryShowsTheExistingCatalogAndUnknownQueryShowsNothing() {
        assertTrue(SettingsEntry.entries.all { settingsMatches(it, "") })
        assertTrue(SettingsEntry.entries.none { settingsMatches(it, "зефир") })
    }
    @Test fun notificationAliasFindsOnlyImplementedControlsWithoutInventingQuietHours() {
        assertEquals(setOf(SettingsEntry.REMINDERS, SettingsEntry.TIMER_SOUND, SettingsEntry.LOCK_PROGRESS),
            SettingsEntry.entries.filter { settingsMatches(it, "увед") }.toSet())
    }
}
