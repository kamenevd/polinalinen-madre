package com.polinalinen.madre.crash

import android.app.Application
import com.polinalinen.madre.BuildConfig
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig

/**
 * Отчёты о падениях через AppMetrica (Яндекс): работает без Google/Huawei
 * сервисов, данные в РФ.
 *
 * Выключено, пока ключ пустой: ключ приходит только из секрета CI
 * `MADRE_APPMETRICA_API_KEY` в release-сборке. Debug и локальные сборки
 * ничего не отправляют. Геолокация выключена — приложению она для отчётов
 * не нужна.
 */
object CrashReporting {

    val isEnabled: Boolean
        get() = !BuildConfig.DEBUG && BuildConfig.APPMETRICA_API_KEY.isNotBlank()

    fun start(app: Application) {
        if (!isEnabled) return
        val config = AppMetricaConfig.newConfigBuilder(BuildConfig.APPMETRICA_API_KEY)
            .withCrashReporting(true)
            .withNativeCrashReporting(true)
            .withLocationTracking(false)
            .build()
        AppMetrica.activate(app, config)
    }
}
