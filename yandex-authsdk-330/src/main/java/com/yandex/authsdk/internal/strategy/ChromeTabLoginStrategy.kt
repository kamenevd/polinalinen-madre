package com.yandex.authsdk.internal.strategy

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsService
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import com.yandex.authsdk.YandexAuthException
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthToken
import com.yandex.authsdk.internal.ChromeTabLoginActivity
import com.yandex.authsdk.internal.Constants
import com.yandex.authsdk.internal.YandexAuthSdkParams
import com.yandex.authsdk.internal.queryIntentActivities
import com.yandex.authsdk.internal.resolveService

internal class ChromeTabLoginStrategy(
    private val packageName: String
) : LoginStrategy() {

    override val type: LoginType = LoginType.CHROME_TAB

    override val contract = object : LoginContract(ResultExtractor()) {

        override fun createIntent(context: Context, input: YandexAuthSdkParams): Intent {
            return Intent(context, ChromeTabLoginActivity::class.java).apply {
                putExtra(ChromeTabLoginActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtras(this, input.options, input.loginOptions, input.state)
            }
        }
    }

    internal class ResultExtractor : LoginStrategy.ResultExtractor {

        override fun tryExtractToken(data: Intent): YandexAuthToken? {
            return IntentCompat.getParcelableExtra(data, Constants.EXTRA_TOKEN, YandexAuthToken::class.java)
        }

        override fun tryExtractError(data: Intent): YandexAuthException? {
            return IntentCompat.getSerializableExtra(data,Constants.EXTRA_ERROR, YandexAuthException::class.java)
        }
    }

    companion object {

        private const val TEST_WEB_URI = "https://ya.ru"

        val ALLOWED_BROWSERS = setOf(
            "com.yandex.browser",
            "com.yandex.browser.beta",
            "com.yandex.browser.alpha",
            "com.android.chrome",
            "com.android.chrome.beta",
            "com.android.chrome.dev",
            "com.huawei.browser",
            "com.sec.android.app.sbrowser",
        )

        fun getIfPossible(context: Context, options: YandexAuthOptions): LoginStrategy? {
            val packageManager = context.packageManager
            val sampleUri = Uri.parse(TEST_WEB_URI)
            val intent = Intent(Intent.ACTION_VIEW, sampleUri)
            val resolvedActivityList = packageManager.queryIntentActivities(intent)
            val packagesSupportingCustomTabs = resolvedActivityList
                .filter {
                    val browserPackage = it.activityInfo.packageName
                    if (browserPackage !in ALLOWED_BROWSERS) {
                        return@filter false
                    }

                    val serviceIntent = Intent().apply {
                        action = CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION
                        `package` = browserPackage
                    }
                    packageManager.resolveService(serviceIntent) != null
                }
            val defaultPackageName = packagesSupportingCustomTabs.getOrNull(0)?.activityInfo?.packageName
            return defaultPackageName
                ?.let { ChromeTabLoginStrategy(it) }
                ?.takeIf { isAppLinkWorking(context, options) }
        }

        private fun isAppLinkWorking(context: Context, options: YandexAuthOptions): Boolean {
            val host = "yx${options.clientId}.${options.oauthHost}"
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    context
                        .getSystemService<DomainVerificationManager>()
                        ?.getDomainVerificationUserState(context.packageName)
                        ?.let { verificationState ->
                            verificationState.isLinkHandlingAllowed
                                && verificationState.hostToStateMap[host] == DOMAIN_STATE_VERIFIED
                        }
                        ?: false
                } catch (e: Exception) {
                    false
                }
            } else true
        }
    }
}
