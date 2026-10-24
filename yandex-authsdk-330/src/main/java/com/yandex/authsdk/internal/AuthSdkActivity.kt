package com.yandex.authsdk.internal

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.yandex.authsdk.YandexAuthException
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthSdkContract.Companion.toYandexAuthSdkParams
import com.yandex.authsdk.YandexAuthToken
import com.yandex.authsdk.internal.strategy.LoginStrategyResolver
import com.yandex.authsdk.internal.strategy.LoginType

internal class AuthSdkActivity : AppCompatActivity() {

    private lateinit var loginType: LoginType

    private lateinit var options: YandexAuthOptions

    private lateinit var loginStrategyResolver: LoginStrategyResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputParams = intent.toYandexAuthSdkParams()
        options = inputParams.options
        loginStrategyResolver = LoginStrategyResolver(
            applicationContext,
            PackageManagerHelper(packageName, packageManager, inputParams.options)
        )

        val strategy = loginStrategyResolver.getLoginStrategy(inputParams)
        // Madre security correction to pinned3.3.0: keep the REAL strategy callback
        // active at STOP, before inherited registry save can persist a raw bearer.
        // Initialize before register: a restored pending result can arrive immediately.
        loginType = if (savedInstanceState == null) strategy.type else
            LoginType.values()[savedInstanceState.getInt(STATE_LOGIN_TYPE)]
        val launcher = activityResultRegistry.register(RESULT_KEY, strategy.contract, ::onGetResult)

        if (savedInstanceState == null) {
            try {
                launcher.launch(inputParams)
            } catch (e: Exception) {
                finishWithError(e)
            }
        }
    }

    private fun onGetResult(result: Result<YandexAuthToken?>) {
        result
            .onSuccess { yandexAuthToken ->
                Logger.d(options, TAG, "Token received")
                if (yandexAuthToken != null) {
                    val intent = Intent().apply {
                        putExtra(Constants.EXTRA_TOKEN, yandexAuthToken)
                    }
                    setResult(RESULT_OK, intent)
                }
            }
            .onFailure {
                Logger.d(options, TAG, "Error received")
                val intent = Intent().apply {
                    putExtra(Constants.EXTRA_ERROR, it)
                }
                setResult(RESULT_OK, intent)
            }
        finish()
    }

    public override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putInt(STATE_LOGIN_TYPE, loginType.ordinal)
    }

    private fun finishWithError(e: Exception) {
        Logger.e(options, TAG, "Unknown error:", e)
        val intent = Intent().apply {
            putExtra(Constants.EXTRA_ERROR, YandexAuthException(YandexAuthException.UNKNOWN_ERROR))
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    companion object  {

        private const val RESULT_KEY = "authsdk:foreground:result"
        private const val STATE_LOGIN_TYPE = "com.yandex.authsdk.STATE_LOGIN_TYPE"

        private val TAG = AuthSdkActivity::class.java.simpleName
    }
}
