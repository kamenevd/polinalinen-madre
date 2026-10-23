package com.polinalinen.madre.account.yandex

/** Metadata returned ONLY after the server verifies the Yandex token/client_id. */
data class VerifiedYandexProfile(
    val uidHash: String,
    val login: String,
    val displayName: String,
    val avatarId: String?,
    val avatarEmpty: Boolean,
    val boundAtMillis: Long,
) {
    init {
        require(uidHash.isNotBlank() && uidHash.length <= 256) { "Invalid account metadata" }
        require(login.isNotBlank() && login.length <= 256) { "Invalid account metadata" }
        require(displayName.length <= 256 && boundAtMillis > 0) { "Invalid account metadata" }
        require(avatarId.isNullOrEmpty() || avatarId.matches(Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*"))) {
            "Invalid avatar metadata"
        }
    }

    val avatarUrl: String?
        get() = avatarId?.takeUnless { avatarEmpty || it.isEmpty() }
            ?.let { "https://avatars.yandex.net/get-yapic/$it/islands-retina-middle" }
    val avatarCacheKey: String get() = "yandex-avatar:$uidHash:${avatarId.orEmpty()}"

    /** An optional suggestion, NEVER a write into the user's ex libris. */
    fun bookNameSuggestion(currentName: String): String? =
        displayName.takeIf { currentName.isBlank() && it.isNotBlank() }

    override fun toString(): String = "VerifiedYandexProfile([REDACTED])"
}

sealed interface YandexAccountState {
    data object SignedOut : YandexAccountState
    data class SigningIn(val attempt: Long, val browser: Boolean) : YandexAccountState
    data class Binding(val attempt: Long) : YandexAccountState
    data class SignedIn(val profile: VerifiedYandexProfile) : YandexAccountState
    data class Error(val kind: YandexAccountError) : YandexAccountState
}

enum class YandexAccountError { NETWORK, YANDEX, SERVER, FOREIGN_CLIENT, STORAGE }

/** Contains no login token, authorization code, SDK result or installation credential. */
data class YandexAccountSnapshot(
    val profile: VerifiedYandexProfile? = null,
    val offerSeen: Boolean = false,
    val foregroundRetryRequired: Boolean = false,
    val unbindPending: Boolean = false,
)
