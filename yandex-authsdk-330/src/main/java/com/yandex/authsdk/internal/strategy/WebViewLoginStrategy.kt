package com.yandex.authsdk.internal.strategy

import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.yandex.authsdk.YandexAuthException
import com.yandex.authsdk.YandexAuthToken
import com.yandex.authsdk.internal.Constants
import com.yandex.authsdk.internal.WebViewLoginActivity
import com.yandex.authsdk.internal.YandexAuthSdkParams

internal class WebViewLoginStrategy : LoginStrategy() {

    override val type = LoginType.WEBVIEW

    override val contract = object : LoginContract(ResultExtractor()) {

        override fun createIntent(context: Context, input: YandexAuthSdkParams): Intent {
            return Intent(context, WebViewLoginActivity::class.java).apply {
                putExtras(this, input.options, input.loginOptions, input.state)
            }
        }
    }

    internal class ResultExtractor : LoginStrategy.ResultExtractor {

        override fun tryExtractToken(data: Intent): YandexAuthToken? {
            return IntentCompat.getParcelableExtra(data,Constants.EXTRA_TOKEN, YandexAuthToken::class.java)
        }

        override fun tryExtractError(data: Intent): YandexAuthException? {
            return IntentCompat.getSerializableExtra(data,Constants.EXTRA_ERROR, YandexAuthException::class.java)
        }
    }

    companion object {
        fun get(): LoginStrategy {
            return WebViewLoginStrategy()
        }
    }
}
