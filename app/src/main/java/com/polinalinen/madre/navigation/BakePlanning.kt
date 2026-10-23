package com.polinalinen.madre.navigation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Ready-time presets use real elapsed minutes, including across a timezone/DST boundary. */
data class BakeReadyOption(val label: String, val readyAtMillis: Long, val startAtMillis: Long, val available: Boolean)

object BakePlanning {
    fun options(totalMinutes: Int, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<BakeReadyOption> {
        require(totalMinutes >= 0)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        fun option(label: String, day: LocalDate, hour: Int): BakeReadyOption {
            val ready = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            val start = Math.subtractExact(ready, Math.multiplyExact(totalMinutes.toLong(), 60_000L))
            return BakeReadyOption(label, ready, start, start >= nowMillis)
        }
        return listOf(
            option("К ужину · 19:00", today, 19),
            option("К завтраку · 8:00", today.plusDays(1), 8),
            option("К обеду · 13:00", today.plusDays(1), 13),
            option("Завтра к ужину", today.plusDays(1), 19),
        )
    }
    fun reminderAt(plan: PlannedBake, nowMillis: Long): Long =
        (plan.startAtMillis - 10 * 60_000L).coerceAtLeast(nowMillis)
}
