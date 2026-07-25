package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 7, фича «Погода за окном» (WeatherPage): правила
 * «погода → заметка Мадре на полях» и влажность бумаги в дождь.
 * Коды погоды — WMO, как их отдаёт Open-Meteo.
 */
class WeatherPageTest {

    private val CLEAR = 0
    private val RAIN = 63          // умеренный дождь
    private val RAIN_SHOWER = 80
    private val THUNDER = 95
    private val SNOW = 73

    @Test
    fun `mild dry weather keeps madre silent`() {
        assertThat(WeatherPage.noteFor(22.0, 55, CLEAR, 0.0)).isNull()
    }

    @Test
    fun `rain dampens the paper and asks for less water`() {
        val note = WeatherPage.noteFor(20.0, 80, RAIN, 2.0)
        assertThat(note).isNotNull()
        assertThat(note!!.dampAlpha).isGreaterThan(0f)
        assertThat(note.text).contains("дождь")
        assertThat(note.text).contains("воды")
    }

    @Test
    fun `rain is detected by code and by actual precipitation`() {
        assertThat(WeatherPage.isRaining(RAIN, 0.0)).isTrue()
        assertThat(WeatherPage.isRaining(RAIN_SHOWER, 0.0)).isTrue()
        assertThat(WeatherPage.isRaining(THUNDER, 0.0)).isTrue()
        assertThat(WeatherPage.isRaining(CLEAR, 0.4)).isTrue()
        assertThat(WeatherPage.isRaining(CLEAR, 0.0)).isFalse()
    }

    @Test
    fun `snow is not rain — the paper stays dry indoors`() {
        assertThat(WeatherPage.isRaining(SNOW, 1.5)).isFalse()
        val note = WeatherPage.noteFor(-3.0, 85, SNOW, 1.5)
        assertThat(note).isNotNull()
        assertThat(note!!.dampAlpha).isEqualTo(0f)
        assertThat(note.text).contains("снег")
    }

    @Test
    fun `damp alpha grows with precipitation but is capped`() {
        val drizzle = WeatherPage.dampAlpha(RAIN, 0.2)
        val pour = WeatherPage.dampAlpha(RAIN, 8.0)
        assertThat(drizzle).isGreaterThan(0f)
        assertThat(pour).isGreaterThan(drizzle)
        assertThat(WeatherPage.dampAlpha(RAIN, 100.0)).isAtMost(0.14f)
        assertThat(WeatherPage.dampAlpha(CLEAR, 0.0)).isEqualTo(0f)
    }

    @Test
    fun `humid day asks to cut back on water`() {
        val note = WeatherPage.noteFor(22.0, 80, CLEAR, 0.0)
        assertThat(note).isNotNull()
        assertThat(note!!.text).contains("влажно")
        assertThat(note.dampAlpha).isEqualTo(0f)
    }

    @Test
    fun `heat warns about faster fermentation`() {
        val note = WeatherPage.noteFor(31.0, 40, CLEAR, 0.0)
        assertThat(note).isNotNull()
        assertThat(note!!.text).contains("жара")
    }

    @Test
    fun `cold promises a slow rise`() {
        val note = WeatherPage.noteFor(12.0, 50, CLEAR, 0.0)
        assertThat(note).isNotNull()
        assertThat(note!!.text).contains("холодно")
    }

    @Test
    fun `rain outranks heat and humidity in the margin`() {
        val note = WeatherPage.noteFor(30.0, 90, RAIN, 1.0)
        assertThat(note!!.text).contains("дождь")
    }
}
