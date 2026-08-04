package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Cycle 14: ритм выпечки стал календарём текущего месяца.
 *
 * До этого цикла на Полке стояли двенадцать безымянных квадратов «по неделям»:
 * по ним нельзя было сказать ни какой это день, ни какой месяц, а записи
 * соседних месяцев сливались в один столбик. Календарь обязан показать весь
 * месяц целиком — включая дни, когда не пекли, — и ни одного чужого дня.
 */
class MonthRhythmTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val august = YearMonth.of(2026, 8)

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the grid holds every day of the month, quiet ones included`() {
        val grid = MonthRhythm.build(listOf(millis(2026, 8, 4)), august, zone)
        assertThat(grid.days).hasSize(31)
        assertThat(grid.days.first().date).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(grid.days.last().date).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(grid.days.count { it.bakes == 0 }).isEqualTo(30)
    }

    @Test
    fun `a short month is short, and february knows about leap years`() {
        assertThat(MonthRhythm.build(emptyList(), YearMonth.of(2026, 2), zone).days).hasSize(28)
        assertThat(MonthRhythm.build(emptyList(), YearMonth.of(2028, 2), zone).days).hasSize(29)
        assertThat(MonthRhythm.build(emptyList(), YearMonth.of(2026, 4), zone).days).hasSize(30)
    }

    @Test
    fun `a day with bakes counts them all`() {
        val grid = MonthRhythm.build(
            listOf(millis(2026, 8, 4, hour = 9), millis(2026, 8, 4, hour = 20), millis(2026, 8, 7)),
            august,
            zone,
        )
        assertThat(grid.days.single { it.date.dayOfMonth == 4 }.bakes).isEqualTo(2)
        assertThat(grid.days.single { it.date.dayOfMonth == 7 }.bakes).isEqualTo(1)
        assertThat(grid.days.single { it.date.dayOfMonth == 5 }.bakes).isEqualTo(0)
    }

    /** Главное: соседний месяц — чужой, и в этот календарь он не попадает. */
    @Test
    fun `bakes from the months next door stay out of this one`() {
        val grid = MonthRhythm.build(
            listOf(
                millis(2026, 7, 31),
                millis(2026, 9, 1),
                millis(2025, 8, 4), // тот же месяц, но другой год
                millis(2026, 8, 4),
            ),
            august,
            zone,
        )
        assertThat(grid.days.sumOf { it.bakes }).isEqualTo(1)
        assertThat(grid.days.single { it.date.dayOfMonth == 4 }.bakes).isEqualTo(1)
    }

    @Test
    fun `the busiest day sets the scale for the rest`() {
        val grid = MonthRhythm.build(
            listOf(millis(2026, 8, 4), millis(2026, 8, 4), millis(2026, 8, 4), millis(2026, 8, 9)),
            august,
            zone,
        )
        assertThat(grid.maxBakes).isEqualTo(3)
    }

    @Test
    fun `a month with nothing baked has no scale to speak of`() {
        val grid = MonthRhythm.build(emptyList(), august, zone)
        assertThat(grid.maxBakes).isEqualTo(0)
        assertThat(grid.totalBakes).isEqualTo(0)
    }

    /**
     * Календарь начинается с понедельника: первое августа 2026 — суббота,
     * значит перед ней пять пустых клеток.
     */
    @Test
    fun `the first day lands on its real weekday`() {
        assertThat(MonthRhythm.build(emptyList(), august, zone).leadingBlanks).isEqualTo(5)
        // Июнь 2026 начинается с понедельника — пустых клеток нет вовсе.
        assertThat(MonthRhythm.build(emptyList(), YearMonth.of(2026, 6), zone).leadingBlanks).isEqualTo(0)
    }

    @Test
    fun `intensity is nothing at all when the day is quiet`() {
        assertThat(MonthRhythm.intensity(bakes = 0, maxBakes = 5)).isEqualTo(0)
    }

    @Test
    fun `intensity climbs with the day, up to the busiest one`() {
        assertThat(MonthRhythm.intensity(bakes = 1, maxBakes = 3)).isEqualTo(1)
        assertThat(MonthRhythm.intensity(bakes = 2, maxBakes = 3)).isEqualTo(2)
        assertThat(MonthRhythm.intensity(bakes = 3, maxBakes = 3)).isEqualTo(3)
    }

    /** Один-единственный день с выпечкой — самый тёмный, а не «одна треть». */
    @Test
    fun `a lone bake is the darkest square of its month`() {
        assertThat(MonthRhythm.intensity(bakes = 1, maxBakes = 1)).isEqualTo(3)
    }

    @Test
    fun `intensity does not divide by an empty month`() {
        assertThat(MonthRhythm.intensity(bakes = 0, maxBakes = 0)).isEqualTo(0)
        assertThat(MonthRhythm.intensity(bakes = 2, maxBakes = 0)).isEqualTo(0)
    }

    @Test
    fun `the month is named the way a russian calendar names it`() {
        assertThat(MonthRhythm.title(august)).isEqualTo("август 2026")
        assertThat(MonthRhythm.title(YearMonth.of(2026, 1))).isEqualTo("январь 2026")
        assertThat(MonthRhythm.title(YearMonth.of(2026, 12))).isEqualTo("декабрь 2026")
    }

    @Test
    fun `weekday letters start the week on monday`() {
        assertThat(MonthRhythm.WEEKDAY_LETTERS).hasSize(7)
        assertThat(MonthRhythm.WEEKDAY_LETTERS.first()).isEqualTo("пн")
        assertThat(MonthRhythm.WEEKDAY_LETTERS.last()).isEqualTo("вс")
    }

    /** Сколько всего испечено в этом месяце — подпись под календарём. */
    @Test
    fun `the month knows its own total`() {
        val grid = MonthRhythm.build(
            listOf(millis(2026, 8, 4), millis(2026, 8, 4), millis(2026, 8, 20), millis(2026, 7, 1)),
            august,
            zone,
        )
        assertThat(grid.totalBakes).isEqualTo(3)
    }
}
