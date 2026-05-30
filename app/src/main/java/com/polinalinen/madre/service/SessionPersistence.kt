package com.polinalinen.madre.service

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe

/**
 * Persist active baking sessions to SharedPreferences.
 * Survives process death (force-stop, low memory kill).
 */
object SessionPersistence {

    private const val PREFS_NAME = "levito_sessions"
    private const val KEY_SESSIONS = "active_sessions"
    private const val KEY_REMAINING = "remaining_seconds"

    private val gson = Gson()

    data class SavedSession(
        val id: String,
        val name: String,
        val recipe: Recipe,
        val currentStepIndex: Int,
        val stepStartedAtMillis: Long,
        val isPaused: Boolean,
        val remainingSeconds: Long,
        val completedAt: Long? = null
    )

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save a single active session
     */
    fun saveSession(
        context: Context,
        id: String,
        name: String,
        session: BakingSession,
        remainingSeconds: Long
    ) {
        val sessions = loadAll(context).toMutableMap()
        sessions[id] = SavedSession(
            id = id,
            name = name,
            recipe = session.recipe,
            currentStepIndex = session.currentStepIndex,
            stepStartedAtMillis = session.stepStartedAtMillis,
            isPaused = session.isPaused,
            remainingSeconds = remainingSeconds,
            completedAt = session.completedAt
        )
        saveAll(context, sessions)
    }

    /**
     * Remove a session by id
     */
    fun removeSession(context: Context, id: String) {
        val sessions = loadAll(context).toMutableMap()
        sessions.remove(id)
        saveAll(context, sessions)
    }

    /**
     * Load all saved sessions
     */
    fun loadAll(context: Context): Map<String, SavedSession> {
        try {
            val json = prefs(context).getString(KEY_SESSIONS, null) ?: return emptyMap()
            val type = object : TypeToken<Map<String, SavedSession>>() {}.type
            return gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            return emptyMap()
        }
    }

    /**
     * Clear all sessions (e.g. on app data clear)
     */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Remove completed sessions (cleanup)
     */
    fun cleanupCompleted(context: Context) {
        val sessions = loadAll(context).toMutableMap()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val s = entry.value
            if (s.completedAt != null) {
                iterator.remove()
            }
        }
        saveAll(context, sessions)
    }

    private fun saveAll(context: Context, sessions: Map<String, SavedSession>) {
        val json = gson.toJson(sessions)
        prefs(context).edit().putString(KEY_SESSIONS, json).apply()
    }

    /**
     * Restore ActiveSession from saved data
     */
    fun restoreActiveSession(saved: SavedSession): Triple<String, String, BakingSession> {
        val session = BakingSession(
            recipe = saved.recipe,
            currentStepIndex = saved.currentStepIndex,
            stepStartedAtMillis = saved.stepStartedAtMillis,
            isPaused = saved.isPaused,
            completedAt = saved.completedAt
        )
        return Triple(saved.id, saved.name, session)
    }

    fun getRemainingSeconds(saved: SavedSession): Long {
        return saved.remainingSeconds
    }
}
