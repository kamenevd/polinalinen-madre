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
import com.polinalinen.madre.data.remote.FeedingStatRecord
import com.polinalinen.madre.data.remote.MadreApi
import com.polinalinen.madre.data.remote.MadreApiFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Фоновая отправка одной записи в PocketBase (Cycle 5). Пейлоад приезжает
 * в inputData готовым JSON'ом (собирает SyncRepository) — воркер только
 * доставляет его и решает по SyncPolicy, повторять ли при неудаче.
 *
 * Cycle 17: доставка идёт под входом. Токен берётся из того же
 * [SecureTokenStore], которым живёт семейная книга: другого входа у приложения
 * нет, и заводить второй — значит однажды разойтись с ним. Токена нет —
 * работа не повторяется: без аккаунта общей книги не существует, и долбить
 * сервер до пятой попытки незачем. Исход в любом случае ложится в
 * [SyncStatus], откуда его читают «Выходные данные».
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val payload = inputData.getString(KEY_PAYLOAD) ?: return Result.failure()

        // Keystore и SharedPreferences — это диск: читаем на IO, а не на том
        // потоке, который WorkManager выдал под doWork.
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
            else -> error("Неизвестный вид синхронизации: $kind")
        }
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_PAYLOAD = "payload"
        const val KIND_BAKE = "bake"
        const val KIND_FEEDING = "feeding"

        /** Подмена api в тестах — у воркера нет конструктора под DI без Hilt. */
        @VisibleForTesting
        var apiOverride: MadreApi? = null

        /** Подмена хранилища токена в тестах: Keystore на JVM не поднять. */
        @VisibleForTesting
        var tokenStoreOverride: AuthTokenStore? = null
    }
}
