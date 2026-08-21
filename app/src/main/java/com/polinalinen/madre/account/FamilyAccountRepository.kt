package com.polinalinen.madre.account

import com.polinalinen.madre.data.remote.AuthResponse
import com.polinalinen.madre.data.remote.CreateFamilyRequest
import com.polinalinen.madre.data.remote.FamilyBookApi
import com.polinalinen.madre.data.remote.FamilyResponse
import com.polinalinen.madre.data.remote.JoinFamilyRequest
import com.polinalinen.madre.data.remote.PasswordAuthRequest
import com.polinalinen.madre.data.remote.PasswordResetRequest
import com.polinalinen.madre.data.remote.RegisterRequest
import com.polinalinen.madre.data.remote.RenameFamilyRequest
import com.polinalinen.madre.data.remote.UserRecord
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Вход в общую книгу и работа с семьёй (DESIGN-V4.md Cycle 11, фича 28).
 *
 * Ничего локального этот слой не трогает: Room живёт сам по себе и про
 * аккаунт не знает — своя книга обязана открываться и без входа, и без сети.
 * Здесь только токен, аккаунт и честный ответ о том, что случилось.
 *
 * Токен выбрасывается ровно в одном случае — когда сервер сказал, что он
 * больше не годится. Отсутствие сети токен не трогает: иначе поездка в метро
 * выкидывала бы читателя из книги.
 */
