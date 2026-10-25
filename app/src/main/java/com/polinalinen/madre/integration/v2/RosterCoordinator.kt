package com.polinalinen.madre.integration.v2

import android.content.SharedPreferences
import android.util.Base64
import com.polinalinen.madre.account.TokenCipher
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.voice.VoiceIdentityStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RosterConnection(val credentialHash: String, val connection: String?, val consent: Boolean,
    val revision: Long = 0, val revokePending: Boolean = false)

/** Only scoped consent/technical metadata; identity bearer remains in its existing protected store. */
class RosterConnectionStore(private val prefs: SharedPreferences, private val cipher: TokenCipher) {
    internal fun read(): RosterConnection? {
        val raw = prefs.getString("connection", null) ?: return null
        val plain = requireNotNull(cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP)))
        return com.google.gson.Gson().fromJson(String(plain, Charsets.UTF_8), RosterConnection::class.java).also {
            require(it.credentialHash.matches(Regex("[0-9a-f]{64}")) && it.revision >= 0)
            require(it.connection == null || uuid(it.connection))
        }
    }
    internal fun write(value: RosterConnection) {
        val sealed = cipher.encrypt(com.google.gson.Gson().toJson(value).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("connection", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit())
    }
}

enum class RosterStatus { UNLINKED, ATTACHING, ATTACHED, CONNECTED, OFFLINE, REVOKE_PENDING, ERROR }
data class RosterUiState(val status: RosterStatus = RosterStatus.UNLINKED, val bakes: List<RosterBake> = emptyList(),
    val consent: Boolean = false, val message: String? = null)

