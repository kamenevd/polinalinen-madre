package com.polinalinen.madre.account.yandex

import java.io.Closeable

/**
 * RAM-only ownership of an SDK login result, never a state/Bundle/worker payload.
 * close() wipes our mutable copy and drops its reference. JVM/SDK immutable String
 * copies cannot be physically wiped; the transport must release them after use.
 */
class YandexLoginToken private constructor(private var characters: CharArray?) : Closeable {
    internal val isClosed: Boolean
        @Synchronized get() = characters == null

    /** Only the foreground server-verification transport may request a temporary copy. */
    @Synchronized
    internal fun forVerificationRequest(): String =
        checkNotNull(characters) { "Login token already released" }.concatToString()

    @Synchronized
    override fun close() {
        characters?.fill('\u0000')
        characters = null
    }

    override fun toString(): String = "YandexLoginToken([REDACTED])"

    companion object {
        fun takeSdkResult(value: String): YandexLoginToken {
            require(value.isNotBlank()) { "Empty login result" }
            return YandexLoginToken(value.toCharArray())
        }
    }
}
