package com.polinalinen.madre.navigation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Navigation-only state. Existing family/notes/Room keys are never replaced. */
data class BookBookmark(val recipeId: String, val page: Int) {
    fun encode() = "$recipeId:$page"
    companion object {
        fun decode(value: String?): BookBookmark? {
            val id = value?.substringBeforeLast(':', "").orEmpty()
            val page = value?.substringAfterLast(':')?.toIntOrNull()
            return if (id.isNotBlank() && page != null && page >= 0) BookBookmark(id, page) else null
        }
    }
}

data class PlannedBake(val recipeId: String, val portions: Int, val startAtMillis: Long) {
    val id: String get() = "$recipeId:$startAtMillis"
}

object BookStartup {
    const val RECENT_MILLIS = 12 * 60 * 60 * 1000L
    fun tab(saved: Int, lastVisit: Long, now: Long): Int =
        if (saved in 0..3 && now - lastVisit in 0 until RECENT_MILLIS) saved else 0
}

class BookPreferences(context: Context, namespace: String = "madre_prefs") : AutoCloseable {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val gson = Gson()
    var bookmark by mutableStateOf(BookBookmark.decode(prefs.getString("bookmark", null)))
        private set
    var plans by mutableStateOf(readPlans())
        private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bookmark") bookmark = BookBookmark.decode(prefs.getString(key, null))
        if (key == "planned_bakes") plans = readPlans()
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    fun initialTab(now: Long = System.currentTimeMillis()) = BookStartup.tab(
        prefs.getInt("last_tab", 0), prefs.getLong("last_visit", 0), now)
    fun visit(tab: Int, now: Long = System.currentTimeMillis()) {
        prefs.edit().putInt("last_tab", tab.coerceIn(0, 3)).putLong("last_visit", now).apply()
    }
    fun saveBookmark(value: BookBookmark) {
        bookmark = value
        prefs.edit().putString("bookmark", value.encode()).apply()
    }
    fun portions(recipeId: String) = prefs.getInt("chapter_portions_$recipeId", 2).coerceIn(1, 5)
    fun savePortions(recipeId: String, value: Int) {
        prefs.edit().putInt("chapter_portions_$recipeId", value.coerceIn(1, 5)).apply()
    }
    fun putPlan(plan: PlannedBake): Boolean {
        require(plan.portions in 1..5 && plan.startAtMillis > 0 && plan.recipeId.isNotBlank())
        val next = (plans.filterNot { it.id == plan.id } + plan).sortedBy { it.startAtMillis }
        if (!prefs.edit().putString("planned_bakes", gson.toJson(next)).commit()) return false
        plans = next
        return true
    }
    fun removePlan(id: String): Boolean {
        val next = plans.filterNot { it.id == id }
        if (!prefs.edit().putString("planned_bakes", gson.toJson(next)).commit()) return false
        plans = next
        return true
    }
    private fun readPlans(): List<PlannedBake> = runCatching {
        val type = object : TypeToken<List<PlannedBake>>() {}.type
        (gson.fromJson<List<PlannedBake>>(prefs.getString("planned_bakes", "[]"), type) ?: emptyList())
            .filter { it.recipeId.isNotBlank() && it.portions in 1..5 && it.startAtMillis > 0 }
            .distinctBy { it.id }.sortedBy { it.startAtMillis }
    }.getOrDefault(emptyList())
    override fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}
