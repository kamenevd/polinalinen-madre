package com.yandex.authsdk.internal.strategy

import android.content.Context
import com.yandex.authsdk.internal.PackageManagerHelper
import com.yandex.authsdk.internal.YandexAuthSdkParams

internal class LoginStrategyResolver(
    private val context: Context,
    private val packageManagerHelper: PackageManagerHelper
) {

    private val fullLoginTypesOrder: List<LoginType> = listOf(
        LoginType.NATIVE, LoginType.CHROME_TAB, LoginType.WEBVIEW,
    )

    fun getLoginStrategy(params: YandexAuthSdkParams): LoginStrategy {
        val preferredLoginType = params.loginOptions.loginType
        val startIndex = fullLoginTypesOrder.indexOf(preferredLoginType).takeIf { it != -1 } ?: 0
        val loginTypeOrder = fullLoginTypesOrder.subList(startIndex, fullLoginTypesOrder.size)
        return loginTypeOrder.firstNotNullOf {
            return@firstNotNullOf when (it) {
                LoginType.NATIVE -> NativeLoginStrategy.getIfPossible(packageManagerHelper)
                LoginType.CHROME_TAB -> if (params.options.skipChromeTab) {
                    null
                } else {
                    ChromeTabLoginStrategy.getIfPossible(context, params.options)
                }
                LoginType.WEBVIEW -> WebViewLoginStrategy.get()
            }
        }
    }
}
