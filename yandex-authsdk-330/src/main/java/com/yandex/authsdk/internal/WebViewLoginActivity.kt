package com.yandex.authsdk.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.core.content.IntentCompat
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthException

internal class WebViewLoginActivity : Activity() {

    private lateinit var loginHandler: ExternalLoginHandler

    private lateinit var options: YandexAuthOptions

    private lateinit var webView: WebView
    private lateinit var sslErrorHandler: LoginSslErrorHandler

    @SuppressLint("SetJavaScriptEnabled")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val options = IntentCompat
            .getParcelableExtra(intent, Constants.EXTRA_OPTIONS, YandexAuthOptions::class.java)
        if (options == null){
            finish()
            return
        }
        this.options = options

        sslErrorHandler = LoginSslErrorHandler(
            WebViewSslErrorHandlerFactory.create(applicationContext),
            ::finishWithSecurityError,
        )

        val state = intent.getStringExtra(Constants.EXTRA_STATE)!!

        // no need to save state, url will be loaded once again after rotation
        loginHandler = ExternalLoginHandler(
            PreferencesHelper(this),
            state,
            UrlCreator(),
            clientId = options.clientId,
        )

        if (savedInstanceState == null) {
            clearCookies()
        }

        webView = WebView(this).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            loadUrl(loginHandler.getUrl(intent, ORIGIN_WEB_VIEW))
        }
        setContentView(webView)
    }

    override fun onDestroy() {
        if (::sslErrorHandler.isInitialized) {
            sslErrorHandler.destroy()
        }
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun finishWithSecurityError() {
        sslErrorHandler.destroy()
        webView.stopLoading()
        setResult(RESULT_OK, Intent().apply {
            putExtra(Constants.EXTRA_ERROR, YandexAuthException(YandexAuthException.SECURITY_ERROR))
        })
        finish()
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun parseTokenFromUrl(url: String) {
        sslErrorHandler.destroy()
        val result = loginHandler.parseResult(Uri.parse(url))
        setResult(RESULT_OK, result)
        finish()
    }

    private inner class WebViewClient : android.webkit.WebViewClient() {

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            sslErrorHandler.handle(handler, error)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (resolveFinalUrl(url)) {
                view.stopLoading()
            } else {
                super.onPageStarted(view, url, favicon)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return resolveFinalUrl(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) {
                return false
            }

            return resolveFinalUrl(request.url.toString())
        }

        private fun resolveFinalUrl(url: String): Boolean {
            return if (loginHandler.isFinalUrl(options, url)) {
                parseTokenFromUrl(url)
                true
            } else {
                false
            }
        }
    }
}
