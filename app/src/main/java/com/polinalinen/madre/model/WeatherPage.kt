package com.polinalinen.madre.model

/**
 * «Погода за окном» (DESIGN-V4.md Cycle 7, фича WeatherPage): книга знает
 * локальную погоду через Open-Meteo и Мадре оставляет заметку на полях —
 * влажность, жара и холод по-настоящему меняют поведение теста. В дождь
 * страницы слегка отсыревают (Modifier.dampPaper).
 *
 * Здесь только чистые правила «погода → заметка/влажность бумаги»
 * (юнит-тест WeatherPageTest); сеть — data/remote/WeatherApi,
 * геолокация и состояние — viewmodel/WeatherViewModel.
 */
data class WeatherNote(
    val text: String,
    /** Насколько отсырела бумага: 0 — сухо, дальше — сила разводов dampPaper. */
    val dampAlpha: Float,
)

object WeatherPage {

    // Коды погоды WMO, которые отдаёт Open-Meteo.
    private val DRIZZLE_AND_RAIN = 51..67
    private val RAIN_SHOWERS = 80..82
    private val THUNDERSTORM = 95..99
    private val SNOW = 71..77
    private val SNOW_SHOWERS = 85..86

    fun isSnowing(weatherCode: Int): Boolean =
        weatherCode in SNOW || weatherCode in SNOW_SHOWERS

    /** Дождь: по коду, либо по факту осадков — но снег бумагу в доме не мочит. */
    fun isRaining(weatherCode: Int, precipitationMm: Double): Boolean =
        weatherCode in DRIZZLE_AND_RAIN || weatherCode in RAIN_SHOWERS ||
            weatherCode in THUNDERSTORM || (precipitationMm > 0.0 && !isSnowing(weatherCode))

    /**
     * Сила разводов на бумаге: лёгкая морось — едва заметно, ливень — сильнее,
     * но с потолком: страница отсырела, а не утонула.
     */
    fun dampAlpha(weatherCode: Int, precipitationMm: Double): Float =
        if (!isRaining(weatherCode, precipitationMm)) 0f
        else (0.05f + 0.02f * precipitationMm.toFloat()).coerceAtMost(0.14f)

    /**
     * Заметка Мадре на полях. Приоритет: дождь → снег → влажно → жара →
     * холод; в мягкую сухую погоду Мадре молчит (null) — заметка должна
     * быть событием, а не постоянной плашкой.
     */
    fun noteFor(
        temperatureC: Double,
        humidityPercent: Int,
        weatherCode: Int,
        precipitationMm: Double,
    ): WeatherNote? {
        val damp = dampAlpha(weatherCode, precipitationMm)
        return when {
            isRaining(weatherCode, precipitationMm) -> WeatherNote(
                "за окном дождь, даже страницы отсырели. убавь воды в тесте — мука сегодня пьёт меньше",
                damp,
            )
            isSnowing(weatherCode) -> WeatherNote(
                "за окном снег. в доме сухо и прохладно — брожение не будет спешить, и я тоже",
                0f,
            )
            humidityPercent >= 75 -> WeatherNote(
                "сегодня влажно — убавь воды грамм на десять, тесто скажет спасибо",
                0f,
            )
            temperatureC >= 27.0 -> WeatherNote(
                "жара! брожение побежит быстрее обычного — поглядывай на меня, не проспи пик",
                0f,
            )
            temperatureC <= 16.0 -> WeatherNote(
                "холодно. тесто будет подниматься неспеша — дай ему лишний час в тёплом углу",
                0f,
            )
            else -> null
        }
    }
}
