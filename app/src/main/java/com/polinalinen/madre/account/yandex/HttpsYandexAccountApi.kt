package com.polinalinen.madre.account.yandex

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Pinned origin, no logger/cache/cookies/redirects. Used only after explicit foreground consent. */
class HttpsYandexAccountApi internal constructor(
    private val installationCredential: suspend () -> String,
    client: OkHttpClient,
    private val wallClock: () -> Long,
) : YandexAccountApi {
    constructor(installationCredential: suspend () -> String) : this(
        installationCredential, OkHttpClient(), System::currentTimeMillis)

    private val http = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).cache(null).build()

    override suspend fun bind(token: YandexLoginToken): VerifiedYandexProfile = safeRequest {
        val credential = installationCredential()
        require(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
        val payload = JsonObject().apply { addProperty("yandex_token", token.forVerificationRequest()) }
        val request = request("yandex-bind", credential, payload.toString())
        execute(request).use { r ->
            withContext(Dispatchers.IO) {
                val body = readBounded(r)
                if (r.code != 200) {
                    val foreign = r.code == 403 && runCatching {
                        Gson().fromJson(body, JsonObject::class.java)?.get("error")?.asString == "foreign_client"
                    }.getOrDefault(false)
                    throw YandexAccountFailure(if (foreign) YandexAccountError.FOREIGN_CLIENT else YandexAccountError.SERVER)
                }
                val json = requireNotNull(Gson().fromJson(body, JsonObject::class.java))
                fun text(name: String): String? = json.get(name)?.takeUnless { it.isJsonNull }?.asString
                val avatar = text("avatar_id")
                VerifiedYandexProfile(
                    uidHash = requireNotNull(text("uid_hash")),
                    login = requireNotNull(text("login")),
                    displayName = text("display_name").orEmpty(),
                    avatarId = avatar,
                    avatarEmpty = json.get("avatar_empty")?.takeUnless { it.isJsonNull }?.asBoolean ?: avatar.isNullOrEmpty(),
                    // Receipt time on this phone, not an invented server timestamp.
                    boundAtMillis = wallClock(),
                )
            }
        }
    }

    override suspend fun unbind(): Unit = safeRequest {
        val credential = installationCredential()
        require(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
        execute(request("yandex-unbind", credential, "{}")).use { response ->
            // 202/redirect/HTML/200 are not the contract's explicit revocation acknowledgement.
            if (response.code != 204) throw YandexAccountFailure(YandexAccountError.SERVER)
        }
    }

    private fun request(path: String, credential: String, json: String): Request {
        require(path == "yandex-bind" || path == "yandex-unbind")
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
        return Request.Builder().url("https://kdnfx.space/alice/madre/voice/phone/$path")
            .header("Authorization", "Installation $credential")
            .post(json.toRequestBody("application/json".toMediaType())).build()
    }

    private fun readBounded(response: Response): String {
        val source = requireNotNull(response.body).source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw YandexAccountFailure(YandexAccountError.SERVER)
        return source.readUtf8()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(YandexAccountFailure(YandexAccountError.NETWORK)))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
    }

    private suspend fun <T> safeRequest(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (known: YandexAccountFailure) { throw known }
    catch (_: IOException) { throw YandexAccountFailure(YandexAccountError.NETWORK) }
    catch (_: Exception) { throw YandexAccountFailure(YandexAccountError.SERVER) }

    private companion object { const val MAX_RESPONSE_BYTES = 65_536 }
}
