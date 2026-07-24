package com.polinalinen.madre.sync

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Cycle 5, SyncWorker: retry-политика — чистая функция, проверяем без
 * WorkManager. Важно, чтобы 4xx (сломанный контракт с PocketBase) не
 * долбил сервер повторами, а отсутствие сети — наоборот, доживало до retry.
 */
class SyncPolicyTest {

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
    )

    @Test
    fun `network errors retry`() {
        assertThat(SyncPolicy.classify(IOException("нет сети"), 1)).isEqualTo(SyncOutcome.RETRY)
    }

    @Test
    fun `server 5xx retries but client 4xx gives up`() {
        assertThat(SyncPolicy.classify(httpError(503), 1)).isEqualTo(SyncOutcome.RETRY)
        assertThat(SyncPolicy.classify(httpError(400), 1)).isEqualTo(SyncOutcome.GIVE_UP)
        assertThat(SyncPolicy.classify(httpError(404), 1)).isEqualTo(SyncOutcome.GIVE_UP)
    }

    @Test
    fun `unexpected errors give up`() {
        assertThat(SyncPolicy.classify(IllegalStateException("баг"), 1)).isEqualTo(SyncOutcome.GIVE_UP)
    }

    @Test
    fun `gives up after max attempts even on network error`() {
        assertThat(SyncPolicy.classify(IOException(), SyncPolicy.MAX_ATTEMPTS - 1)).isEqualTo(SyncOutcome.RETRY)
        assertThat(SyncPolicy.classify(IOException(), SyncPolicy.MAX_ATTEMPTS)).isEqualTo(SyncOutcome.GIVE_UP)
    }
}
