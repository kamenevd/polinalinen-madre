package com.polinalinen.madre.account

import com.polinalinen.madre.data.remote.AuthResponse
import com.polinalinen.madre.data.remote.CreateFamilyRequest
import com.polinalinen.madre.data.remote.FamilyBookApi
import com.polinalinen.madre.data.remote.FamilyResponse
import com.polinalinen.madre.data.remote.JoinFamilyRequest
import com.polinalinen.madre.data.remote.PasswordAuthRequest
import com.polinalinen.madre.data.remote.RegisterRequest

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

    suspend fun signIn(email: String, password: String): FamilyBookState {
        val response = runCatching { api.authWithPassword(PasswordAuthRequest(email, password)) }
            .getOrElse { return fail(NetworkFailure.classify(it, NetworkFailure.INVALID_CREDENTIALS)) }
        return adopt(response)
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
        val response = runCatching { api.rotateInviteCode(authorization) }
            .getOrElse { return fail(NetworkFailure.classify(it)) }
        return remember(current, response)
    }

    fun signOut(): FamilyBookState {
        tokens.clear()
        return forget()
    }

    private suspend fun adopt(response: AuthResponse): FamilyBookState {
        tokens.write(response.token)
        token = response.token

        val familyId = response.record.family.takeIf { it.isNotBlank() }
        // Название семьи — отдельный запрос, и его провал не повод не пускать
        // читателя внутрь: книга просто побудет пока безымянной.
        val familyName = familyId?.let { id ->
            runCatching { api.family(response.token, id).name }.getOrNull()
        }

        val restored = FamilyAccount(
            userId = response.record.id,
            email = response.record.email,
            displayName = response.record.name,
            familyId = familyId,
            familyName = familyName,
        )
        account = restored
        return FamilyBookState.SignedIn(restored)
    }

    private fun remember(current: FamilyAccount, response: FamilyResponse): FamilyBookState {
        val updated = current.copy(
            familyId = response.familyId,
            familyName = response.familyName,
            inviteCode = response.inviteCode,
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

    /** Отказ по коду приглашения не несёт вообще ничего — в этом весь смысл. */
    private fun rejected(): FamilyBookState = FamilyBookState.Failed(NetworkFailure.REJECTED)
}
