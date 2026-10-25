package com.polinalinen.madre.integration.v2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No URL, body, credential or upstream exception escapes this boundary. */
class ContractFailure(val kind: Kind) : IOException("Integration v2: ${kind.name}") {
    enum class Kind { DISABLED, NETWORK, UNAUTHORIZED, DEAD_INVITE, FAMILY_FULL, OTHER_FAMILY, CONFLICT, RATE_LIMITED, UNSUPPORTED_PROTOCOL, SERVER, INVALID }
}

/** Same OkHttp/Gson stack as phase A; pinned callers, no cache/logger/cookies/redirects. */
internal class ContractHttp(private val client: OkHttpClient = client()) {
    init {
        require(!client.followRedirects && !client.followSslRedirects && client.cache == null)
        require(client.cookieJar === CookieJar.NO_COOKIES)
    }

    suspend fun request(method: String, url: String, credential: String? = null,
                        body: JsonObject? = null, installation: Boolean = false, noContent: Boolean = false, strict: Boolean = false): JsonObject = safe {
        val encoded = body?.toString()
        require(encoded == null || encoded.toByteArray(Charsets.UTF_8).size <= 65_536)
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (!installation) builder.header("X-Madre-Family-Version", "2")
        if (credential != null) {
            require(credential.isNotBlank() && credential.none { it <= ' ' || it == '\u007f' })
            builder.header("Authorization", if (installation) "Installation $credential" else credential)
        }
        builder.method(method, encoded?.toRequestBody("application/json".toMediaType()))
        execute(builder.build()).use { response ->
            withContext(Dispatchers.IO) {
                val source = requireNotNull(response.body).source()
                source.request(65_537)
                require(source.buffer.size <= 65_536)
                val text = source.readUtf8()
                if (response.code == 204 && noContent) {
                    require(text.isEmpty())
                    return@withContext JsonObject()
                }
                if (response.code != 200 || noContent) {
                    val kind = when (response.code) {
                        401, 403 -> ContractFailure.Kind.UNAUTHORIZED
                        404 -> ContractFailure.Kind.DEAD_INVITE
                        409 -> when (runCatching { JsonParser.parseString(text).asJsonObject.string("error") }.getOrNull()) {
                            "family_full" -> ContractFailure.Kind.FAMILY_FULL
                            "already_in_family" -> ContractFailure.Kind.OTHER_FAMILY
                            else -> ContractFailure.Kind.CONFLICT
                        }
                        426 -> ContractFailure.Kind.UNSUPPORTED_PROTOCOL
                        429 -> ContractFailure.Kind.RATE_LIMITED
                        else -> ContractFailure.Kind.SERVER
                    }
                    throw ContractFailure(kind)
                }
                require(response.body?.contentType()?.subtype == "json")
                if (strict) strictObject(text) else JsonParser.parseString(text).asJsonObject
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(ContractFailure(ContractFailure.Kind.NETWORK)))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
    }

    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}

internal suspend fun <T> safe(block: suspend () -> T): T = try { block() }
catch (e: kotlinx.coroutines.CancellationException) { throw e }
catch (e: ContractFailure) { throw e }
catch (_: IOException) { throw ContractFailure(ContractFailure.Kind.NETWORK) }
catch (_: Exception) { throw ContractFailure(ContractFailure.Kind.INVALID) }

internal fun JsonObject.string(key: String): String {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
    return value.asString.also { require(it.isNotBlank() && it.length <= 4096) }
}
internal fun JsonObject.integer(key: String): Long {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
    require(value.asString.matches(Regex("0|[1-9][0-9]{0,18}")))
    return value.asLong.also { require(it >= 0) }
}
internal fun JsonObject.boolean(key: String): Boolean {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
    return value.asBoolean
}
internal fun json(block: JsonObject.() -> Unit): JsonObject = JsonObject().apply(block)
internal fun uuid(value: String): Boolean = runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