/** Foreground-only transport; never selects a bake, enrolls, pairs, or upgrades legacy consent. */
class RosterCoordinator(private val identities: VoiceIdentityStore, private val store: RosterConnectionStore,
    private val api: RosterV2Api, private val fence: Any, private val enabled: () -> Boolean) {
    private val actions = Mutex()
    @Volatile private var controller: RosterController? = null
    @Volatile private var saved: RosterConnection? = null
    @Volatile private var epoch = 0L
    @Volatile private var foreground = false
    @Volatile private var foregroundGeneration = 0L
    @Volatile private var suspended = false
    private val mutable = MutableStateFlow(RosterUiState())
    val state = mutable.asStateFlow()
    private fun hash(credential: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(credential.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun attachController(value: RosterController) = synchronized(fence) { controller = value; epoch++ }
    fun detachController(value: RosterController) = synchronized(fence) {
        if (controller === value) { controller = null; epoch++; offline() }
    }
    fun stopForeground() = synchronized(fence) { foreground = false; foregroundGeneration++; epoch++; offline() }
    private fun offline() { if (mutable.value.status in setOf(RosterStatus.CONNECTED, RosterStatus.ATTACHED, RosterStatus.ATTACHING)) mutable.value = mutable.value.copy(status = RosterStatus.OFFLINE) }
    fun retry() = synchronized(fence) { epoch++; suspended = false }
    suspend fun load() = actions.withLock {
        if (!enabled()) return@withLock
        try {
            synchronized(fence) {
                if (saved == null) saved = store.read()
                val current = saved
                mutable.value = mutable.value.copy(status = when {
                    current?.revokePending == true -> RosterStatus.REVOKE_PENDING
                    current?.consent == true && current.connection != null -> RosterStatus.OFFLINE
                    else -> RosterStatus.UNLINKED
                }, consent = current?.consent == true)
            }
        } catch (_: Exception) { mutable.value = RosterUiState(RosterStatus.ERROR) }
    }

    /** Called only by an explicit scope switch/tap following its full warning, not load/SDK/restore. */
    suspend fun consentFromExplicitTap() = actions.withLock {
        if (!enabled() || !foreground) return@withLock
        val e = synchronized(fence) { epoch++; epoch }
        try {
            val identity = requireNotNull(identities.read())
            check(!identity.revokePending)
            check(saved?.revokePending != true)
            val credential = identity.credential
            val before = saved?.takeIf { it.credentialHash == hash(credential) }
            val candidate = RosterConnection(hash(credential), before?.connection, false, before?.revision ?: 0)
            synchronized(fence) { check(epoch == e && foreground); store.write(candidate); saved = candidate
                mutable.value = RosterUiState(RosterStatus.ATTACHING) }
            val connection = api.attach(credential) // producer independently requires a confirmed paired grant
            synchronized(fence) {
                check(epoch == e && foreground && identities.read() == identity)
                val attached = candidate.copy(connection = connection, consent = true, revision = if (connection == before?.connection) before.revision else 0)
                store.write(attached); saved = attached; suspended = false
                mutable.value = RosterUiState(RosterStatus.ATTACHED, consent = true) // NOT CONNECTED until exact poll
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { synchronized(fence) { if (epoch == e) mutable.value = RosterUiState(RosterStatus.ERROR,
            message = "Подключение не подтверждено. Нужна действующая подтверждённая пара навыка; входа в Яндекс ID недостаточно.") } }
    }

    /** Same commitFence as SQLite. Local refusal wins before the network, even if persistence fails. */
    fun revokeLocally() = synchronized(fence) {
        if (!enabled()) return@synchronized
        epoch++; suspended = true
        try {
            val old = saved ?: store.read()
            saved = old?.copy(consent = false, revokePending = true)
            saved?.let(store::write)
            mutable.value = RosterUiState(if (old?.connection == null) RosterStatus.UNLINKED else RosterStatus.REVOKE_PENDING)
        } catch (_: Exception) { mutable.value = RosterUiState(RosterStatus.ERROR,
            message = "Не удалось сохранить отзыв. Управление на телефоне остановлено; повторите отключение.") }
    }
    suspend fun disconnect() {
        revokeLocally()
        actions.withLock {
            val old = saved ?: return@withLock
            val conn = old.connection ?: return@withLock
            try {
                val identity = requireNotNull(identities.read())
                check(hash(identity.credential) == old.credentialHash)
                api.disconnect(identity.credential, conn)
                synchronized(fence) {
                    check(saved == old)
                    val disabled = old.copy(connection = null, consent = false, revokePending = false)
                    store.write(disabled); saved = disabled; mutable.value = RosterUiState()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = RosterUiState(RosterStatus.REVOKE_PENDING,
                message = "На телефоне отключено. Серверный отзыв не подтверждён; повторите с интернетом.") }
        }
    }

    /** One Activity-owned foreground job. Empty/planned-only installations need no timer service. */
    suspend fun runForeground() {
        if (!enabled()) return
        load()
        val owner = synchronized(fence) { foreground = true; suspended = false; epoch++; foregroundGeneration++; foregroundGeneration }
        try {
            while (currentCoroutineContext().isActive) {
                pollOnce()
                delay(500)
            }
        } finally { synchronized(fence) { if (owner == foregroundGeneration) stopForeground() } }
    }
    internal suspend fun pollOnce() = actions.withLock {
        val c = controller ?: return@withLock
        if (!foreground || !enabled()) return@withLock
        val viewEpoch = epoch
        try {
            // This UUID is only a local DTO envelope, never persisted/attached/sent as authority.
            val local = c.persistedRoster(saved?.connection ?: "00000000-0000-0000-0000-000000000000", 0)
            synchronized(fence) { if (epoch == viewEpoch && foreground) mutable.value = mutable.value.copy(bakes = local.bakes) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { synchronized(fence) { if (epoch == viewEpoch) { suspended = true; mutable.value = mutable.value.copy(status = RosterStatus.ERROR, message = "Не удалось прочитать текущие выпечки. Исходные данные сохранены.") } }; return@withLock }
        val current = saved ?: return@withLock
        val conn = current.connection ?: return@withLock
        val e = epoch
        fun live(): Boolean {
            val identity = identities.read()
            return enabled() && foreground && !suspended && epoch == e && controller === c && saved?.connection == conn &&
                saved?.consent == true && saved?.revokePending == false && identity != null && !identity.revokePending &&
                hash(identity.credential) == current.credentialHash
        }
        try {
            if (!live()) return@withLock
            val identity = requireNotNull(identities.read())
            val next = current.copy(revision = Math.addExact(current.revision, 1))
            synchronized(fence) { check(live()); store.write(next); saved = next }
            val roster = c.persistedRoster(conn, next.revision)
            synchronized(fence) { check(live()); mutable.value = mutable.value.copy(bakes = roster.bakes) }
            val reply = api.poll(identity.credential, roster)
            val received = BakingClock.elapsed()
            check(kotlin.math.abs(reply.serverTime - BakingClock.wallClock()) < 4000)
            synchronized(fence) { check(live()); mutable.value = RosterUiState(RosterStatus.CONNECTED, roster.bakes, true) }
            for (command in reply.commands) {
                if (!live()) break
                val receipt = c.executeRoster(command, reply.serverTime, received, ::live)
                if (receipt != null && live()) api.acknowledge(identity.credential, command, receipt)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { synchronized(fence) { if (epoch == e) { suspended = true; offline() } } }
    }
}
