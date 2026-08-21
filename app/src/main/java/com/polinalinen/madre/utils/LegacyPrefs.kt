package com.polinalinen.madre.utils

import android.content.SharedPreferences

/**
 * Cycle 12: уборка следов удалённых механик из madre_prefs.
 *
 * «Слипшиеся страницы» (Cycle 10) писали по ключу на каждый расклеенный
 * рецепт — `stuck_pages_freed_<recipeId>`. Самой механики в книге больше нет
 * (см. DESIGN-V4.md, Cycle 12), и ключи остались бы висеть в настройках
 * навсегда, у каждого, кто хоть раз отлепил кромку.
 *
 * Room не трогаем вовсе: у удалённой механики своих таблиц не было, а всё
 * остальное в madre_prefs — избранное, имя, спокойный режим, кофейные круги,
 * даты последних открытий — живое и переезжает как есть. Правило «что именно
 * считается мусором» — чистая функция [obsoleteKeys], поэтому проверяется
 * обычным юнит-тестом и не может случайно захватить лишнее.
 */
object LegacyPrefs {

    /** Префиксы ключей, которых в книге больше нет. */
    private val OBSOLETE_PREFIXES = listOf("stuck_pages_freed_")
    private val OBSOLETE_KEYS = setOf("shelf_share_mode")

    fun obsoleteKeys(allKeys: Set<String>): Set<String> =
        allKeys.filterTo(mutableSetOf()) { key ->
            key in OBSOLETE_KEYS || OBSOLETE_PREFIXES.any { key.startsWith(it) }
        }

    /** Возвращает, сколько ключей убрано — ноль на всех запусках после первого. */
    fun purge(prefs: SharedPreferences): Int {
        val doomed = obsoleteKeys(prefs.all.keys)
        if (doomed.isEmpty()) return 0
        prefs.edit().apply { doomed.forEach { remove(it) } }.apply()
        return doomed.size
    }
}
