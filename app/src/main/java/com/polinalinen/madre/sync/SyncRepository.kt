package com.polinalinen.madre.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.FeedingStatRecord
import com.polinalinen.madre.data.remote.PocketBaseDates
import java.util.concurrent.TimeUnit

/**
 * Шаринг статистики на полку (Cycle 5 → 27): каждая выпечка/кормление
 * превращается в OneTimeWorkRequest — без сети WorkManager подождёт её и
 * дошлёт сам (retry-политика — SyncPolicy). Очередь на запись — уникальное
 * имя + KEEP: повторный вызов с тем же ключом не создаёт дубликат.
 *
 * Cycle 27: вместе с фактом уходит снимок подписи; кадр — отдельная работа,
 * потому что его могут вклеить уже после того, как факт встал в очередь.
 * Снять кадр с полки факт не трогает.
 */
class SyncRepository(private val context: Context) {

    private val gson = Gson()

    /**
     * @param recordId id строки bake_records — он же ключ события. Номер
     *   сессии сюда больше не приходит: см. [SyncEventId.forBake].
     */
    fun shareBakeStat(
        recordId: Long,
        recipeId: String,
        recipeName: String,
        portions: Int,
        bakedAtMillis: Long,
        displayName: String? = null,
        familyName: String? = null,
        photoPath: String? = null,
    ) {
        val deviceId = DeviceIdentity.id(context)
        val clientEventId = SyncEventId.forBake(deviceId, recordId)
        val record = BakeStatRecord(
            deviceId = deviceId,
            clientEventId = clientEventId,
            recipeId = recipeId,
            recipeName = recipeName,
            portions = portions,
            bakedAt = PocketBaseDates.toIso(bakedAtMillis),
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
            familyName = familyName?.trim()?.takeIf { it.isNotEmpty() },
        )
        // Chain first bake POST before photo mutation (photo may be attached later or at share time)
        enqueue("sync-bake-record-$recordId", SyncWorker.KIND_BAKE, gson.toJson(record))
        val photo = photoPath?.trim().orEmpty()
        if (photo.isNotEmpty()) {
            shareBakePhoto(recordId, photo)
        }
    }

    fun shareBakePhoto(recordId: Long, photoPath: String) {
        val deviceId = DeviceIdentity.id(context)
        val payload = BakePhotoPayload(
            clientEventId = SyncEventId.forBake(deviceId, recordId),
            photoPath = photoPath,
        )
        // Serialize photo desired-state: use one queue per record + REPLACE so
        // upload A then clear -> final clear; upload A then upload B -> final B
        enqueue("sync-bake-photo-$recordId", SyncWorker.KIND_BAKE_PHOTO, gson.toJson(payload), ExistingWorkPolicy.REPLACE)
    }

    fun clearBakePhoto(recordId: Long) {
        val deviceId = DeviceIdentity.id(context)
        val payload = BakePhotoPayload(
            clientEventId = SyncEventId.forBake(deviceId, recordId),
            photoPath = "",
        )
        enqueue("sync-bake-photo-$recordId", SyncWorker.KIND_BAKE_PHOTO_CLEAR, gson.toJson(payload), ExistingWorkPolicy.REPLACE)
    }

    fun shareFeedingStat(feedingId: Long, flourGrams: Int, waterGrams: Int, fedAtMillis: Long) {
        val deviceId = DeviceIdentity.id(context)
        val record = FeedingStatRecord(
            deviceId = deviceId,
            clientEventId = SyncEventId.forFeeding(deviceId, feedingId),
            flourGrams = flourGrams,
            waterGrams = waterGrams,
            fedAt = PocketBaseDates.toIso(fedAtMillis),
        )
        enqueue("sync-feeding-$feedingId", SyncWorker.KIND_FEEDING, gson.toJson(record))
    }

    private fun enqueue(uniqueName: String, kind: String, payload: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_KIND to kind, SyncWorker.KEY_PAYLOAD to payload))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, policy, request)
    }
}

internal data class BakePhotoPayload(
    @SerializedName("client_event_id") val clientEventId: String,
    @SerializedName("photo_path") val photoPath: String,
)
