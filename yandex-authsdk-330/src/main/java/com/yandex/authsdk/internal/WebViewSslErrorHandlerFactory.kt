package com.yandex.authsdk.internal

import android.content.Context
import android.net.http.SslError
import com.yandex.authsdk.internal.domesticroots.webview.WebViewSslErrorHandler

/** Loads domestic-root verification when the optional module is present. */
internal object WebViewSslErrorHandlerFactory {

    private const val IMPLEMENTATION_CLASS =
        "com.yandex.authsdk.internal.domesticroots.webview.WebViewSslErrorHandlerImpl"

    fun create(context: Context): WebViewSslErrorHandler {
        return try {
            Class.forName(IMPLEMENTATION_CLASS)
                .getConstructor(Context::class.java)
                .newInstance(context.applicationContext) as WebViewSslErrorHandler
        } catch (_: ReflectiveOperationException) {
            UnsupportedWebViewSslErrorHandler
        } catch (_: LinkageError) {
            UnsupportedWebViewSslErrorHandler
        } catch (_: ClassCastException) {
            UnsupportedWebViewSslErrorHandler
        }
    }

    private object UnsupportedWebViewSslErrorHandler : WebViewSslErrorHandler {
        override fun handleSslError(
            error: SslError,
            callback: WebViewSslErrorHandler.Callback,
        ): Boolean = false
    }
}
