package com.polinalinen.madre.sync

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.polinalinen.madre.account.AuthTokenStore
import com.polinalinen.madre.account.KeystoreTokenCipher
import com.polinalinen.madre.account.SecureTokenStore
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.ClearShelfPhotoRequest
import com.polinalinen.madre.data.remote.FeedingStatRecord
import com.polinalinen.madre.data.remote.MadreApi
import com.polinalinen.madre.data.remote.MadreApiFactory
import com.polinalinen.madre.data.remote.PocketBaseFilter
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Фоновая отправка одной записи в PocketBase (Cycle 5). Пейлоад приезжает
 * в inputData готовым JSON'ом (собирает SyncRepository) — воркер только
 * доставляет его и решает по SyncPolicy, повторять ли при неудаче.
 *
 * Cycle 17: доставка идёт под входом. Токена нет — работа не повторяется.
 * Cycle 27: кадр на полке — отдельный вид; нет файла или нет записи — не
 * выдумываем снимок.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val payload = inputData.getString(KEY_PAYLOAD) ?: return Result.failure()

        val token = withContext(Dispatchers.IO) { tokens().read() }?.takeIf { it.isNotBlank() }
        if (token == null) {
            remember(SyncStatus.State.NO_ACCOUNT)
            return Result.failure()
        }

        val api = apiOverride ?: MadreApiFactory.create()
        return try {
            send(api, token, kind, payload)
            remember(SyncStatus.State.DELIVERED)
            Result.success()
        } catch (e: Exception) {
            remember(SyncPolicy.stateFor(e))
            when (SyncPolicy.classify(e, runAttemptCount + 1)) {
                SyncOutcome.RETRY -> Result.retry()
                SyncOutcome.GIVE_UP -> Result.failure()
            }
        }
    }

    private fun tokens(): AuthTokenStore =
        tokenStoreOverride ?: SecureTokenStore(applicationContext, KeystoreTokenCipher())

    private suspend fun remember(state: SyncStatus.State) = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(SyncStatus.PREFS, Context.MODE_PRIVATE)
        SyncStatus.record(prefs, state)
    }

    private suspend fun send(api: MadreApi, token: String, kind: String, payload: String) {
        val gson = Gson()
        when (kind) {
            KIND_BAKE -> api.postBakeStat(token, gson.fromJson(payload, BakeStatRecord::class.java))
            KIND_FEEDING -> api.postFeedingStat(token, gson.fromJson(payload, FeedingStatRecord::class.java))
            KIND_BAKE_PHOTO -> uploadPhoto(api, token, gson.fromJson(payload, BakePhotoPayload::class.java))
            KIND_BAKE_PHOTO_CLEAR -> clearPhoto(api, token, gson.fromJson(payload, BakePhotoPayload::class.java))
            else -> error("Неизвестный вид синхронизации: $kind")
        }
    }

    private suspend fun uploadPhoto(api: MadreApi, token: String, payload: BakePhotoPayload) {
        val recordId = findShelfRecordId(api, token, payload.clientEventId) ?: error("запись ещё не на полке")
        val file = PhotoStore.resolve(applicationContext, payload.photoPath)
        if (!file.isFile) {
            // Кадра на телефоне нет — факта это не отменяет, и выдумывать
            // файл книга не будет.
            return
        }
        val media = "image/jpeg".toMediaType()
        val part = MultipartBody.Part.createFormData("photo", file.name, file.asRequestBody(media))
        val idBody = recordId.toRequestBody("text/plain".toMediaType())
        api.uploadBakePhoto(token, idBody, part)
    }

    private suspend fun clearPhoto(api: MadreApi, token: String, payload: BakePhotoPayload) {
        val recordId = findShelfRecordId(api, token, payload.clientEventId) ?: return
        api.clearBakePhoto(token, ClearShelfPhotoRequest(recordId))
    }

    private suspend fun findShelfRecordId(api: MadreApi, token: String, clientEventId: String): String? {
        val page = api.listBakeStats(token, filter = PocketBaseFilter.ofClientEvent(clientEventId), perPage = 1)
        return page.items.firstOrNull()?.id
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_PAYLOAD = "payload"
        const val KIND_BAKE = "bake"
        const val KIND_FEEDING = "feeding"
        const val KIND_BAKE_PHOTO = "bake-photo"
        const val KIND_BAKE_PHOTO_CLEAR = "bake-photo-clear"

        @VisibleForTesting
        var apiOverride: MadreApi? = null

        @VisibleForTesting
        var tokenStoreOverride: AuthTokenStore? = null
    }
}
