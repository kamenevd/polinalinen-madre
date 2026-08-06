package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Cycle 15: ритм года — арифметика годовой карты, отдельно от её отрисовки.
 *
 * Именованный тест назван по модели (как MonthRhythmTest до него), а не по
 * composable: считает здесь [YearRhythm], а YearHeatmap только красит.
 *
 * «Сегодня» здесь всегда одно и то же число: тест, который зависит от того,
 * в какой день его запустили, однажды краснеет сам по себе — и тогда ему
 * перестают верить.
 */
class YearRhythmTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    /** Четверг — специально не понедельник и не воскресенье. */
    private val today: LocalDate = LocalDate.of(2026, 8, 6)

    private fun millisOn(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun build(vararg dates: LocalDate) =
        YearRhythm.build(dates.map { millisOn(it) }, today, zone)

    private fun cellFor(grid: YearGrid, date: LocalDate): YearDay? =
        grid.weeks.flatten().filterNotNull().firstOrNull { it.date == date }

    // --- форма сетки ---

    @Test
    fun `the year is fifty two week columns of seven days`() {
        val grid = build()
        assertThat(grid.weeks).hasSize(YearRhythm.WEEKS)
        assertThat(grid.weeks.map { it.size }.distinct()).containsExactly(YearRhythm.WEEK_LENGTH)
    }

    /** Колонка — это неделя, значит начинается она с понедельника. */
    @Test
    fun `the window starts on a monday`() {
        val grid = build()
        assertThat(grid.from.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
        assertThat(ChronoUnit.WEEKS.between(grid.from, today.with(DayOfWeek.MONDAY)))
            .isEqualTo((YearRhythm.WEEKS - 1).toLong())
    }

    @Test
    fun `the window ends today`() {
        val grid = build()
        assertThat(grid.to).isEqualTo(today)
        assertThat(cellFor(grid, today)).isNotNull()
    }

    /**
     * Остаток текущей недели — пустые слоты, а не клетки «здесь не пекли».
     * Пятница, до которой книга ещё не дожила, ничего о ритме не говорит.
     */
    @Test
    fun `the days after today are empty slots, not quiet days`() {
        val grid = build()
        val lastWeek = grid.weeks.last()
        // Четверг — четвёртый день недели, значит заполнены ровно четыре слота.
        assertThat(lastWeek.take(4).all { it != null }).isTrue()
        assertThat(lastWeek.drop(4).all { it == null }).isTrue()
    }

    @Test
    fun `every day of the window has its own cell exactly once`() {
        val grid = build()
        val days = grid.weeks.flatten().filterNotNull().map { it.date }
        assertThat(days).hasSize(ChronoUnit.DAYS.between(grid.from, today).toInt() + 1)
        assertThat(days).isInOrder()
        assertThat(days.distinct()).hasSize(days.size)
    }

    // --- что попадает в окно ---

    @Test
    fun `a bake lands on its own day`() {
        val date = today.minusDays(40)
        val grid = build(date)
        assertThat(cellFor(grid, date)?.events).isEqualTo(1)
        assertThat(cellFor(grid, date.minusDays(1))?.events).isEqualTo(0)
    }

    @Test
    fun `two entries on one day stack into one cell`() {
        val date = today.minusDays(3)
        val grid = build(date, date)
        assertThat(cellFor(grid, date)?.events).isEqualTo(2)
    }

    /** Записи старше года в этой картинке не участвуют вовсе. */
    @Test
    fun `a bake from before the window is left out`() {
        val grid = build()
        val tooOld = grid.from.minusDays(1)
        val withOld = YearRhythm.build(listOf(millisOn(tooOld)), today, zone)
        assertThat(withOld.totalEvents).isEqualTo(0)
    }

    /**
     * Сбитые часы на телефоне или запись из чужого часового пояса не имеют
     * права нарисовать выпечку в дне, который ещё не наступил.
     */
    @Test
    fun `a bake dated in the future is left out`() {
        val grid = YearRhythm.build(listOf(millisOn(today.plusDays(2))), today, zone)
        assertThat(grid.totalEvents).isEqualTo(0)
    }

    /** Граница включительна с обеих сторон: и первый день окна, и сегодня. */
    @Test
    fun `both edges of the window count`() {
        val grid = build()
        val edges = YearRhythm.build(listOf(millisOn(grid.from), millisOn(today)), today, zone)
        assertThat(edges.totalEvents).isEqualTo(2)
        assertThat(cellFor(edges, edges.from)?.events).isEqualTo(1)
        assertThat(cellFor(edges, today)?.events).isEqualTo(1)
    }

    /** Кормления и выпечки — один список дат: карта не различает, чем занят день. */
    @Test
    fun `feedings and bakes share the same day`() {
        val date = today.minusDays(10)
        val grid = YearRhythm.build(
            listOf(millisOn(date, hour = 9), millisOn(date, hour = 20)),
            today,
            zone,
        )
        assertThat(cellFor(grid, date)?.events).isEqualTo(2)
        assertThat(grid.activeDays).isEqualTo(1)
    }

    // --- итоги ---

    @Test
    fun `an empty year counts nothing`() {
        val grid = build()
        assertThat(grid.totalEvents).isEqualTo(0)
        assertThat(grid.activeDays).isEqualTo(0)
    }

    @Test
    fun `totals count entries, active days count days`() {
        val date = today.minusDays(5)
        val grid = build(date, date, date, today)
        assertThat(grid.totalEvents).isEqualTo(4)
        assertThat(grid.activeDays).isEqualTo(2)
    }

    // --- насыщенность ---

    @Test
    fun `a quiet day has no colour`() {
        assertThat(YearRhythm.intensity(0)).isEqualTo(0)
    }

    /**
     * Шкала абсолютная: одна запись — всегда первая ступень, где бы в году она
     * ни стояла. Относительная (как в месяце) перекрасила бы обычные дни в
     * бледное ради одного дня с пятью записями, и привычка исчезла бы с карты.
     */
    @Test
    fun `one two and three entries climb the scale`() {
        assertThat(YearRhythm.intensity(1)).isEqualTo(1)
        assertThat(YearRhythm.intensity(2)).isEqualTo(2)
        assertThat(YearRhythm.intensity(3)).isEqualTo(YearRhythm.LEVELS)
    }

    @Test
    fun `a very busy day stops at the darkest step`() {
        assertThat(YearRhythm.intensity(9)).isEqualTo(YearRhythm.LEVELS)
        assertThat(YearRhythm.intensity(100)).isEqualTo(YearRhythm.LEVELS)
    }

    @Test
    fun `a negative count cannot happen but is not a colour either`() {
        assertThat(YearRhythm.intensity(-1)).isEqualTo(0)
    }

    // --- подписи месяцев ---

    @Test
    fun `a month is named over the week its first day falls into`() {
        val grid = build()
        val labels = YearRhythm.monthLabels(grid)
        assertThat(labels).hasSize(grid.weeks.size)

        grid.weeks.forEachIndexed { index, week ->
            val firstOfMonth = week.filterNotNull().firstOrNull { it.date.dayOfMonth == 1 }
            if (firstOfMonth == null) {
                assertThat(labels[index]).isNull()
            } else {
                assertThat(labels[index]).isEqualTo(RuDate.monthShort(firstOfMonth.date.monthValue))
            }
        }
    }

    /** Год — это двенадцать месяцев, и каждый называет себя ровно один раз. */
    @Test
    fun `the year names twelve months`() {
        val named = YearRhythm.monthLabels(build()).filterNotNull()
        assertThat(named).hasSize(12)
        assertThat(named.distinct()).hasSize(12)
    }

    @Test
    fun `august is named in august`() {
        val grid = build()
        val labels = YearRhythm.monthLabels(grid)
        val augustWeek = grid.weeks.indexOfFirst { week ->
            week.filterNotNull().any { it.date == LocalDate.of(2026, 8, 1) }
        }
        assertThat(labels[augustWeek]).isEqualTo("авг")
    }
}
