package com.polinalinen.madre.sync

import java.io.IOException
import retrofit2.HttpException

/**
 * Решение «повторять или сдаться» для SyncWorker — чистая функция, чтобы
 * покрыть тестами без WorkManager. Сеть/5xx — временные (retry с backoff),
 * 4xx — контракт сломан и повтор бесполезен, всё прочее — баг, не долбим сервер.
 */
enum class SyncOutcome { RETRY, GIVE_UP }

object SyncPolicy {

    /** После этого числа попыток перестаём: статистика — не критичные данные. */
    const val MAX_ATTEMPTS = 5

    fun classify(error: Throwable, runAttemptCount: Int): SyncOutcome {
        if (runAttemptCount >= MAX_ATTEMPTS) return SyncOutcome.GIVE_UP
        return when {
            error is IOException -> SyncOutcome.RETRY
            error is HttpException && error.code() in 500..599 -> SyncOutcome.RETRY
            else -> SyncOutcome.GIVE_UP
        }
    }

    /**
     * Cycle 17: тот же отказ, но словами колофона ([SyncStatus.line]).
     *
     * Отдельно от [classify] потому, что вопросы разные: «повторять ли» и «что
     * сказать человеку» отвечаются по-разному на одном и том же ответе. 401
     * повторять бесполезно (GIVE_UP), но человеку есть что сделать — войти
     * заново; 503 повторять стоит, а говорить — нечего, кроме «подождём».
     */
    fun stateFor(error: Throwable): SyncStatus.State = when {
        error is HttpException && error.code() in listOf(401, 403) -> SyncStatus.State.DENIED
        error is HttpException && error.code() in 500..599 -> SyncStatus.State.UNREACHABLE
        error is HttpException -> SyncStatus.State.REJECTED
        error is IOException -> SyncStatus.State.UNREACHABLE
        else -> SyncStatus.State.REJECTED
    }
}
