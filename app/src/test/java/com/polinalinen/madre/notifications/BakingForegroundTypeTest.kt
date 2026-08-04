package com.polinalinen.madre.notifications

import android.content.pm.ServiceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 13 release blocker: тип specialUse у переднепланового сервиса появился
 * только в Android 14 (API 34). На 29..33 его передача не проходит проверку
 * системы и оставляет сервис без переднего плана — а значит, под нож. Граница
 * вынесена в чистую функцию, чтобы проверить её без самого сервиса.
 */
class BakingForegroundTypeTest {

    @Test
    fun `special use type is withheld below Android 14`() {
        listOf(26, 28, 29, 30, 31, 33).forEach { sdk ->
            assertThat(BakingProgressService.specialUseForegroundType(sdk)).isNull()
        }
    }

    @Test
    fun `special use type is passed from Android 14 up`() {
        listOf(34, 35).forEach { sdk ->
            assertThat(BakingProgressService.specialUseForegroundType(sdk))
                .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }
}
