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
    val revision: Long = 0, val revokePending: Boolean = false, val bindingKey: String? = null)

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
    internal fun readAttempt(): PairAttempt? {
        val raw = prefs.getString("pair_attempt", null) ?: return null
        val plain = requireNotNull(cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP)))
        return com.google.gson.Gson().fromJson(String(plain, Charsets.UTF_8), PairAttempt::class.java).also {
            require(uuid(it.requestId) && uuid(it.process) && it.createdAt > 0 && it.deadline >= it.createdAt)
            require(it.deadline - it.createdAt <= 300_000 && it.stage != null)
        }
    }
    internal fun writeAttempt(value: PairAttempt?) {
        val editor = prefs.edit()
        if (value == null) editor.remove("pair_attempt") else {
            val sealed = cipher.encrypt(com.google.gson.Gson().toJson(value).toByteArray(Charsets.UTF_8))
            editor.putString("pair_attempt", Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
        check(editor.commit())
    }
    internal fun write(value: RosterConnection) {
        val sealed = cipher.encrypt(com.google.gson.Gson().toJson(value).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("connection", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit())
    }
}

enum class RosterStatus { UNLINKED, ATTACHING, ATTACHED, CONNECTED, OFFLINE, REVOKE_PENDING, ERROR }
/** Data-only pairing detail; frozen screens keep their accepted status vocabulary. */
enum class InstallationPairStatus { NONE, NEEDS_IDENTITY, AUTHORIZATION_PENDING, EXPIRED, INTERRUPTED }
data class RosterUiState(val status: RosterStatus = RosterStatus.UNLINKED, val bakes: List<RosterBake> = emptyList(),
    val consent: Boolean = false, val message: String? = null, val pendingExpiresAt: Long? = null,
    val retryPair: Boolean = false, val pairStatus: InstallationPairStatus = InstallationPairStatus.NONE)

/** Foreground-only explicit installation consent. No provider launch, bake selection or implicit renewal. */
class RosterCoordinator(private val identities: VoiceIdentityStore, private val store: RosterConnectionStore,
    private val api: RosterV2Api, private val fence: Any, private val enabled: () -> Boolean) {
    private val actions = Mutex()
    private val process = java.util.UUID.randomUUID().toString()
    @Volatile private var binding: String? = null
    @Volatile private var attempt: PairAttempt? = null
    private var loaded = false
    // Revision provenance only, never authority. A pending attempt still has no live connection.
    private var previousAttachment: RosterConnection? = null
    /** Called by the existing verified SDK owner, synchronously before account replacement/logout. */
    fun accountChanged(key: String?) = synchronized(fence) {
        if (binding == key) return@synchronized
        val initialIdentity = binding == null && attempt?.stage == PairStage.IDENTITY && attempt?.binding == null
        binding = key; epoch++; suspended = true; previousAttachment = null
        if (!initialIdentity && attempt != null) {
            attempt = attempt?.copy(stage = PairStage.TERMINAL)
            try { store.writeAttempt(attempt) } catch (_: Exception) { /* RAM fence still wins. */ }
            mutable.value = mutable.value.copy(status = RosterStatus.ERROR, pairStatus = InstallationPairStatus.INTERRUPTED, consent = false,
                message = "Аккаунт изменился. Отмените прежнюю попытку и разрешите подключение заново.", retryPair = false)
        } else if (saved?.consent == true) {
            mutable.value = mutable.value.copy(status = RosterStatus.OFFLINE, consent = false)
        }
    }
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
    fun retry() = synchronized(fence) { epoch++; if (attempt == null) suspended = false }
    suspend fun load() = actions.withLock {
        if (!enabled()) return@withLock
        try {
            synchronized(fence) {
                if (loaded) return@synchronized
                saved = store.read(); attempt = store.readAttempt(); loaded = true; suspended = true
                val current = saved
                mutable.value = mutable.value.copy(status = when {
                    current?.revokePending == true -> RosterStatus.REVOKE_PENDING
                    attempt != null -> RosterStatus.ERROR
                    current?.consent == true && current.connection != null -> RosterStatus.OFFLINE
                    else -> RosterStatus.UNLINKED
                }, consent = current?.consent == true && attempt == null,
                    pairStatus = if (attempt != null) InstallationPairStatus.INTERRUPTED else InstallationPairStatus.NONE, message = if (attempt != null)
                    "Попытка прервана. После перезапуска приложение не возобновляет согласие или вход. Отмените её перед новой попыткой." else null)
            }
        } catch (_: Exception) { mutable.value = RosterUiState(RosterStatus.ERROR) }
    }

    /** One UUID per explicit consent. SDK completion/return/retry never calls this method. */
    suspend fun consentFromExplicitTap() = actions.withLock {
        if (!enabled() || !foreground || saved?.revokePending == true || attempt != null) return@withLock
        val now = BakingClock.wallClock()
        synchronized(fence) {
            epoch++; suspended = true
            previousAttachment = saved
            val pending = PairAttempt(java.util.UUID.randomUUID().toString(), process, now, now + 300_000)
            store.writeAttempt(pending); attempt = pending
        }
        continuePairLocked()
    }
    /** Explicit continuation after SDK bind, or same-ID transport retry. No login side effect. */
    suspend fun continuePairFromExplicitTap() = actions.withLock {
        if (!enabled() || !foreground) return@withLock
        continuePairLocked()
    }
    private fun saveAttempt(value: PairAttempt) { store.writeAttempt(value); attempt = value }
    private fun terminal(message: String, pairStatus: InstallationPairStatus = InstallationPairStatus.EXPIRED) {
        attempt?.let { saveAttempt(it.copy(stage = PairStage.TERMINAL)) }
        epoch++; suspended = true
        mutable.value = mutable.value.copy(status = RosterStatus.ERROR, pairStatus = pairStatus, consent = false, message = message, retryPair = false)
    }
    private suspend fun continuePairLocked() {
        var pending = attempt ?: return
        if (pending.process != process || pending.stage == PairStage.TERMINAL) return
        if (!pending.valid(BakingClock.wallClock())) {
            synchronized(fence) { terminal("Время попытки истекло. Отмените её и дайте согласие заново.") }; return
        }
        val key = binding
        if (key == null) {
            mutable.value = mutable.value.copy(status = RosterStatus.ERROR, pairStatus = InstallationPairStatus.NEEDS_IDENTITY, consent = false,
                message = "Для подключения нужен проверенный аккаунт Яндекс ID. Вход сам по себе не даёт Алисе доступ.")
            return
        }
        val e = synchronized(fence) { epoch++; epoch }
        try {
            val identity = requireNotNull(identities.read()); check(!identity.revokePending && saved?.revokePending != true)
            val credential = identity.credential
            synchronized(fence) {
                check(epoch == e && foreground && binding == key)
                check(pending.binding == null || pending.binding == key)
                check(pending.credentialHash == null || pending.credentialHash == hash(credential))
                pending = pending.copy(binding = key, credentialHash = hash(credential),
                    stage = if (pending.stage == PairStage.IDENTITY) PairStage.PROBE else pending.stage)
                saveAttempt(pending)
                // A pending challenge never inherits a previous attached connection.
                val local = RosterConnection(hash(credential), null, false)
                store.write(local); saved = local
                mutable.value = mutable.value.copy(status = RosterStatus.ATTACHING, consent = false, message = null, retryPair = false, pairStatus = InstallationPairStatus.NONE)
            }
            fun live() = epoch == e && foreground && binding == key && identities.read() == identity &&
                attempt?.requestId == pending.requestId && pending.valid(BakingClock.wallClock())
            if (pending.stage == PairStage.PROBE) {
                try {
                    val connection = api.attach(credential)
                    synchronized(fence) { check(live()); acceptAttach(connection, credential) }
                    return
                } catch (failure: ContractFailure) {
                    if (failure.kind != ContractFailure.Kind.PAIR_REQUIRED) throw failure
                    synchronized(fence) { check(live()); pending = pending.copy(stage = PairStage.CONSENT); saveAttempt(pending) }
                }
            }
            if (pending.stage == PairStage.CONSENT) {
                val reply = try { api.pairConsent(credential, InstallationPairConsent(pending.requestId)) }
                catch (failure: ContractFailure) {
                    if (failure.kind != ContractFailure.Kind.ALREADY_PAIRED) throw failure
                    val connection = api.attach(credential)
                    synchronized(fence) { check(live()); acceptAttach(connection, credential) }; return
                }
                synchronized(fence) {
                    check(live()); require(reply.requestId == pending.requestId)
                    val now = BakingClock.wallClock()
                    require(reply.expiresAt > now && reply.expiresAt - now <= 300_000)
                    pending = pending.copy(stage = PairStage.PENDING, consentExpiresAt = reply.expiresAt,
                        deadline = minOf(pending.deadline, reply.expiresAt))
                    saveAttempt(pending); showPending()
                }
            } else if (pending.stage == PairStage.PENDING) {
                // Returning from Dialogs is explicit. Only confirmed attach followed by validated poll earns CONNECTED.
                try {
                    val connection = api.attach(credential)
                    synchronized(fence) { check(live()); acceptAttach(connection, credential) }
                } catch (failure: ContractFailure) {
                    if (failure.kind != ContractFailure.Kind.PAIR_REQUIRED) throw failure
                    synchronized(fence) { check(live()); showPending("Привязка навыка ещё не подтверждена. Завершите её в Диалогах и проверьте снова.") }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            synchronized(fence) {
                if (epoch == e) {
                    val kind = (failure as? ContractFailure)?.kind
                    if (!pending.valid(BakingClock.wallClock())) {
                        terminal("Время попытки истекло. Отмените её и дайте согласие заново.")
                    } else if (kind in setOf(ContractFailure.Kind.CHALLENGE_CONSUMED, ContractFailure.Kind.BINDING_CHANGED,
                            ContractFailure.Kind.BINDING_REQUIRED, ContractFailure.Kind.UNAUTHORIZED)) {
                        terminal("Попытка больше недействительна. Отмените её; затем проверьте аккаунт и разрешите подключение заново.", InstallationPairStatus.INTERRUPTED)
                    } else mutable.value = mutable.value.copy(status = RosterStatus.ERROR, consent = false,
                        message = "Подключение не подтверждено. Повторите проверку с интернетом: номер и срок этой попытки не изменятся.",
                        retryPair = true)
                }
            }
        }
    }
    private fun showPending(message: String? = null) {
        mutable.value = mutable.value.copy(status = RosterStatus.ERROR, pairStatus = InstallationPairStatus.AUTHORIZATION_PENDING, consent = false,
            message = message, pendingExpiresAt = attempt?.deadline, retryPair = false)
    }
    private fun acceptAttach(connection: String, credential: String) {
        val attached = RosterConnection(hash(credential), connection, true,
            revision = listOfNotNull(saved, previousAttachment)
                .filter { it.connection == connection && it.credentialHash == hash(credential) }
                .maxOfOrNull { it.revision } ?: 0, bindingKey = binding)
        store.write(attached); store.writeAttempt(null); saved = attached; attempt = null; previousAttachment = null; suspended = false
        mutable.value = mutable.value.copy(status = RosterStatus.ATTACHED, consent = true, message = null,
            pendingExpiresAt = null, retryPair = false, pairStatus = InstallationPairStatus.NONE)
    }
    /** Reattach stable permission only on an explicit check; never silently start a new consent. */
    suspend fun checkFromExplicitTap() {
        if (attempt != null) { continuePairFromExplicitTap(); return }
        actions.withLock {
            if (!enabled() || !foreground || saved?.consent != true || saved?.revokePending == true) return@withLock
            val e = synchronized(fence) { epoch++; epoch }
            val key = binding ?: return@withLock
            try {
                check(saved?.bindingKey == key)
                val identity = requireNotNull(identities.read()); check(!identity.revokePending)
                check(saved?.credentialHash == hash(identity.credential))
                val connection = api.attach(identity.credential)
                synchronized(fence) { check(epoch == e && foreground && binding == key && identities.read() == identity)
                    acceptAttach(connection, identity.credential) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(fence) { if (epoch == e) {
                suspended = true; mutable.value = mutable.value.copy(status = RosterStatus.ERROR,
                    message = "Разрешение или связь не подтверждены. Повторите проверку либо отключите Алису перед новым согласием.")
            } } }
        }
    }

    /** Same commitFence as SQLite. Local refusal wins before the network, even if persistence fails. */
    fun revokeLocally() = synchronized(fence) {
        if (!enabled()) return@synchronized
        epoch++; suspended = true; previousAttachment = null
        try {
            attempt = (attempt ?: store.readAttempt())?.copy(stage = PairStage.TERMINAL)
            store.writeAttempt(attempt)
            val old = saved ?: store.read()
            saved = old?.copy(consent = false, revokePending = true)
            saved?.let(store::write)
            mutable.value = RosterUiState(if (old == null && attempt?.credentialHash == null) RosterStatus.UNLINKED else RosterStatus.REVOKE_PENDING)
        } catch (_: Exception) { mutable.value = RosterUiState(RosterStatus.ERROR,
            message = "Не удалось сохранить отзыв. Управление на телефоне остановлено; повторите отключение.") }
    }
    suspend fun disconnect() {
        revokeLocally()
        actions.withLock {
            val old = saved
            val conn = old?.connection
            if (old == null && attempt?.credentialHash == null) {
                synchronized(fence) { store.writeAttempt(null); attempt = null; mutable.value = RosterUiState() }
                return@withLock
            }
            val e = epoch
            try {
                val identity = requireNotNull(identities.read())
                check(hash(identity.credential) == (old?.credentialHash ?: attempt?.credentialHash))
                if (conn != null) api.disconnect(identity.credential, conn) else api.cancelPair(identity.credential)
                synchronized(fence) {
                    check(saved == old && epoch == e && identities.read() == identity)
                    val disabled = (old ?: RosterConnection(hash(identity.credential), null, false))
                        .copy(connection = null, consent = false, revokePending = false)
                    store.write(disabled); store.writeAttempt(null); attempt = null; saved = disabled; mutable.value = RosterUiState()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(fence) { if (epoch == e) mutable.value = RosterUiState(RosterStatus.REVOKE_PENDING,
                message = "На телефоне отключено. Серверный отзыв не подтверждён; повторите с интернетом.") } }
        }
    }

    /** One Activity-owned foreground job. Empty/planned-only installations need no timer service. */
    suspend fun runForeground() {
        if (!enabled()) return
        load()
        val owner = synchronized(fence) { foreground = true; epoch++; foregroundGeneration++; foregroundGeneration }
        try {
            while (currentCoroutineContext().isActive) {
                pollOnce()
                delay(500)
            }
        } finally { synchronized(fence) { if (owner == foregroundGeneration) stopForeground() } }
    }
    internal suspend fun pollOnce() = actions.withLock {
        if (!foreground || !enabled()) return@withLock
        synchronized(fence) {
            attempt?.takeIf { it.process == process && it.stage != PairStage.TERMINAL && !it.valid(BakingClock.wallClock()) }
                ?.let { terminal("Время попытки истекло. Отмените её и дайте согласие заново.") }
        }
        val c = controller ?: return@withLock
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
                saved?.consent == true && saved?.revokePending == false && binding != null && binding == current.bindingKey && identity != null && !identity.revokePending &&
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
