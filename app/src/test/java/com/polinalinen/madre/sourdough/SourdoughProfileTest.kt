package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Фазовая модель закваски — тоже без тестов с момента портирования из
 * feat/living-culture-v2 (см. SourdoughProfile.kt). Проверяем ровно то,
 * что видит пользователь: выбор профиля по интервалу и границы фаз.
 */
class SourdoughProfileTest {

    @Test
    fun `profileForInterval returns the matching profile for each known interval`() {
        assertThat(profileForInterval(12).intervalHours).isEqualTo(12)
        assertThat(profileForInterval(24).intervalHours).isEqualTo(24)
        assertThat(profileForInterval(48).intervalHours).isEqualTo(48)
        assertThat(profileForInterval(72).intervalHours).isEqualTo(72)
        assertThat(profileForInterval(168).intervalHours).isEqualTo(168)
    }

    @Test
    fun `profileForInterval falls back to the 24h profile for unknown intervals`() {
        val fallback = profileForInterval(999)
        assertThat(fallback.intervalHours).isEqualTo(24)
        assertThat(fallback).isEqualTo(profileForInterval(24))
    }

    @Test
    fun `hoursSinceFeeding computes elapsed hours from a past timestamp`() {
        val now = System.currentTimeMillis()
        val threeHoursAgo = now - 3 * 3_600_000L
        // Без mock-часов — допуск в несколько секунд на выполнение теста.
        assertThat(hoursSinceFeeding(threeHoursAgo)).isWithin(0.01f).of(3.0f)
    }

    @Test
    fun `currentPhase walks through every phase boundary for the 24h profile`() {
        val profile = profileForInterval(24) // peakHours=6f, cycleHours=24f
        // Границы ниже — не "круглые" 1.5/5.1/7.8, а чуть по обе стороны от них:
        // peak*0.85 и peak*1.3 как Float не равны ровно 5.1/7.8 (0.85 и 1.3 не точны
        // в двоичной дроби, напр. 6f*1.3f == 7.799999714, а не 7.8f) — проверка
        // ровно на "круглом" числе однажды уже падала на этом биении округления.
        assertThat(currentPhase(-1f, profile)).isEqualTo(GrowthPhase.EMPTY)
        assertThat(currentPhase(0f, profile)).isEqualTo(GrowthPhase.LAG)
        assertThat(currentPhase(1.5f, profile)).isEqualTo(GrowthPhase.LAG) // peak*0.25 = 1.5 ровно (степень двойки)
        assertThat(currentPhase(1.51f, profile)).isEqualTo(GrowthPhase.GROWING)
        assertThat(currentPhase(5.09f, profile)).isEqualTo(GrowthPhase.GROWING) // peak*0.85 ≈ 5.1
        assertThat(currentPhase(5.11f, profile)).isEqualTo(GrowthPhase.PEAK)
        assertThat(currentPhase(7.79f, profile)).isEqualTo(GrowthPhase.PEAK) // peak*1.3 ≈ 7.8
        assertThat(currentPhase(7.81f, profile)).isEqualTo(GrowthPhase.DECLINING)
        assertThat(currentPhase(24f, profile)).isEqualTo(GrowthPhase.DECLINING) // == cycleHours, прямой литерал — точно
        assertThat(currentPhase(24.01f, profile)).isEqualTo(GrowthPhase.HUNGRY)
    }

    @Test
    fun `currentPhase scales with a different profile's peak and cycle hours`() {
        val profile = profileForInterval(72) // peakHours=24f, cycleHours=72f
        assertThat(currentPhase(20f, profile)).isEqualTo(GrowthPhase.GROWING) // 24*0.85=20.4, just under
        assertThat(currentPhase(30f, profile)).isEqualTo(GrowthPhase.PEAK) // 24*1.3=31.2
        assertThat(currentPhase(73f, profile)).isEqualTo(GrowthPhase.HUNGRY)
    }

    @Test
    fun `formatHourOffset renders sub-hour offsets in minutes`() {
        assertThat(formatHourOffset(0.5f)).isEqualTo("30м")
    }

    @Test
    fun `formatHourOffset renders same-day offsets in hours`() {
        assertThat(formatHourOffset(5f)).isEqualTo("5ч")
    }

    @Test
    fun `formatHourOffset renders multi-day offsets in days`() {
        assertThat(formatHourOffset(50f)).isEqualTo("2д")
    }

    @Test
    fun `formatHourOffset treats negative offsets the same as positive via abs`() {
        assertThat(formatHourOffset(-5f)).isEqualTo(formatHourOffset(5f))
    }
}
