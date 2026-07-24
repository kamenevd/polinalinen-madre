package com.polinalinen.madre.sync

import android.content.Context
import java.util.UUID

/**
 * Анонимный стабильный device_id для PocketBase (Cycle 5): UUID, создаётся
 * при первом обращении и живёт в тех же madre_prefs, что избранное/имя.
 * Ничего личного не содержит — по нему сервер только отличает семьи друг
 * от друга (фильтр «статистика других» и подсчёт «сколько семей пекут»).
 */
object DeviceIdentity {

    private const val KEY = "device_id"

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences("madre_prefs", Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}
