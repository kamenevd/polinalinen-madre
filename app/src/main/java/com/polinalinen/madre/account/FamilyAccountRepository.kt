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

    private var token: String? = null
    private var account: FamilyAccount? = null

    /** Проверить сохранённый токен на старте. Токена нет — сети тоже не будет. */
    suspend fun restore(): FamilyBookState {
        val stored = tokens.read() ?: return forget()
        val response = runCatching { api.authRefresh(stored) }
            .getOrElse { return fail(NetworkFailure.classify(it, NetworkFailure.SIGNED_OUT)) }
        return adopt(response)
    }

    /**
     * Re-read current family record (incl. owner) without full re-auth.
     * Needed so a surviving member sees themselves as owner after previous owner left,
     * without forcing re-login. Does not touch token.
     */
    suspend fun refresh(): FamilyBookState {
        val authorization = token ?: return fail(NetworkFailure.SIGNED_OUT)
        val current = account ?: return fail(NetworkFailure.SIGNED_OUT)
        if (!current.hasFamily) return FamilyBookState.SignedIn(current)
        val family = runCatching { api.family(authorization, current.familyId!!) }.getOrNull()
            ?: return FamilyBookState.SignedIn(current)
        val updated = current.copy(
            familyName = family.name,
            familyOwnerId = family.owner,
        )
        account = updated
        return FamilyBookState.SignedIn(updated)
    }

    suspend fun signIn(email: String, password: String): FamilyBookState {
        val response = runCatching { api.authWithPassword(PasswordAuthRequest(email, password)) }
            .getOrElse { return fail(NetworkFailure.classify(it, NetworkFailure.INVALID_CREDENTIALS)) }
        return adopt(response)
    }

    /**
     * Письмо со сбросом на ту же почту, что в поле входа. Токен и локальную
     * книгу не трогает: неудача — строка на форме, не выход из книги.
     */
    suspend fun requestPasswordReset(email: String): PasswordResetResult {
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

    /** Регистрация сразу заводит и вход — отдельного экрана «теперь войдите» нет. */
    suspend fun register(email: String, password: String, displayName: String): FamilyBookState {
        runCatching { api.register(RegisterRequest(email, password, password, displayName)) }
            .getOrElse { return fail(NetworkFailure.classify(it, NetworkFailure.INVALID_CREDENTIALS)) }
        return signIn(email, password)
    }

    suspend fun createFamily(name: String): FamilyBookState {
        val authorization = token ?: return fail(NetworkFailure.SIGNED_OUT)
        val current = account ?: return fail(NetworkFailure.SIGNED_OUT)
        val response = runCatching { api.createFamily(authorization, CreateFamilyRequest(name)) }
            .getOrElse { return fail(NetworkFailure.classify(it)) }
        return remember(current, response)
    }

    /**
     * Код разбирается до запроса, и отказ выглядит одинаково при любом изъяне:
     * ни по ответу сервера, ни по нашей проверке нельзя понять, существует ли
     * книга с таким кодом.
     */
    suspend fun joinFamily(code: String): FamilyBookState {
        val authorization = token ?: return fail(NetworkFailure.SIGNED_OUT)
        val current = account ?: return fail(NetworkFailure.SIGNED_OUT)
        val normalized = InviteCode.normalize(code) ?: return rejected()
        val response = runCatching { api.joinFamily(authorization, JoinFamilyRequest(normalized)) }
            .getOrElse {
                val failure = NetworkFailure.classify(it, NetworkFailure.REJECTED)
                return if (failure == NetworkFailure.REJECTED) rejected() else fail(failure)
            }
        return remember(current, response)
    }

    /** Сменить код приглашения: старый перестаёт работать сразу. */
    suspend fun rotateInviteCode(): FamilyBookState {
        val authorization = token ?: return fail(NetworkFailure.SIGNED_OUT)
        val current = account ?: return fail(NetworkFailure.SIGNED_OUT)
        if (!current.isFamilyOwner) return fail(NetworkFailure.NOT_OWNER)
        val response = runCatching { api.rotateInviteCode(authorization) }
            .getOrElse { return fail(NetworkFailure.classify(it)) }
        return remember(current, response)
    }

    /**
     * Новое название полки. Старые строки формуляра хранят снимок имени на
     * сервере и здесь не переписываются — меняется только живой заголовок.
     */
    suspend fun renameFamily(name: String): FamilyBookState {
        val authorization = token ?: return fail(NetworkFailure.SIGNED_OUT)
        val current = account ?: return fail(NetworkFailure.SIGNED_OUT)
        if (!current.isFamilyOwner) return fail(NetworkFailure.NOT_OWNER)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return fail(NetworkFailure.UNKNOWN)
        val response = runCatching { api.renameFamily(authorization, RenameFamilyRequest(trimmed)) }
            .getOrElse { return fail(NetworkFailure.classify(it, NetworkFailure.NOT_OWNER)) }
        val updated = current.copy(familyName = response.familyName)
        account = updated
        return FamilyBookState.SignedIn(updated)
    }

    /**
     * Уйти с полки, остаться в книге на телефоне. Токен не выбрасывается:
     * это не выход из аккаунта.
     */
    suspend fun leaveFamily(): FamilyBookState {
        val authorization = token ?: return fail(NetworkFailure.SIGNED_OUT)
        val current = account ?: return fail(NetworkFailure.SIGNED_OUT)
        runCatching { api.leaveFamily(authorization) }
            .getOrElse { return fail(NetworkFailure.classify(it)) }
        val updated = current.copy(
            familyId = null,
            familyName = null,
            inviteCode = null,
            familyOwnerId = null,
        )
        account = updated
        return FamilyBookState.SignedIn(updated)
    }

    suspend fun listFamilyUsers(): List<UserRecord> {
        val authorization = token ?: throw IllegalStateException("Missing token for listFamilyUsers")
        return api.listFamilyUsers(authorization).items
    }

    fun signOut(): FamilyBookState {
        tokens.clear()
        return forget()
    }

    fun currentAccount(): FamilyAccount? = account

    /**
     * Забыть открытый код: сервер отдаёт его один раз, и после показа он не
     * должен всплыть снова. Чистить только экран мало — код живёт и в этом
     * локальном аккаунте, а его тащит с собой любой следующий отказ ([fail],
     * [rejected]). Не вычистив его здесь, провалившийся rotate/join вернул бы
     * старый код обратно на страницу.
     */
    fun clearInviteCode() {
        val current = account ?: return
        if (current.inviteCode == null) return
        account = current.copy(inviteCode = null)
    }

    private suspend fun adopt(response: AuthResponse): FamilyBookState {
        tokens.write(response.token)
        token = response.token

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
        account = restored
        return FamilyBookState.SignedIn(restored)
    }

    private suspend fun remember(current: FamilyAccount, response: FamilyResponse): FamilyBookState {
        val ownerId = if (response.inviteCode != null) {
            current.userId
        } else {
            token?.let { authorization ->
                runCatching { api.family(authorization, response.familyId).owner }.getOrNull()
            }
        }
        val updated = current.copy(
            familyId = response.familyId,
            familyName = response.familyName,
            inviteCode = response.inviteCode,
            familyOwnerId = ownerId,
        )
        account = updated
        return FamilyBookState.SignedIn(updated)
    }

    private fun forget(): FamilyBookState {
        token = null
        account = null
        return FamilyBookState.SignedOut
    }

    private fun fail(failure: NetworkFailure): FamilyBookState {
        if (failure == NetworkFailure.SIGNED_OUT) {
            tokens.clear()
            forget()
        }
        return FamilyBookState.Failed(failure, account)
    }

    /**
     * Отказ по коду не раскрывает ничего о семье, но сохраняет локально уже
     * известный аккаунт: ошибка приглашения не должна разлогинивать читателя.
     */
    private fun rejected(): FamilyBookState = FamilyBookState.Failed(NetworkFailure.REJECTED, account)
}
