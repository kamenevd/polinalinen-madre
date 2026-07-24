package com.polinalinen.madre.model

import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.PocketBaseDates

/**
 * Сводка «общей книги» для главной (Cycle 5): считается из bake_stats ДРУГИХ
 * семей (свой device_id отфильтрован ещё запросом). Чистая функция от списка
 * записей — сеть и состояние живут в CommunityStatsViewModel.
 */
data class CommunityStats(
    val familiesBaking: Int,
    val popularRecipeOfWeek: String?,
    val bakesThisWeek: Int,
) {
    companion object {
        private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

        fun from(records: List<BakeStatRecord>, nowMillis: Long = System.currentTimeMillis()): CommunityStats {
            val weekRecords = records.filter { record ->
                val bakedAt = PocketBaseDates.parseOrNull(record.bakedAt) ?: return@filter false
                bakedAt >= nowMillis - WEEK_MILLIS && bakedAt <= nowMillis
            }
            val popular = weekRecords
                .groupingBy { it.recipeName }
                .eachCount()
                .entries
                // При равенстве побеждает алфавитно первый — чтобы ярлык не
                // мигал разными названиями от запроса к запросу.
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key
            return CommunityStats(
                familiesBaking = records.map { it.deviceId }.distinct().size,
                popularRecipeOfWeek = popular,
                bakesThisWeek = weekRecords.size,
            )
        }
    }
}
