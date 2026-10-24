package com.polinalinen.madre.utils

import android.content.SharedPreferences

/** sessionId -> recordId ledger for exactly-once completion flow. */
interface BakeSessionLedger {
    fun recordIdFor(sessionId: Long): Long?
    fun remember(sessionId: Long, recordId: Long): Boolean
    fun forget(sessionId: Long)
    /** Optional enumeration for restoring undecided finales; old lookup-only implementations remain valid. */
    fun pendingSessionIds(): Set<Long> = emptySet()

    companion object {
        fun key(sessionId: Long): String = "bake_record_session_$sessionId"
    }
}

class PrefsBakeSessionLedger(private val prefs: SharedPreferences) : BakeSessionLedger {

    override fun pendingSessionIds(): Set<Long> = prefs.all.mapNotNull { (key,value) ->
        if(!key.startsWith(KEY_PREFIX) || value !is Long || value<=0L)return@mapNotNull null
        key.removePrefix(KEY_PREFIX).toLongOrNull()?.takeIf { it>=0 && key==BakeSessionLedger.key(it) }
    }.toSet()

    override fun recordIdFor(sessionId: Long): Long? {
        val key = BakeSessionLedger.key(sessionId)
        if (!prefs.contains(key)) return null
        val value = prefs.getLong(key, 0L)
        return value.takeIf { it > 0L }
    }

    override fun remember(sessionId: Long, recordId: Long): Boolean {
        if (recordId <= 0L) return false
        return prefs.edit().putLong(BakeSessionLedger.key(sessionId), recordId).commit()
    }

    override fun forget(sessionId: Long) {
        prefs.edit().remove(BakeSessionLedger.key(sessionId)).apply()
    }

    companion object {
        const val KEY_PREFIX = "bake_record_session_"
    }
}
