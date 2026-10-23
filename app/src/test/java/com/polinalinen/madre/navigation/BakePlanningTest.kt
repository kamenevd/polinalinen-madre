package com.polinalinen.madre.navigation

import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BakePlanningTest {
    private fun stamp(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()
    @Test fun realDurationIsSubtractedFromReadinessAndUnreachableTargetsStayDisabled() {
        val now=stamp("2026-10-23T18:00:00+03:00[Europe/Moscow]")
        val options=BakePlanning.options(90,now,ZoneId.of("Europe/Moscow"))
        assertEquals(stamp("2026-10-23T17:30:00+03:00[Europe/Moscow]"),options[0].startAtMillis)
        assertFalse(options[0].available)
        assertTrue(options[1].available)
        assertEquals(stamp("2026-10-24T06:30:00+03:00[Europe/Moscow]"),options[1].startAtMillis)
        assertEquals(stamp("2026-10-24T13:00:00+03:00[Europe/Moscow]"),options[2].readyAtMillis)
    }
    @Test fun autumnClockChangeDoesNotTurnAnEightHourRecipeIntoNineHours() {
        val now=stamp("2026-10-31T20:00:00-04:00[America/New_York]")
        val breakfast=BakePlanning.options(480,now,ZoneId.of("America/New_York"))[1]
        assertEquals(stamp("2026-11-01T01:00:00-04:00[America/New_York]"),breakfast.startAtMillis)
        assertEquals(stamp("2026-11-01T08:00:00-05:00[America/New_York]"),breakfast.readyAtMillis)
        assertTrue(breakfast.available)
    }
    @Test fun reminderUsesTenMinutesAndAnAlreadyDueReminderIsImmediate() {
        val now=stamp("2026-10-23T18:00:00+03:00[Europe/Moscow]")
        val plan=PlannedBake("synthetic",2,stamp("2026-10-23T19:00:00+03:00[Europe/Moscow]"))
        assertEquals(stamp("2026-10-23T18:50:00+03:00[Europe/Moscow]"),BakePlanning.reminderAt(plan,now))
        assertEquals(plan.startAtMillis,BakePlanning.reminderAt(plan,plan.startAtMillis))
    }
}
