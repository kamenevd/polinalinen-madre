package com.polinalinen.madre.voice

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.polinalinen.madre.account.yandex.YandexVoiceConsentApi
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

interface VoiceController {
    fun voiceSessions(): List<BakingSession>
    fun voiceRecipeRevision(): String
    suspend fun applyVoice(command: VoiceCommand, receivedElapsed: Long, authority: () -> VoiceAuthority): VoiceCommandReceiptEntity?
}

enum class VoiceStatus { UNLINKED, LOADING, WAITING_LINK, CONNECTED, OFFLINE, REVOKE_PENDING, ERROR }
data class VoiceUiState(val status: VoiceStatus = VoiceStatus.UNLINKED, val selectedBake: String? = null,
    val message: String? = null, val preview: PairPreview? = null, val phoneRequired: Boolean = false,
    val yandexConsentExpiresAt: Long? = null)
data class PairPreview(val challengeId: String, val code: String, val expiresAt: Long, val bakeKey: String,
    val generation: String)

/** Transport/consent only. The controller remains the sole state writer. */
class VoiceCoordinator(private val store: VoiceIdentityStore, private val api: VoiceApi, private val fence: Any,
    private val yandexConsent: YandexVoiceConsentApi? = null) {
    private val actions = Mutex()
    @Volatile private var controller: VoiceController? = null
    @Volatile private var identity: VoiceIdentity? = null
    @Volatile private var foreground = false
    @Volatile private var connection = UUID.randomUUID().toString()
    @Volatile private var epoch = 0L
    private val _state = MutableStateFlow(VoiceUiState())
    val state = _state.asStateFlow()

    fun attach(value: VoiceController) = synchronized(fence) {
        controller = value; epoch++; connection = UUID.randomUUID().toString()
    }
    fun detach(value: VoiceController) = synchronized(fence) {
        if (controller === value) { controller = null; foreground = false; epoch++; offline() }
    }
    fun stopForeground() = synchronized(fence) {
        foreground = false; epoch++; connection = UUID.randomUUID().toString(); offline()
    }
    private fun offline() {
        if (_state.value.status !in setOf(VoiceStatus.UNLINKED, VoiceStatus.REVOKE_PENDING, VoiceStatus.ERROR)) {
            _state.value = _state.value.copy(status = VoiceStatus.OFFLINE)
        }
    }

    suspend fun load() = actions.withLock {
        if (identity == null) {
            val e = epoch
            val saved = withContext(Dispatchers.IO) { store.read() }
            synchronized(fence) {
                if (epoch != e) return@withLock
                identity = saved
                _state.value = VoiceUiState(if (saved?.revokePending == true) VoiceStatus.REVOKE_PENDING
                    else if (saved?.bakeKey != null) VoiceStatus.OFFLINE else VoiceStatus.UNLINKED, saved?.bakeKey)
            }
        }
    }

    /** Explicit on-phone action; never called on restore, from a webhook or merely opening the screen. */
    suspend fun select(bakeKey: String) = actions.withLock {
        selectLocked(bakeKey)
        Unit
    }

    private suspend fun selectLocked(bakeKey: String, allowEnrollment: Boolean = true,
        accountStillSignedIn: () -> Boolean = { true }): Boolean {
        val c = controller ?: return false
        val selected = c.voiceSessions().find { it.bakeKey == bakeKey && !it.isCompleted } ?: return false
        if (selected.recipeRevision != c.voiceRecipeRevision() || selected.trustedPortions == null || !selected.restoredStepValid) {
            _state.value = _state.value.copy(message = "Для этой старой выпечки голосовое управление недоступно. Таймер на телефоне работает.")
            return false
        }
        val before = synchronized(fence) { epoch++; epoch }
        _state.value = _state.value.copy(status = VoiceStatus.LOADING, preview = null, message = null, yandexConsentExpiresAt = null)
        fun current() = epoch == before && controller === c && accountStillSignedIn()
        try {
            val existing = identity ?: withContext(Dispatchers.IO) {
                if (allowEnrollment) store.readOrCreate() else requireNotNull(store.read())
            }
            // Old consent is invalidated locally BEFORE the first network operation.
            val disabled = existing.copy(bakeKey = null, generation = null, revokePending = true)
            withContext(Dispatchers.IO) {
                synchronized(fence) { check(current()); store.write(disabled); identity = disabled }
            }
            if (allowEnrollment) api.post("enroll", existing.credential, mapOf("credential" to existing.credential))
            check(current())
            check(api.post("disconnect", existing.credential).get("revoked")?.asBoolean == true)
            check(current())
            val chosen = existing.copy(bakeKey = bakeKey, generation = UUID.randomUUID().toString(), revokePending = false)
            check(api.post("select", chosen.credential, mapOf("bake_key" to bakeKey, "selection_generation" to chosen.generation)).get("selected")?.asBoolean == true)
            withContext(Dispatchers.IO) {
                synchronized(fence) {
                    check(current())
                    store.write(chosen)
                    identity = chosen; connection = UUID.randomUUID().toString()
                    _state.value = VoiceUiState(VoiceStatus.WAITING_LINK, bakeKey)
                }
            }
            return true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            synchronized(fence) {
                if (epoch == before) _state.value = VoiceUiState(VoiceStatus.ERROR, message = "Не удалось выбрать выпечку. Проверьте интернет и попробуйте снова.")
            }
            return false
        }
    }

    /** Fresh warning + deliberate tap only. No saved job, automatic retry or grant/CONNECTED synthesis. */
    suspend fun prepareYandexFromExplicitTap(bakeKey: String, accountStillSignedIn: () -> Boolean) {
        val consentApi = yandexConsent ?: return
        // Drop overlapping taps rather than queue a second consent action behind the first one.
        if (!actions.tryLock()) return
        try {
            if (!accountStillSignedIn()) return
            if (!selectLocked(bakeKey, allowEnrollment = false, accountStillSignedIn)) return
            val chosen = identity ?: return
            val generation = chosen.generation ?: return
            val e = epoch
            fun current() = epoch == e && identity == chosen && !chosen.revokePending && accountStillSignedIn()
            try {
                synchronized(fence) {
                    check(current())
                    _state.value = _state.value.copy(status = VoiceStatus.LOADING)
                }
                val expiresAt = consentApi.prepareVoiceConsent(chosen.credential, bakeKey, generation)
                synchronized(fence) {
                    if (current()) _state.value = VoiceUiState(VoiceStatus.WAITING_LINK, bakeKey,
                        yandexConsentExpiresAt = expiresAt)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(fence) {
                    if (current()) _state.value = VoiceUiState(VoiceStatus.WAITING_LINK, bakeKey,
                        message = "Не удалось подготовить подключение. Проверьте интернет и нажмите «Подключить Алису» снова.")
                }
            }
        } finally { actions.unlock() }
    }

    /** Synchronous in-memory fence: a sign-out/disconnect wins against any not-yet-committed command. */
    fun revokeLocally() = synchronized(fence) {
        epoch++; connection = UUID.randomUUID().toString()
        identity = identity?.copy(bakeKey = null, generation = null, revokePending = true)
        _state.value = VoiceUiState(VoiceStatus.REVOKE_PENDING)
        if (runCatching { store.revoke() }.isFailure) {
            _state.value = VoiceUiState(VoiceStatus.ERROR, message = "Не удалось сохранить отключение. Не закрывайте книгу; повторите отключение.")
        }
    }

    suspend fun disconnect() {
        revokeLocally()
        actions.withLock { revokeRemote() }
    }
    private suspend fun revokeRemote() {
        try {
            val old = identity ?: withContext(Dispatchers.IO) { store.read() } ?: run {
                _state.value = VoiceUiState(); return
            }
            val disabled = old.copy(bakeKey = null, generation = null, revokePending = true)
            synchronized(fence) { identity = disabled }
            withContext(Dispatchers.IO) { store.write(disabled) }
            check(api.post("disconnect", disabled.credential).get("revoked")?.asBoolean == true)
            val revoked = disabled.copy(revokePending = false)
            withContext(Dispatchers.IO) { store.write(revoked) }
            synchronized(fence) { identity = revoked; _state.value = VoiceUiState() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.value = VoiceUiState(VoiceStatus.REVOKE_PENDING,
            message = "На телефоне отключено. Отзыв доступа на сервере ещё не подтверждён; повторите с интернетом.") }
    }

    suspend fun previewPair(code: String) = actions.withLock {
        val selected = identity ?: return@withLock
        if (selected.bakeKey == null || selected.generation == null || selected.revokePending) return@withLock
        val e = epoch
        try {
            require(code.matches(Regex("[A-Z2-7]{10}")))
            val result = api.post("pair-preview", selected.credential, mapOf("code" to code))
            require(result.get("scope").asString == "bake:read&bake:control")
            val preview = PairPreview(result.get("challenge_id").asString, code, result.get("expires_at").asLong,
                selected.bakeKey, selected.generation)
            synchronized(fence) { if (epoch == e) _state.value = _state.value.copy(preview = preview, message = null) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.value = _state.value.copy(preview = null, message = "Код неверен, истёк или нет связи. Откройте подключение навыка заново.") }
    }

    suspend fun approvePair() = actions.withLock {
        val selected = identity ?: return@withLock
        val p = _state.value.preview ?: return@withLock
        val e = epoch
        if (p.bakeKey != selected.bakeKey || p.generation != selected.generation || p.expiresAt <= BakingClock.wallClock()) return@withLock
        try {
            val r = api.post("pair-approve", selected.credential, mapOf("challenge_id" to p.challengeId,
                "code" to p.code, "bake_key" to p.bakeKey, "selection_generation" to p.generation))
            check(r.get("approved").asBoolean)
            synchronized(fence) { if (epoch == e) _state.value = _state.value.copy(preview = null,
                message = "Разрешение подтверждено. Вернитесь в окно подключения навыка и закончите вход.") }
        } catch (x: CancellationException) { throw x }
        catch (_: Exception) { _state.value = _state.value.copy(preview = null, message = "Подключение не подтверждено. Попробуйте заново.") }
    }

    fun retryConnection() = synchronized(fence) {
        epoch++
        connection = UUID.randomUUID().toString()
        if (_state.value.selectedBake != null) _state.value = _state.value.copy(status = VoiceStatus.OFFLINE,
            message = null, yandexConsentExpiresAt = null)
    }

    fun dismissPairPreview() = synchronized(fence) { _state.value = _state.value.copy(preview = null) }

    /** Called exclusively by the already-foreground timer service, cancelled with its lifecycle. */
    suspend fun runForeground() {
        load()
        synchronized(fence) { foreground = true; epoch++; connection = UUID.randomUUID().toString() }
        var failedConnection: String? = null
        try {
            while (true) {
                val c = controller
                val selected = identity
                val e = epoch
                val conn = connection
                if (!foreground || c == null || selected?.bakeKey == null || selected.generation == null || selected.revokePending) {
                    delay(500); continue
                }
                if (conn == failedConnection) { delay(500); continue }
                val bake = withContext(Dispatchers.Main.immediate) { c.voiceSessions().find { it.bakeKey == selected.bakeKey } }
                if (bake == null || bake.isCompleted) { disconnect(); delay(500); continue }
                try {
                    val snapshot = bake.voiceSnapshot(BakingClock.elapsed(), BakingClock.wallClock(), c.voiceRecipeRevision())
                    val response = api.post("poll", selected.credential, mapOf("connection_id" to conn,
                        "selection_generation" to selected.generation, "snapshot" to snapshot))
                    val received = BakingClock.elapsed()
                    val serverTime = response.get("server_time").asLong
                    check(kotlin.math.abs(serverTime - BakingClock.wallClock()) < 4_000)
                    if (epoch != e || connection != conn || !foreground) continue
                    _state.value = _state.value.copy(status = if (response.get("linked").asBoolean) VoiceStatus.CONNECTED else VoiceStatus.WAITING_LINK)
                    val raw = response.get("command")
                    if (response.get("linked").asBoolean && raw != null && !raw.isJsonNull) {
                        val command = parseCommand(raw.asJsonObject)
                        val receipt = withContext(Dispatchers.Main.immediate) {
                            c.applyVoice(command, received) {
                                VoiceAuthority(identity == selected && !selected.revokePending, foreground && controller === c && epoch == e,
                                    selected.bakeKey, selected.generation, conn, c.voiceRecipeRevision(), serverTime)
                            }
                        }
                        if (receipt?.outcome == VoiceOutcome.PHONE_REQUIRED.name) {
                            _state.value = _state.value.copy(phoneRequired = true,
                                message = "«Испечено», фото и полка — только на телефоне.")
                        }
                        if (receipt != null && epoch == e && foreground) {
                            api.post("ack", selected.credential, mapOf("connection_id" to conn,
                                "selection_generation" to selected.generation, "command_id" to command.id,
                                "bake_key" to receipt.bakeKey, "expected_revision" to receipt.expectedRevision,
                                "status" to receipt.outcome, "new_revision" to receipt.newRevision))
                        }
                    }
                } catch (x: CancellationException) { throw x }
                catch (_: Exception) {
                    synchronized(fence) {
                        if (epoch == e) { epoch++; connection = UUID.randomUUID().toString(); offline(); failedConnection = connection }
                    }
                    delay(1_000)
                }
                delay(100)
            }
        } finally { stopForeground() }
    }

    private fun parseCommand(raw: JsonObject): VoiceCommand {
        val strings = listOf("id", "operation", "bake_key", "recipe_revision", "selection_generation", "connection_id")
        val numbers = listOf("expected_revision", "expected_step", "issued_at", "expires_at")
        strings.forEach { require(raw.get(it)?.isJsonPrimitive == true && raw.getAsJsonPrimitive(it).isString) }
        numbers.forEach { require(raw.get(it)?.isJsonPrimitive == true && raw.getAsJsonPrimitive(it).isNumber && raw.get(it).asString.matches(Regex("[0-9]{1,18}"))) }
        require(raw.get("expected_paused")?.isJsonPrimitive == true && raw.getAsJsonPrimitive("expected_paused").isBoolean)
        require(raw.get("operation").asString in VoiceOperation.entries.map { it.name })
        return Gson().fromJson(raw, VoiceCommand::class.java)
    }
}
