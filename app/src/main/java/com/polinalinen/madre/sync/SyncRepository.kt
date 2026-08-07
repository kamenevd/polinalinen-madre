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
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.FeedingStatRecord
import com.polinalinen.madre.data.remote.PocketBaseDates
import java.util.concurrent.TimeUnit

/**
 * Шаринг статистики в общую книгу (Cycle 5): каждая выпечка/кормление
 * превращается в OneTimeWorkRequest — без сети WorkManager подождёт её и
 * дошлёт сам (retry-политика — SyncPolicy). Очередь на запись — уникальное
 * имя + KEEP: повторный вызов с тем же ключом (например, кнопка «Поделиться»
 * после автоотправки) не создаёт дубликат записи на сервере.
 *
 * Cycle 15: то же обещание, но и за пределами очереди — каждая запись несёт
 * client_event_id ([SyncEventId]). Уникальное имя работы защищает, только пока
 * работа ещё в очереди этого устройства; повтор после успешного POST с
 * потерянным ответом, доотправка из старой очереди или переустановка ему уже
 * не видны, а серверу по ключу — видны.
 */
class SyncRepository(private val context: Context) {

    private val gson = Gson()

    /**
     * @param recordId id строки bake_records — он же ключ события. Номер
     *   сессии сюда больше не приходит: см. [SyncEventId.forBake].
     */
    fun shareBakeStat(recordId: Long, recipeId: String, recipeName: String, portions: Int, bakedAtMillis: Long) {
        val deviceId = DeviceIdentity.id(context)
        val record = BakeStatRecord(
            deviceId = deviceId,
            clientEventId = SyncEventId.forBake(deviceId, recordId),
            recipeId = recipeId,
            recipeName = recipeName,
            portions = portions,
            bakedAt = PocketBaseDates.toIso(bakedAtMillis),
        )
        enqueue("sync-bake-record-$recordId", SyncWorker.KIND_BAKE, gson.toJson(record))
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

    private fun enqueue(uniqueName: String, kind: String, payload: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_KIND to kind, SyncWorker.KEY_PAYLOAD to payload))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }
}
