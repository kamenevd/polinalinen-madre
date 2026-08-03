package com.polinalinen.madre.account

import java.io.IOException
import retrofit2.HttpException

/**
 * Что видно на странице семейной книги (DESIGN-V4.md Cycle 11, фича 28).
 *
 * Главное здесь — [localBookAvailable]: своя книга лежит в Room и читается
 * всегда, в любом состоянии сети и без всякого аккаунта. Сеть — это только
 * общая книга, и её отказы не должны выглядеть как поломка приложения,
 * поэтому у каждого состоят своя внятная причина, а не пустой экран.
 */
sealed interface FamilyBookState {

    /** Аккаунт, о котором уже известно; сохраняется и в отказе. */
    val account: FamilyAccount? get() = null

    /** Сетевые действия предлагаются только вошедшему читателю. */
    val canUseNetwork: Boolean get() = false

    /** Своя книга работает всегда — на этом держится весь offline-режим. */
    val localBookAvailable: Boolean get() = true

    /** Идёт запрос — токен проверяется или книга заводится. */
    data object Loading : FamilyBookState

    /** Аккаунта нет: приложение живёт локально, общей книги просто не видно. */
    data object SignedOut : FamilyBookState

    data class SignedIn(override val account: FamilyAccount) : FamilyBookState {
        override val canUseNetwork: Boolean get() = true
    }

    /**
     * Отказ. Аккаунт сохраняется, если он был известен: потеряв сеть, страница
     * продолжает показывать название семьи, а не забывает её.
     */
    data class Failed(
        val failure: NetworkFailure,
        override val account: FamilyAccount? = null,
    ) : FamilyBookState
}

/**
 * Читатель общей книги. `inviteCode` живёт только в памяти и только сразу
 * после создания книги или смены кода: сервер отдаёт открытый код один раз.
 */
data class FamilyAccount(
    val userId: String,
    val email: String,
    val displayName: String,
    val familyId: String? = null,
    val familyName: String? = null,
    val inviteCode: String? = null,
) {
    val hasFamily: Boolean get() = !familyId.isNullOrBlank()
}

/**
 * Причина отказа сети словами читателя. Разделение важно для поведения, а не
 * только для текста: [OFFLINE] не трогает сохранённый токен, [SIGNED_OUT] его
 * выбрасывает, а [REJECTED] намеренно ничего не говорит о том, существует ли
 * книга с таким кодом.
 */
enum class NetworkFailure(val message: String) {

    OFFLINE("Сети нет — своя книга открыта, общая подождёт."),
    SIGNED_OUT("Нужно войти заново."),
    INVALID_CREDENTIALS("Почта или пароль не подошли."),
    REJECTED("Приглашение не подошло — проверьте код и попробуйте ещё раз."),
    SERVER("Сервер сейчас не отвечает как надо."),
    UNKNOWN("Что-то пошло не так.");

    companion object {

        /**
         * @param badRequest чем считать 400/404 — у входа и у кода приглашения
         *   один и тот же код ответа значит разное.
         */
        fun classify(error: Throwable, badRequest: NetworkFailure = UNKNOWN): NetworkFailure =
            when {
                error is HttpException -> when (error.code()) {
                    401, 403 -> SIGNED_OUT
                    400, 404, 422 -> badRequest
                    in 500..599 -> SERVER
                    else -> UNKNOWN
                }
                // Таймаут и оборванное соединение для читателя — то же самое,
                // что и отсутствие сети: ждать и не терять токен.
                error is IOException -> OFFLINE
                else -> UNKNOWN
            }
    }
}
