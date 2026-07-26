package com.polinalinen.madre.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.FeedingStatRecord
import com.polinalinen.madre.data.remote.MadreApi
import com.polinalinen.madre.data.remote.MadreApiFactory
import com.polinalinen.madre.data.remote.MarginNoteSyncRecord

/**
 * Фоновая отправка одной записи в PocketBase (Cycle 5). Пейлоад приезжает
 * в inputData готовым JSON'ом (собирает SyncRepository) — воркер только
 * доставляет его и решает по SyncPolicy, повторять ли при неудаче.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val payload = inputData.getString(KEY_PAYLOAD) ?: return Result.failure()
        val api = apiOverride ?: MadreApiFactory.create()
        return try {
            send(api, kind, payload)
            Result.success()
        } catch (e: Exception) {
            when (SyncPolicy.classify(e, runAttemptCount + 1)) {
                SyncOutcome.RETRY -> Result.retry()
                SyncOutcome.GIVE_UP -> Result.failure()
            }
        }
    }

    private suspend fun send(api: MadreApi, kind: String, payload: String) {
        val gson = Gson()
        when (kind) {
            KIND_BAKE -> api.postBakeStat(gson.fromJson(payload, BakeStatRecord::class.java))
            KIND_FEEDING -> api.postFeedingStat(gson.fromJson(payload, FeedingStatRecord::class.java))
            // Cycle 11: писать сюда больше некому — «Пометы на полях» убраны.
            // Ветка остаётся, чтобы уже поставленная в очередь WorkManager
            // отправка (она переживает обновление приложения) дослалась, а не
            // падала в бесконечный retry на неизвестном kind.
            KIND_MARGIN_NOTE -> api.postMarginNote(gson.fromJson(payload, MarginNoteSyncRecord::class.java))
            else -> error("Неизвестный вид синхронизации: $kind")
        }
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_PAYLOAD = "payload"
        const val KIND_BAKE = "bake"
        const val KIND_FEEDING = "feeding"
        const val KIND_MARGIN_NOTE = "margin_note"

        /** Подмена api в тестах — у воркера нет конструктора под DI без Hilt. */
        var apiOverride: MadreApi? = null
    }
}
