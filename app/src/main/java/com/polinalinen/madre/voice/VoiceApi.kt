package com.polinalinen.madre.voice

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

interface VoiceApi {
    suspend fun post(path: String, credential: String, payload: Any = emptyMap<String, String>()): JsonObject
}

/** No interceptor/logger; redirects disabled so installation credentials cannot leave the pinned origin. */
class HttpsVoiceApi : VoiceApi {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override suspend fun post(path: String, credential: String, payload: Any): JsonObject = withContext(Dispatchers.IO) {
        require(path in PATHS)
        val body = com.google.gson.GsonBuilder().serializeNulls().create().toJson(payload)
        require(body.toByteArray().size <= 65536)
        val request = Request.Builder().url("https://kdnfx.space/alice/madre/voice/phone/$path")
            .header("Authorization", "Installation $credential")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Voice HTTP ${response.code}" }
            val source = requireNotNull(response.body).source()
            source.request(65537)
            check(source.buffer.size <= 65536) { "Voice response too large" }
            Gson().fromJson(source.readUtf8(), JsonObject::class.java)
        }
    }
    private companion object {
        val PATHS = setOf("enroll", "select", "disconnect", "pair-preview", "pair-approve", "poll", "ack")
    }
}
