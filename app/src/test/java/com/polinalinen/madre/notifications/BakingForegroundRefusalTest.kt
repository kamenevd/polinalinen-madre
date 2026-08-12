package com.polinalinen.madre.notifications

import android.app.ForegroundServiceStartNotAllowedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cycle 20: отказ в переднем плане — это конец сервиса, а не повод жить дальше.
 *
 * До этого цикла отказ просто возвращал управление: сервис оставался поднятым,
 * но без переднего плана и без единого своего уведомления в шторке. Дальше он
 * либо молча висел, пока система не убьёт его за молчание, либо — хуже —
 * держал в шторке строку хода выпечки, которую больше некому обновлять: тот
 * самый застывший прогресс, ради недопущения которого сервис и заведён.
 *
 * Классификация вынесена в чистую функцию по образцу
 * [BakingProgressService.specialUseForegroundType]: поднимать настоящий сервис
 * ради проверки одной развилки незачем, а развилка здесь нетривиальная —
 * «система не дала стартовать из фона» и «мы сами ошиблись» выглядят одинаково
 * (оба Exception), а поступать с ними надо противоположно.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BakingForegroundRefusalTest {

    /** Запрет старта из фона (Android 12+) — законный отказ, книга его переживает. */
    @Test
    fun `a background start restriction is a refusal, not a crash`() {
        val refusal = ForegroundServiceStartNotAllowedException("нельзя из фона")
        assertThat(BakingProgressService.isForegroundRefusal(refusal, sdk = 35)).isTrue()
        assertThat(BakingProgressService.isForegroundRefusal(refusal, sdk = 31)).isTrue()
    }

    /**
     * До Android 12 такого запрета нет вовсе, и списывать на него отказ нельзя:
     * там это означало бы нашу ошибку, спрятанную под чужим именем.
     */
    @Test
    fun `below Android 12 there is no such restriction to blame`() {
        val refusal = ForegroundServiceStartNotAllowedException("нельзя из фона")
        assertThat(BakingProgressService.isForegroundRefusal(refusal, sdk = 30)).isFalse()
    }

    /**
     * Любой другой отказ — это сервис без переднего плана по НАШЕЙ ошибке.
     * Глотать его нельзя: он должен упасть и быть починен, а не превратиться в
     * тихо неработающую выпечку.
     */
    @Test
    fun `any other failure is not swallowed`() {
        assertThat(BakingProgressService.isForegroundRefusal(IllegalStateException("тип не тот"), sdk = 35))
            .isFalse()
        assertThat(BakingProgressService.isForegroundRefusal(SecurityException("нет права"), sdk = 35))
            .isFalse()
    }
}
