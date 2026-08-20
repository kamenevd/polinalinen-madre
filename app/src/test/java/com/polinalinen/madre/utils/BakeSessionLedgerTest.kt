package com.polinalinen.madre.utils

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BakeSessionLedgerTest {

    @Test
    fun `remember stores record id and forget removes it`() {
        val prefs = MemorySharedPreferences()
        val ledger = PrefsBakeSessionLedger(prefs)

        assertThat(ledger.remember(sessionId = 5L, recordId = 77L)).isTrue()
        assertThat(ledger.recordIdFor(5L)).isEqualTo(77L)

        ledger.forget(5L)
        assertThat(ledger.recordIdFor(5L)).isNull()
    }

    @Test
    fun `key format is stable`() {
        assertThat(BakeSessionLedger.key(123L)).isEqualTo("bake_record_session_123")
    }

    @Test
    fun `failed commit does not mark pointer as ready`() {
        val prefs = MemorySharedPreferences(commitSucceeds = false)
        val ledger = PrefsBakeSessionLedger(prefs)

        assertThat(ledger.remember(sessionId = 9L, recordId = 101L)).isFalse()
        assertThat(ledger.recordIdFor(9L)).isNull()
    }
}

private class MemorySharedPreferences(
    private val commitSucceeds: Boolean = true,
) : SharedPreferences {
    private val data = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        ((data[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet())

    override fun getInt(key: String?, defValue: Int): Int =
        (data[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (data[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (data[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class EditorImpl : SharedPreferences.Editor {
        private val staged = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            if (!commitSucceeds) return false
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            if (clearRequested) data.clear()
            staged.forEach { (key, value) ->
                if (value == null) data.remove(key) else data[key] = value
            }
        }
    }
}
