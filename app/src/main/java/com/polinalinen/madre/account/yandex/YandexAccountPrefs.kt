package com.polinalinen.madre.account.yandex

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.yandexAccountDataStore by preferencesDataStore(name = "account_prefs")

/** Dedicated metadata only. There is deliberately no API accepting a login token. */
interface YandexAccountStorage {
    suspend fun read(): YandexAccountSnapshot
    suspend fun markOfferSeen()
    suspend fun storeVerified(profile: VerifiedYandexProfile)
    /** Atomically retain retry until PB also succeeds; stores only verified Alice metadata. */
    suspend fun storeAliceBound(profile: VerifiedYandexProfile) {
        // Phase-A-only stores must fail closed instead of losing the partial-success marker.
        // The production DataStore implementation below writes profile+retry atomically.
        throw YandexAccountFailure(YandexAccountError.STORAGE)
    }
    suspend fun requireForegroundRetry()
    suspend fun clearAccount(unbindPending: Boolean)
    suspend fun acknowledgeUnbind()
}

class YandexAccountPrefs internal constructor(private val store: DataStore<Preferences>) : YandexAccountStorage {
    constructor(context: Context) : this(context.applicationContext.yandexAccountDataStore)

    override suspend fun read(): YandexAccountSnapshot {
        val p = store.data.first()
        val profile = runCatching {
            VerifiedYandexProfile(
                uidHash = p[UID_HASH] ?: return@runCatching null,
                login = p[LOGIN] ?: return@runCatching null,
                displayName = p[DISPLAY_NAME].orEmpty(),
                avatarId = p[AVATAR_ID],
                avatarEmpty = p[AVATAR_EMPTY] ?: true,
                boundAtMillis = p[BOUND_AT] ?: return@runCatching null,
            )
        }.getOrNull()
        return YandexAccountSnapshot(profile, p[OFFER_SEEN] ?: false,
            p[RETRY_REQUIRED] ?: false, p[UNBIND_PENDING] ?: false,
            p.asMap().keys.any { it.name.startsWith(PREFIX) && it.name !in setOf(OFFER_SEEN.name, RETRY_REQUIRED.name, UNBIND_PENDING.name) })
    }

    override suspend fun markOfferSeen() = update { this[OFFER_SEEN] = true }

    override suspend fun storeVerified(profile: VerifiedYandexProfile) = storeProfile(profile, retry = false)
    override suspend fun storeAliceBound(profile: VerifiedYandexProfile) = storeProfile(profile, retry = true)

    private suspend fun storeProfile(profile: VerifiedYandexProfile, retry: Boolean) = update {
        this[UID_HASH] = profile.uidHash
        this[LOGIN] = profile.login
        this[DISPLAY_NAME] = profile.displayName
        profile.avatarId?.let { this[AVATAR_ID] = it } ?: remove(AVATAR_ID)
        this[AVATAR_EMPTY] = profile.avatarEmpty
        this[BOUND_AT] = profile.boundAtMillis
        this[RETRY_REQUIRED] = retry
        this[UNBIND_PENDING] = false
    }

    override suspend fun requireForegroundRetry() = update { this[RETRY_REQUIRED] = true }

    override suspend fun clearAccount(unbindPending: Boolean) = update {
        val seen = this[OFFER_SEEN] ?: false
        asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { remove(it) }
        this[OFFER_SEEN] = seen
        this[RETRY_REQUIRED] = false
        this[UNBIND_PENDING] = unbindPending
    }

    override suspend fun acknowledgeUnbind() = update { this[UNBIND_PENDING] = false }

    private suspend fun update(block: MutablePreferences.() -> Unit) {
        store.edit { p ->
            // Reject unknown auth keys, including remnants of any incompatible old implementation.
            p.asMap().keys.filter { it.name.startsWith(PREFIX) && it.name !in ALLOWED }
                .forEach { p.remove(it) }
            block(p)
        }
    }

    private companion object {
        const val PREFIX = "yandex_"
        val UID_HASH = stringPreferencesKey("yandex_uid_hash")
        val LOGIN = stringPreferencesKey("yandex_login")
        val DISPLAY_NAME = stringPreferencesKey("yandex_display_name")
        val AVATAR_ID = stringPreferencesKey("yandex_avatar_id")
        val AVATAR_EMPTY = booleanPreferencesKey("yandex_avatar_empty")
        val BOUND_AT = longPreferencesKey("yandex_bound_at")
        val OFFER_SEEN = booleanPreferencesKey("yandex_offer_seen")
        val RETRY_REQUIRED = booleanPreferencesKey("yandex_foreground_retry_required")
        val UNBIND_PENDING = booleanPreferencesKey("yandex_unbind_pending")
        val ALLOWED = setOf(UID_HASH.name, LOGIN.name, DISPLAY_NAME.name, AVATAR_ID.name,
            AVATAR_EMPTY.name, BOUND_AT.name, OFFER_SEEN.name, RETRY_REQUIRED.name, UNBIND_PENDING.name)
    }
}
