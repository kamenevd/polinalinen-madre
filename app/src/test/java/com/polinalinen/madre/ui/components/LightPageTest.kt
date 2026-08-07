package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 4, фича «Страница на просвет» (LightPage): наклон
 * (pitch/roll из ROTATION_VECTOR) -> спека блика — чистая функция. Проверяем
 * полосу alpha 0.05..0.08 из спецификации, детерминированность и то, что
 * блик следует за наклоном, не покидая страницы.
 */
class LightPageTest {

    @Test
    fun `flat phone keeps the sheen at the minimum alpha`() {
        assertThat(LightPage.specFor(pitchRad = 0f, rollRad = 0f).alpha).isEqualTo(LightPage.ALPHA_MIN)
    }

    @Test
    fun `alpha always stays inside the 0,05 to 0,08 spec band`() {
        val tilts = listOf(-3.14f, -1.5f, -0.7f, -0.2f, 0f, 0.1f, 0.5f, 1f, 2f, 3.14f)
        tilts.forEach { pitch ->
            tilts.forEach { roll ->
                val spec = LightPage.specFor(pitch, roll)
                assertThat(spec.alpha).isAtLeast(LightPage.ALPHA_MIN)
                assertThat(spec.alpha).isAtMost(LightPage.ALPHA_MAX)
            }
        }
    }

    @Test
    fun `stronger tilt catches more light`() {
        val slight = LightPage.specFor(pitchRad = 0.1f, rollRad = 0f)
        val steep = LightPage.specFor(pitchRad = 0.6f, rollRad = 0f)
        assertThat(steep.alpha).isGreaterThan(slight.alpha)
    }

    @Test
    fun `sheen centre never leaves the page`() {
        val tilts = listOf(-3.14f, -1f, 0f, 1f, 3.14f)
        tilts.forEach { pitch ->
            tilts.forEach { roll ->
                val spec = LightPage.specFor(pitch, roll)
                assertThat(spec.cxFraction).isAtLeast(0f)
                assertThat(spec.cxFraction).isAtMost(1f)
                assertThat(spec.cyFraction).isAtLeast(0f)
                assertThat(spec.cyFraction).isAtMost(1f)
            }
        }
    }

    @Test
    fun `sheen slides in the direction of the tilt`() {
        val flat = LightPage.specFor(0f, 0f)
        assertThat(flat.cxFraction).isEqualTo(0.5f)
        assertThat(flat.cyFraction).isEqualTo(0.5f)
        // Крен вправо уводит блик вправо, наклон «на себя» — вверх.
        assertThat(LightPage.specFor(0f, rollRad = 0.4f).cxFraction).isGreaterThan(0.5f)
        assertThat(LightPage.specFor(pitchRad = 0.4f, rollRad = 0f).cyFraction).isLessThan(0.5f)
    }

    @Test
    fun `same tilt always produces the same spec`() {
        assertThat(LightPage.specFor(0.3f, -0.2f)).isEqualTo(LightPage.specFor(0.3f, -0.2f))
    }

    // Cycle 16: порог, отсекающий микродрожание руки. Раньше он был вписан
    // прямо в слушатель датчика и проверялся только глазами, на телефоне.

    @Test
    fun `первый замер всегда осмысленный — блику неоткуда сдвигаться`() {
        assertThat(LightPage.isMeaningfulChange(null, LightPage.specFor(0f, 0f))).isTrue()
    }

    @Test
    fun `дрожание руки не двигает блик`() {
        val current = LightPage.specFor(pitchRad = 0.30f, rollRad = -0.20f)
        // Наклон на сотую радиана — это меньше градуса: рука, а не жест.
        val jitter = LightPage.specFor(pitchRad = 0.3015f, rollRad = -0.2015f)
        assertThat(LightPage.isMeaningfulChange(current, jitter)).isFalse()
    }

    @Test
    fun `настоящий наклон блик двигает`() {
        val current = LightPage.specFor(pitchRad = 0.0f, rollRad = 0.0f)
        val tilted = LightPage.specFor(pitchRad = 0.0f, rollRad = 0.3f)
        assertThat(LightPage.isMeaningfulChange(current, tilted)).isTrue()
    }

    @Test
    fun `один и тот же наклон не считается изменением`() {
        val spec = LightPage.specFor(0.3f, -0.2f)
        assertThat(LightPage.isMeaningfulChange(spec, spec)).isFalse()
    }
}
