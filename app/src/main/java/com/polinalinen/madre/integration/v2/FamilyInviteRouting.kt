package com.polinalinen.madre.integration.v2

import android.content.Intent

/** Manifest filter is only dispatch; this literal validator is the security boundary. */
object FamilyInviteRouting {
    class Queue(private val enabled: () -> Boolean) {
        val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        fun accept(intent: Intent?) { read(intent, enabled())?.let { pending.value = it } }
        fun restore(url: String?) {
            pending.value = if (enabled()) url?.let { FamilyInvitation.parse(it)?.savedInviteUrl() } else null
        }
    }
    fun read(intent: Intent?, enabled: Boolean): String? {
        if (!enabled || intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString?.let { FamilyInvitation.parse(it)?.savedInviteUrl() }
    }
}
