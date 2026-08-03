package com.polinalinen.madre.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 12: уборка следов «слипшихся страниц». Опасность здесь ровно одна —
 * захватить лишнее и стереть живую настройку, поэтому правило проверяется на
 * реальном наборе ключей madre_prefs.
 */
class LegacyPrefsTest {

    private val realKeys = setOf(
        "favorite_recipes",
        "my_name",
        "calm_mode",
        "coffee_ring_borodinsky",
        "last_opened_borodinsky",
    )

    @Test
    fun `keys of the removed stuck pages mechanic are obsolete`() {
        val keys = realKeys + setOf("stuck_pages_freed_borodinsky", "stuck_pages_freed_focaccia")
        assertThat(LegacyPrefs.obsoleteKeys(keys))
            .containsExactly("stuck_pages_freed_borodinsky", "stuck_pages_freed_focaccia")
    }

    @Test
    fun `living settings are never touched`() {
        assertThat(LegacyPrefs.obsoleteKeys(realKeys)).isEmpty()
    }

    @Test
    fun `a clean book has nothing to purge`() {
        assertThat(LegacyPrefs.obsoleteKeys(emptySet())).isEmpty()
    }
}
