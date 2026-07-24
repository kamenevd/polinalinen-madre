package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.PocketBaseDates
import org.junit.Test

/**
 * Cycle 5, «Общая статистика»: семьи считаются по уникальным device_id,
 * «рецепт недели» — только из выпечек последних 7 дней, при равенстве —
 * детерминированно (алфавит), битые даты не роняют подсчёт.
 */
class CommunityStatsTest {

    private val now = 1_784_887_200_000L // 2026-07-24 10:00:00 UTC

    private fun record(device: String, recipe: String, daysAgo: Long, bakedAt: String? = null) = BakeStatRecord(
        deviceId = device,
        recipeId = recipe.lowercase(),
        recipeName = recipe,
        portions = 1,
        bakedAt = bakedAt ?: PocketBaseDates.toIso(now - daysAgo * 86_400_000L),
    )

    @Test
    fun `families counted by distinct device ids across all records`() {
        val stats = CommunityStats.from(
            listOf(record("a", "Багет", 1), record("a", "Багет", 2), record("b", "Ржаной", 30)),
            now,
        )
        assertThat(stats.familiesBaking).isEqualTo(2)
    }

    @Test
    fun `popular recipe considers only last seven days`() {
        val stats = CommunityStats.from(
            listOf(
                record("a", "Багет", 1),
                record("b", "Багет", 3),
                // Чиабатту пекли чаще, но месяц назад — в «неделю» не входит.
                record("a", "Чиабатта", 20),
                record("b", "Чиабатта", 21),
                record("c", "Чиабатта", 22),
            ),
            now,
        )
        assertThat(stats.popularRecipeOfWeek).isEqualTo("Багет")
        assertThat(stats.bakesThisWeek).isEqualTo(2)
    }

    @Test
    fun `tie breaks alphabetically for a stable label`() {
        val stats = CommunityStats.from(
            listOf(record("a", "Ржаной", 1), record("b", "Багет", 2)),
            now,
        )
        assertThat(stats.popularRecipeOfWeek).isEqualTo("Багет")
    }

    @Test
    fun `unparseable dates are skipped and empty week yields null recipe`() {
        val stats = CommunityStats.from(
            listOf(record("a", "Багет", 0, bakedAt = "мусор"), record("b", "Ржаной", 40)),
            now,
        )
        assertThat(stats.popularRecipeOfWeek).isNull()
        assertThat(stats.bakesThisWeek).isEqualTo(0)
        assertThat(stats.familiesBaking).isEqualTo(2)
    }
}
