package com.yandex.authsdk.internal

import com.yandex.authsdk.YandexAuthException
import okhttp3.Request
import java.io.IOException
import java.util.Locale

internal class JwtRequest(
    private val token: String,
    private val clientId: String,
) {

    private val client = Components.okHttpClient

    @Throws(IOException::class, YandexAuthException::class)
    fun get(): String {
        val request = Request.Builder()
            .url(JWT_REQUEST_URL_FORMAT)
            .header(HEADER_USER_AGENT_NAME, getUserAgentValue())
            .header(HEADER_NAME_AUTHORIZATION, "$HEADER_VALUE_AUTHORIZATION_PREFIX $token")
            .build()

        return client.newCall(request).execute().use { response ->
            if (response.code == RESPONSE_CODE_UNAUTHORIZED) {
                throw YandexAuthException(YandexAuthException.JWT_AUTHORIZATION_ERROR)
            }

            response.body!!.string()
        }
    }

    private fun getUserAgentValue(): String {
        return String.format(Locale.US, "com.yandex.authsdk/%s", clientId)
    }

    companion object {

        const val JWT_REQUEST_URL_FORMAT = "https://login.yandex.ru/info?format=jwt"

        const val HEADER_NAME_AUTHORIZATION = "Authorization"

        const val HEADER_VALUE_AUTHORIZATION_PREFIX = "OAuth"

        const val HEADER_USER_AGENT_NAME: String = "User-Agent"

        const val RESPONSE_CODE_UNAUTHORIZED = 401
    }
}
