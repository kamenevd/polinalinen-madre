package com.polinalinen.madre.account

/**
 * Сброс пароля без отдельного экрана: та же почта, что для входа,
 * одно действие под полем пароля, книга на телефоне не трогается.
 */
object PasswordReset {

    const val ACTION_LABEL = "Забыли пароль? Пришлём письмо с kdnfx@kdnfx.ru"
    const val SENT_LINE = "письмо ушло, загляните в спам"
    const val EMPTY_EMAIL = "Напишите почту выше — письмо уйдёт на неё."
    const val PATH = "api/collections/users/request-password-reset"

    fun messageFor(failure: NetworkFailure): String = when (failure) {
        NetworkFailure.OFFLINE -> "Сети нет — письмо не ушло. Книга на телефоне на месте."
        NetworkFailure.INVALID_CREDENTIALS -> "Почта не принята — проверьте написание."
        NetworkFailure.SERVER -> "Сервер сейчас не отвечает как надо."
        else -> "Письмо не ушло. Книга на телефоне на месте."
    }
}

sealed interface PasswordResetResult {
    data object Sent : PasswordResetResult
    data class Failed(val message: String) : PasswordResetResult
}

sealed interface PasswordResetNotice {
    data object Idle : PasswordResetNotice
    data object Sending : PasswordResetNotice
    data object Sent : PasswordResetNotice
    data class Failed(val message: String) : PasswordResetNotice
}
