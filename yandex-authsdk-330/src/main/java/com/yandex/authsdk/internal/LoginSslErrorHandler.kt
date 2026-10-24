package com.yandex.authsdk.internal

import android.net.http.SslError
import android.webkit.SslErrorHandler
import com.yandex.authsdk.internal.domesticroots.webview.WebViewSslErrorHandler

/** Adapts asynchronous certificate verification to the lifetime of the login WebView. */
internal class LoginSslErrorHandler(
    private val verifier: WebViewSslErrorHandler,
    private val onSecurityError: () -> Unit,
) {
    private val pendingHandlers = mutableSetOf<SslErrorHandler>()
    private var isDestroyed = false

    fun handle(handler: SslErrorHandler, error: SslError) {
        if (isDestroyed) {
            handler.cancel()
            return
        }

        // SSL_UNTRUSTED can be reported alongside expiration or hostname errors.
        // The domestic root may only compensate for an unknown trust anchor.
        if (!error.hasError(SslError.SSL_UNTRUSTED) ||
            (0 until SslError.SSL_MAX_ERROR).any {
                it != SslError.SSL_UNTRUSTED && error.hasError(it)
            }
        ) {
            handler.cancel()
            onSecurityError()
            return
        }

        pendingHandlers.add(handler)
        val callback = object : WebViewSslErrorHandler.Callback {
            override fun onProceeded() {
                if (pendingHandlers.remove(handler)) {
                    handler.proceed()
                }
            }

            override fun onCanceled() {
                if (pendingHandlers.remove(handler)) {
                    handler.cancel()
                    onSecurityError()
                }
            }
        }

        try {
            if (!verifier.handleSslError(error, callback)) {
                callback.onCanceled()
            }
        } catch (e: RuntimeException) {
            callback.onCanceled()
        }
    }

    fun destroy() {
        isDestroyed = true
        pendingHandlers.forEach { it.cancel() }
        pendingHandlers.clear()
    }
}
