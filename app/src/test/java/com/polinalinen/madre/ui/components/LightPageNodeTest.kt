package com.polinalinen.madre.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

/**
 * Cycle 16: «Страница на просвет» переехала с composed {} + DisposableEffect на
 * собственный Modifier.Node.
 *
 * Подписка на датчик — единственное в этом модификаторе, что переживает кадр, и
 * единственное, что может утечь: слушатель ROTATION_VECTOR, оставшийся висеть
 * после ухода с дневника закваски, будит процесс и жрёт батарею молча — на
 * экране этого не видно вообще никак.
 *
 * Раньше подписку держал DisposableEffect, то есть она была привязана к
 * композиции. Теперь — к присоединению узла к дереву (onAttach/onDetach).
 * Здесь проверяется, что это действительно так: слушатель появляется, когда
 * страница на экране, и снимается, когда она с экрана ушла.
 *
 * Отдельно проверяется случай «датчика в телефоне нет»: тогда фича обязана
 * молчать целиком и не подписываться ни на что.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class LightPageNodeTest {

    @get:Rule
    val rule = createComposeRule()

    private val sensorManager: SensorManager
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun withRotationSensor() {
        shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR))
    }

    @Test
    fun `страница на экране — слушатель датчика подписан`() {
        withRotationSensor()

        rule.setContent {
            MadreTheme {
                Box(Modifier.fillMaxSize().lightPage(watermark = "MADRE"))
            }
        }
        rule.waitForIdle()

        assertThat(shadowOf(sensorManager).listeners).hasSize(1)
    }

    @Test
    fun `страница ушла с экрана — слушатель датчика снят`() {
        withRotationSensor()

        var visible by mutableStateOf(true)
        rule.setContent {
            MadreTheme {
                if (visible) Box(Modifier.fillMaxSize().lightPage(watermark = "MADRE"))
            }
        }
        rule.waitForIdle()
        assertThat(shadowOf(sensorManager).listeners).hasSize(1)

        // Уходим со страницы: узел отсоединяется от дерева.
        visible = false
        rule.waitForIdle()

        assertThat(shadowOf(sensorManager).listeners).isEmpty()
    }

    @Test
    fun `вход и выход несколько раз не копят слушателей`() {
        withRotationSensor()

        var visible by mutableStateOf(true)
        rule.setContent {
            MadreTheme {
                if (visible) Box(Modifier.fillMaxSize().lightPage(watermark = "MADRE"))
            }
        }

        repeat(3) {
            visible = false
            rule.waitForIdle()
            assertThat(shadowOf(sensorManager).listeners).isEmpty()
            visible = true
            rule.waitForIdle()
            // Именно один, а не «хотя бы один»: накопленные подписки — это и есть утечка.
            assertThat(shadowOf(sensorManager).listeners).hasSize(1)
        }
    }

    @Test
    fun `без датчика ROTATION_VECTOR модификатор не подписывается ни на что`() {
        // Датчик намеренно не добавляем — телефон без ROTATION_VECTOR.
        rule.setContent {
            MadreTheme {
                Box(Modifier.fillMaxSize().lightPage(watermark = "MADRE"))
            }
        }
        rule.waitForIdle()

        assertThat(shadowOf(sensorManager).listeners).isEmpty()
    }
}
