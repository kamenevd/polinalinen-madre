package com.polinalinen.madre.sourdough

import kotlin.math.*

/**
 * Sourdough feeding profile — recommendations based on feeding interval.
 * Портировано из ветки feat/living-culture-v2 (ui/livingculture/SourdoughProfile.kt),
 * пакет изменён на sourdough (в v4 нет ui.livingculture — growth chart не переносим,
 * decision #12: "Journal + status card, NO chart"). Математика графика/фаз оставлена
 * как есть: используется для расчёта статуса закваски на StarterDetailScreen,
 * просто без Canvas-визуализации кривой.
 */
data class SourdoughProfile(
    val intervalHours: Int,
    val temperature: Int,
    val temperatureNote: String,
    val hydration: Int,
    val hydrationNote: String,
    val ratioStarter: Int,
    val ratioFlour: Int,
    val ratioWater: Int,
    val flourRecommendation: String,
    val flourNote: String,
    val peakHours: Float,
    val cycleHours: Float,
    val phaseLabels: PhaseLabels,
)

data class PhaseLabels(
    val lag: String,
    val growing: String,
    val peak: String,
    val declining: String,
)

enum class GrowthPhase { EMPTY, LAG, GROWING, PEAK, DECLINING, HUNGRY }

fun profileForInterval(hours: Int): SourdoughProfile = when (hours) {
    12 -> SourdoughProfile(
        intervalHours = 12, temperature = 27, temperatureNote = "Тёплая, быстрый цикл",
        hydration = 100, hydrationNote = "Жидкая, 1:1 мука:вода",
        ratioStarter = 1, ratioFlour = 3, ratioWater = 3,
        flourRecommendation = "По руководству: белая пшеничная высшего сорта", flourNote = "Белка больше 10 г на 100 г",
        peakHours = 4f, cycleHours = 12f,
        phaseLabels = PhaseLabels("0-1ч", "1-4ч", "4-6ч", "6-12ч"),
    )
    24 -> SourdoughProfile(
        intervalHours = 24, temperature = 24, temperatureNote = "Комнатная, классический цикл",
        hydration = 100, hydrationNote = "Жидкая, 1:1 мука:вода",
        ratioStarter = 1, ratioFlour = 5, ratioWater = 5,
        flourRecommendation = "По руководству: белая пшеничная высшего сорта", flourNote = "Белка больше 10 г на 100 г",
        peakHours = 6f, cycleHours = 24f,
        phaseLabels = PhaseLabels("0-2ч", "2-6ч", "6-8ч", "8-24ч"),
    )
    48 -> SourdoughProfile(
        intervalHours = 48, temperature = 20, temperatureNote = "Прохладная, замедленный цикл",
        hydration = 80, hydrationNote = "Полугустая, 100г муки · 80г воды",
        ratioStarter = 1, ratioFlour = 8, ratioWater = 8,
        flourRecommendation = "По руководству: белая пшеничная высшего сорта", flourNote = "Белка больше 10 г на 100 г",
        peakHours = 10f, cycleHours = 48f,
        phaseLabels = PhaseLabels("0-4ч", "4-10ч", "10-14ч", "14-48ч"),
    )
    72 -> SourdoughProfile(
        intervalHours = 72, temperature = 4, temperatureNote = "Холодильник, замедляет метаболизм",
        hydration = 60, hydrationNote = "Густая, 100г муки · 60г воды",
        ratioStarter = 1, ratioFlour = 10, ratioWater = 10,
        flourRecommendation = "По руководству: белая пшеничная высшего сорта", flourNote = "Белка больше 10 г на 100 г",
        peakHours = 24f, cycleHours = 72f,
        phaseLabels = PhaseLabels("0-8ч", "8-24ч", "24-36ч", "36-72ч"),
    )
    168 -> SourdoughProfile(
        intervalHours = 168, temperature = 4, temperatureNote = "Холодильник, еженедельное хранение",
        hydration = 60, hydrationNote = "Густая, 100г муки · 60г воды",
        ratioStarter = 1, ratioFlour = 20, ratioWater = 20,
        flourRecommendation = "По руководству: белая пшеничная высшего сорта", flourNote = "Белка больше 10 г на 100 г",
        peakHours = 48f, cycleHours = 168f,
        phaseLabels = PhaseLabels("0-12ч", "12-48ч", "48-72ч", "72-168ч"),
    )
    else -> profileForInterval(24)
}

/** Часы с последнего кормления, по времени устройства. */
fun hoursSinceFeeding(lastFeedingMillis: Long): Float {
    val now = System.currentTimeMillis()
    return (now - lastFeedingMillis) / 3_600_000f
}

/** Текущая фаза закваски по часам с кормления. */
fun currentPhase(hours: Float, profile: SourdoughProfile): GrowthPhase {
    val peak = profile.peakHours
    val cycle = profile.cycleHours
    return when {
        hours < 0f -> GrowthPhase.EMPTY
        hours <= peak * 0.25f -> GrowthPhase.LAG
        hours <= peak * 0.85f -> GrowthPhase.GROWING
        hours <= peak * 1.3f -> GrowthPhase.PEAK
        hours <= cycle -> GrowthPhase.DECLINING
        else -> GrowthPhase.HUNGRY
    }
}

fun formatHourOffset(hours: Float): String {
    val absH = abs(hours)
    return when {
        absH < 1f -> "${(absH * 60).toInt()}м"
        absH < 24f -> "${absH.toInt()}ч"
        else -> "${(absH / 24f).toInt()}д"
    }
}