class FamilyAccountRepository(
    private val api: FamilyBookApi,
    private val tokens: AuthTokenStore,
) {
    private val mutex = Mutex()
    private val authGate = Any()
    private val revision = AtomicLong(0)
    private val token = AtomicReference<String?>(null)

    private val _state = MutableStateFlow<FamilyBookState>(FamilyBookState.SignedOut)
    val state: StateFlow<FamilyBookState> = _state.asStateFlow()

    /** Проверить сохранённый токен на старте. Токена нет — сети тоже не будет. */
    suspend fun restore(): FamilyBookState = mutex.withLock { restoreLocked() }

    /**
     * Перечитать запись семьи без повторного входа: владелец и имя полки могли
     * измениться, а токен остаётся тем же.
     */
    suspend fun refresh(): FamilyBookState = mutex.withLock { refreshLocked() }

    suspend fun signIn(email: String, password: String): FamilyBookState =
        mutex.withLock {
            val seen = publishLocked(FamilyBookState.Loading(_state.value.account))
            signInLocked(email, password, seen)
        }

    /**
     * Письмо со сбросом на ту же почту, что в поле входа. Токен и локальную
     * книгу не трогает: неудача — строка на форме, не выход из книги.
     */
    suspend fun requestPasswordReset(email: String): PasswordResetResult =
        mutex.withLock { requestPasswordResetLocked(email) }

    /** Регистрация сразу заводит и вход — отдельного экрана «теперь войдите» нет. */
    suspend fun register(email: String, password: String, displayName: String): FamilyBookState =
        mutex.withLock {
            val seen = publishLocked(FamilyBookState.Loading(_state.value.account))
            runCatching { api.register(RegisterRequest(email, password, password, displayName)) }
                .getOrElse { return failLocked(seen, NetworkFailure.classify(it, NetworkFailure.INVALID_CREDENTIALS)) }
            signInLocked(email, password, seen)
        }

    suspend fun createFamily(name: String): FamilyBookState =
        mutex.withLock {
            val authorization = token.get() ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val current = _state.value.account ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val seen = publishLocked(FamilyBookState.Loading(current))
            val response = runCatching { api.createFamily(authorization, CreateFamilyRequest(name)) }
                .getOrElse { return failLocked(seen, NetworkFailure.classify(it)) }
            rememberLocked(seen, current, response)
        }

    /**
     * Код разбирается до запроса, и отказ выглядит одинаково при любом изъяне:
     * ни по ответу сервера, ни по нашей проверке нельзя понять, существует ли
     * книга с таким кодом.
     */
    suspend fun joinFamily(code: String): FamilyBookState =
        mutex.withLock {
            val authorization = token.get() ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val current = _state.value.account ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val normalized = InviteCode.normalize(code) ?: return rejectedWithoutLoadingLocked()
            val seen = publishLocked(FamilyBookState.Loading(current))
            val response = runCatching { api.joinFamily(authorization, JoinFamilyRequest(normalized)) }
                .getOrElse {
                    val failure = NetworkFailure.classify(it, NetworkFailure.REJECTED)
                    return if (failure == NetworkFailure.REJECTED) rejectedLocked(seen) else failLocked(seen, failure)
                }
            rememberLocked(seen, current, response)
        }

    /** Сменить код приглашения: старый перестаёт работать сразу. */
    suspend fun rotateInviteCode(): FamilyBookState =
        mutex.withLock {
            val authorization = token.get() ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val current = _state.value.account ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            if (!current.isFamilyOwner) return failWithoutLoadingLocked(NetworkFailure.NOT_OWNER)
            val seen = publishLocked(FamilyBookState.Loading(current))
            val response = runCatching { api.rotateInviteCode(authorization) }
                .getOrElse { return failLocked(seen, NetworkFailure.classify(it)) }
            rememberLocked(seen, current, response)
        }

    /**
     * Новое название полки. Старые строки формуляра хранят снимок имени на
     * сервере и здесь не переписываются — меняется только живой заголовок.
     */
    suspend fun renameFamily(name: String): FamilyBookState =
        mutex.withLock {
            val authorization = token.get() ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val current = _state.value.account ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            if (!current.isFamilyOwner) return failWithoutLoadingLocked(NetworkFailure.NOT_OWNER)
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return failWithoutLoadingLocked(NetworkFailure.UNKNOWN)
            val seen = publishLocked(FamilyBookState.Loading(current))
            val response = runCatching { api.renameFamily(authorization, RenameFamilyRequest(trimmed)) }
                .getOrElse { return failLocked(seen, NetworkFailure.classify(it, NetworkFailure.NOT_OWNER)) }
            val updated = current.copy(familyName = response.familyName)
            publishFreshLocked(seen, FamilyBookState.SignedIn(updated))
        }

    /**
     * Уйти с полки, остаться в книге на телефоне. Токен не выбрасывается:
     * это не выход из аккаунта.
     */
    suspend fun leaveFamily(): FamilyBookState =
        mutex.withLock {
            val authorization = token.get() ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val current = _state.value.account ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
            val seen = publishLocked(FamilyBookState.Loading(current))
            runCatching { api.leaveFamily(authorization) }
                .getOrElse { return failLocked(seen, NetworkFailure.classify(it)) }
            val updated = current.copy(
                familyId = null,
                familyName = null,
                inviteCode = null,
                familyOwnerId = null,
            )
            publishFreshLocked(seen, FamilyBookState.SignedIn(updated))
        }

    suspend fun listFamilyUsers(): List<UserRecord> =
        mutex.withLock { listFamilyUsersLocked() }

    fun signOut(): FamilyBookState =
        synchronized(authGate) {
            revision.incrementAndGet()
            token.set(null)
            tokens.clear()
            _state.value = FamilyBookState.SignedOut
            FamilyBookState.SignedOut
        }

    fun currentAccount(): FamilyAccount? = _state.value.account

    /**
     * Забыть открытый код: сервер отдаёт его один раз, и после показа он не
     * должен всплыть снова.
     */
    fun clearInviteCode() {
        synchronized(authGate) {
            revision.incrementAndGet()
            _state.value = _state.value.withoutInviteCode()
        }
    }

    private suspend fun restoreLocked(): FamilyBookState {
        val stored = tokens.read() ?: return forgetLocked()
        val seen = publishLocked(FamilyBookState.Loading(_state.value.account))
        val response = runCatching { api.authRefresh(stored) }
            .getOrElse { return failLocked(seen, NetworkFailure.classify(it, NetworkFailure.SIGNED_OUT)) }
        return adoptLocked(seen, response)
    }

    private suspend fun refreshLocked(): FamilyBookState {
        val authorization = token.get() ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
        val current = _state.value.account ?: return failWithoutLoadingLocked(NetworkFailure.SIGNED_OUT)
        if (!current.hasFamily) return publishLockedState(FamilyBookState.SignedIn(current))
        val seen = publishLocked(FamilyBookState.Loading(current))
        val family = runCatching { api.family(authorization, current.familyId!!) }.getOrNull()
            ?: return publishFreshLocked(seen, FamilyBookState.SignedIn(current))
        val updated = current.copy(
            familyName = family.name,
            familyOwnerId = family.owner,
        )
        return publishFreshLocked(seen, FamilyBookState.SignedIn(updated))
    }

    private suspend fun signInLocked(email: String, password: String, seen: Long): FamilyBookState {
        val response = runCatching { api.authWithPassword(PasswordAuthRequest(email, password)) }
            .getOrElse { return failLocked(seen, NetworkFailure.classify(it, NetworkFailure.INVALID_CREDENTIALS)) }
        return adoptLocked(seen, response)
    }

    private suspend fun requestPasswordResetLocked(email: String): PasswordResetResult {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return PasswordResetResult.Failed(PasswordReset.EMPTY_EMAIL)
        val response = runCatching { api.requestPasswordReset(PasswordResetRequest(trimmed)) }
            .getOrElse {
                return PasswordResetResult.Failed(PasswordReset.messageFor(NetworkFailure.classify(it)))
            }
        if (response.isSuccessful) return PasswordResetResult.Sent
        val failure = when (response.code()) {
            400, 404, 422 -> NetworkFailure.INVALID_CREDENTIALS
            in 500..599 -> NetworkFailure.SERVER
            else -> NetworkFailure.UNKNOWN
        }
        return PasswordResetResult.Failed(PasswordReset.messageFor(failure))
    }

    private suspend fun listFamilyUsersLocked(): List<UserRecord> {
        val authorization = token.get() ?: throw IllegalStateException("Missing token for listFamilyUsers")
        return api.listFamilyUsers(authorization).items
    }

    private suspend fun adoptLocked(seen: Long, response: AuthResponse): FamilyBookState {
        val familyId = response.record.family.orEmpty().takeIf { it.isNotBlank() }
        // Название и владелец семьи — отдельный запрос, и его провал не повод
        // не пускать читателя внутрь: книга просто побудет пока безымянной.
        val family = familyId?.let { id -> runCatching { api.family(response.token, id) }.getOrNull() }
        val restored = FamilyAccount(
            userId = response.record.id,
            email = response.record.email,
            displayName = response.record.name,
            familyId = familyId,
            familyName = family?.name,
            familyOwnerId = family?.owner,
        )
        return synchronized(authGate) {
            if (revision.get() != seen && _state.value is FamilyBookState.SignedOut) {
                return@synchronized _state.value
            }
            token.set(response.token)
            tokens.write(response.token)
            publishFreshUnderGate(seen, FamilyBookState.SignedIn(restored))
        }
    }

    private suspend fun rememberLocked(seen: Long, current: FamilyAccount, response: FamilyResponse): FamilyBookState {
        val ownerId = if (response.inviteCode != null) {
            current.userId
        } else {
            token.get()?.let { authorization ->
                runCatching { api.family(authorization, response.familyId).owner }.getOrNull()
            }
        }
        val updated = current.copy(
            familyId = response.familyId,
            familyName = response.familyName,
            inviteCode = response.inviteCode,
            familyOwnerId = ownerId,
        )
        return publishFreshLocked(seen, FamilyBookState.SignedIn(updated))
    }

    private fun forgetLocked(): FamilyBookState =
        synchronized(authGate) {
            token.set(null)
            tokens.clear()
            revision.incrementAndGet()
            _state.value = FamilyBookState.SignedOut
            FamilyBookState.SignedOut
        }

    private fun failLocked(seen: Long, failure: NetworkFailure): FamilyBookState =
        synchronized(authGate) {
            if (failure == NetworkFailure.SIGNED_OUT) {
                token.set(null)
                tokens.clear()
                return@synchronized publishFreshUnderGate(seen, FamilyBookState.Failed(failure))
            }
            publishFreshUnderGate(seen, FamilyBookState.Failed(failure, _state.value.account))
        }

    private fun rejectedLocked(seen: Long): FamilyBookState =
        publishFreshLocked(seen, FamilyBookState.Failed(NetworkFailure.REJECTED, _state.value.account))

    private fun failWithoutLoadingLocked(failure: NetworkFailure): FamilyBookState =
        synchronized(authGate) {
            if (failure == NetworkFailure.SIGNED_OUT) {
                token.set(null)
                tokens.clear()
                revision.incrementAndGet()
                val next = FamilyBookState.Failed(failure)
                _state.value = next
                return@synchronized next
            }
            revision.incrementAndGet()
            val next = FamilyBookState.Failed(failure, _state.value.account)
            _state.value = next
            next
        }

    private fun rejectedWithoutLoadingLocked(): FamilyBookState =
        synchronized(authGate) {
            revision.incrementAndGet()
            val next = FamilyBookState.Failed(NetworkFailure.REJECTED, _state.value.account)
            _state.value = next
            next
        }

    private fun publishLocked(next: FamilyBookState): Long =
        synchronized(authGate) {
            val nextRevision = revision.incrementAndGet()
            _state.value = next
            nextRevision
        }

    private fun publishLockedState(next: FamilyBookState): FamilyBookState {
        publishLocked(next)
        return next
    }

    private fun publishFreshLocked(seen: Long, next: FamilyBookState): FamilyBookState =
        synchronized(authGate) { publishFreshUnderGate(seen, next) }

    private fun publishFreshUnderGate(seen: Long, next: FamilyBookState): FamilyBookState {
        if (revision.get() != seen) {
            val current = _state.value
            if (current is FamilyBookState.SignedOut) return current
            val sanitized = next.withoutInviteCode()
            revision.incrementAndGet()
            _state.value = sanitized
            return sanitized
        }
        revision.incrementAndGet()
        _state.value = next
        return next
    }
}
