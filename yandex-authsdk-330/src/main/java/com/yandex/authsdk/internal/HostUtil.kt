package com.yandex.authsdk.internal

import java.util.Locale

/**
 * @param baseHost - host ended with ".ru"
 */
internal fun getLocalizedHost(baseHost: String, locale: Locale): String {
    val tld = getTldByLocale(locale)
    return baseHost.replace("ru$".toRegex(), tld)
}

private fun getTldByLocale(locale: Locale): String {
    return when (locale.language) {
        "be" -> "by"
        "tr" -> "com.tr"
        "kk" -> "kz"
        "et", "hy", "ka", "ru", "uk" -> "ru"
        else -> "com"
    }
}
