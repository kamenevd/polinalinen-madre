package com.polinalinen.madre.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
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
class SyncRepository(private val context: Context) : ShelfSync {

    private val gson = Gson()

    /**
     * @param recordId id строки bake_records — он же ключ события. Номер
     *   сессии сюда больше не приходит: см. [SyncEventId.forBake].
     */
    override fun shareBakeStat(
        recordId: Long,
        recipeId: String,
        recipeName: String,
        portions: Int,
        bakedAtMillis: Long,
        displayName: String?,
        familyName: String?,
        photoPath: String?,
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
        val photo = photoPath?.trim().orEmpty()
        val photoPayload = if (photo.isNotEmpty()) {
            gson.toJson(BakePhotoPayload(clientEventId = clientEventId, photoPath = photo))
        } else {
            null
        }
        val plan = ShelfSharePlan.bake(
            recordId = recordId,
            bakePayload = gson.toJson(record),
            photoPayload = photoPayload,
        )
        val manager = WorkManager.getInstance(context)
        val first = plan.steps.firstOrNull() ?: return
        if (plan.steps.size == 1) {
            manager.enqueueUniqueWork(plan.workName, plan.policy, request(first.kind, first.payload))
        } else {
            val second = plan.steps[1]
            manager.beginUniqueWork(plan.workName, plan.policy, request(first.kind, first.payload))
                .then(request(second.kind, second.payload))
                .enqueue()
        }
    }

    fun shareBakePhoto(recordId: Long, photoPath: String) {
        val deviceId = DeviceIdentity.id(context)
        val payload = BakePhotoPayload(
            clientEventId = SyncEventId.forBake(deviceId, recordId),
            photoPath = photoPath,
        )
        enqueuePhotoMutation(recordId, SyncWorker.KIND_BAKE_PHOTO, payload)
    }

    fun clearBakePhoto(recordId: Long) {
        val deviceId = DeviceIdentity.id(context)
        val payload = BakePhotoPayload(
            clientEventId = SyncEventId.forBake(deviceId, recordId),
            photoPath = "",
        )
        enqueuePhotoMutation(recordId, SyncWorker.KIND_BAKE_PHOTO_CLEAR, payload)
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

    private fun enqueuePhotoMutation(recordId: Long, kind: String, payload: BakePhotoPayload) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-bake-chain-$recordId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(kind, gson.toJson(payload)),
        )
    }

    private fun request(kind: String, payload: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_KIND to kind, SyncWorker.KEY_PAYLOAD to payload))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    private fun enqueue(uniqueName: String, kind: String, payload: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            request(kind, payload),
        )
    }
}

internal data class BakePhotoPayload(
    @SerializedName("client_event_id") val clientEventId: String,
    @SerializedName("photo_path") val photoPath: String,
)
