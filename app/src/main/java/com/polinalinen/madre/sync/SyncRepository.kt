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
import com.polinalinen.madre.data.remote.MarginNoteSyncRecord
import com.polinalinen.madre.data.remote.PocketBaseDates
import java.util.concurrent.TimeUnit

/**
 * Шаринг статистики в общую книгу (Cycle 5): каждая выпечка/кормление/заметка
 * превращается в OneTimeWorkRequest — без сети WorkManager подождёт её и
 * дошлёт сам (retry-политика — SyncPolicy). Очередь на запись — уникальное
 * имя + KEEP: повторный вызов с тем же ключом (например, кнопка «Поделиться»
 * после автоотправки) не создаёт дубликат записи на сервере.
 */
class SyncRepository(private val context: Context) {

    private val gson = Gson()

    fun shareBakeStat(sessionKey: Long, recipeId: String, recipeName: String, portions: Int, bakedAtMillis: Long) {
        val record = BakeStatRecord(
            deviceId = DeviceIdentity.id(context),
            recipeId = recipeId,
            recipeName = recipeName,
            portions = portions,
            bakedAt = PocketBaseDates.toIso(bakedAtMillis),
        )
        enqueue("sync-bake-$sessionKey", SyncWorker.KIND_BAKE, gson.toJson(record))
    }

    fun shareFeedingStat(feedingId: Long, flourGrams: Int, waterGrams: Int, fedAtMillis: Long) {
        val record = FeedingStatRecord(
            deviceId = DeviceIdentity.id(context),
            flourGrams = flourGrams,
            waterGrams = waterGrams,
            fedAt = PocketBaseDates.toIso(fedAtMillis),
        )
        enqueue("sync-feeding-$feedingId", SyncWorker.KIND_FEEDING, gson.toJson(record))
    }

    fun shareMarginNote(noteId: Long, recipeId: String, text: String, writtenAtMillis: Long) {
        val record = MarginNoteSyncRecord(
            deviceId = DeviceIdentity.id(context),
            recipeId = recipeId,
            text = text,
            writtenAt = PocketBaseDates.toIso(writtenAtMillis),
        )
        enqueue("sync-note-$noteId", SyncWorker.KIND_MARGIN_NOTE, gson.toJson(record))
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
