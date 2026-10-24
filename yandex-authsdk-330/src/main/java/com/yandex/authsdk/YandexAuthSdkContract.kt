package com.yandex.authsdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import com.yandex.authsdk.internal.AuthSdkActivity
import com.yandex.authsdk.internal.Constants
import com.yandex.authsdk.internal.YandexAuthSdkParams
import java.util.UUID

class YandexAuthSdkContract(private val options: YandexAuthOptions) :
    ActivityResultContract<YandexAuthLoginOptions, YandexAuthResult>() {

    @Volatile
    var latestState: String? = null
        private set

    override fun createIntent(context: Context, input: YandexAuthLoginOptions): Intent {
        val overrideOptions = if (input.clientId.isNullOrBlank()) options else
            YandexAuthOptions(
                loggingEnabled = options.isLoggingEnabled,
                clientId = input.clientId,
                oauthHost = options.oauthHost,
                skipChromeTab = options.skipChromeTab,
            )

        val state = UUID.randomUUID().toString()
        latestState = state

        val intent = Intent(context, AuthSdkActivity::class.java)
        intent.putExtra(Constants.EXTRA_OPTIONS, overrideOptions)
        intent.putExtra(Constants.EXTRA_LOGIN_OPTIONS, input)
        intent.putExtra(Constants.EXTRA_STATE, state)
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): YandexAuthResult {
        if (intent == null || resultCode != Activity.RESULT_OK) {
            return YandexAuthResult.Cancelled
        }

        val exception =
            IntentCompat.getSerializableExtra(intent, Constants.EXTRA_ERROR, YandexAuthException::class.java)
        if (exception != null) {
            return YandexAuthResult.Failure(exception)
        }

        val yandexAuthToken =
            IntentCompat.getParcelableExtra(
                intent,
                Constants.EXTRA_TOKEN,
                YandexAuthToken::class.java
            )
        return yandexAuthToken?.let {
            YandexAuthResult.Success(it)
        } ?: YandexAuthResult.Cancelled
    }

    internal companion object {

        fun Intent.toYandexAuthSdkParams(): YandexAuthSdkParams = YandexAuthSdkParams(
            IntentCompat.getParcelableExtra(this, Constants.EXTRA_OPTIONS, YandexAuthOptions::class.java)!!,
            IntentCompat.getParcelableExtra(this, Constants.EXTRA_LOGIN_OPTIONS, YandexAuthLoginOptions::class.java)!!,
            getStringExtra(Constants.EXTRA_STATE)!!,
        )
    }
}
