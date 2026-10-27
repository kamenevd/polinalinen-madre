package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class DiaryJournalTest {
    private fun stamp(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()
    private fun row(id: Long, date: String) = FeedingEntity(id = id, sourdoughConfigId = 1,
        timestampMillis = stamp(date), flourGrams = id.toInt(), waterGrams = 50, storageLocation = StorageLocation.KITCHEN)

    @Test fun `Monday local boundaries not rolling seven days with Sunday and future separate`() {
        val zone = ZoneId.of("America/New_York")
        val rows = listOf(
            row(9, "2026-10-26T00:00:00-04:00"), row(8, "2026-10-25T23:59:59-04:00"),
            row(7, "2026-10-19T00:00:00-04:00"), row(6, "2026-10-18T23:59:59-04:00"),
            row(5, "2026-11-02T00:00:00-05:00"), row(4, "2025-10-26T00:00:00-04:00"))
        val before = rows.toList()
        val groups = journalWeeks(rows, stamp("2026-10-26T00:00:00-04:00"), zone)
        assertThat(groups.map { it.entries.map(FeedingEntity::id) }).containsExactly(listOf(5L), listOf(9L), listOf(8L, 7L), listOf(6L), listOf(4L)).inOrder()
        assertThat(groups[1].label).isEqualTo("Эта неделя")
        assertThat(groups[2].label).isEqualTo("Прошлая неделя")
        assertThat(groups.first().label).isEqualTo("2 нояб. 2026 — 8 нояб. 2026")
        assertThat(groups.last().label).isEqualTo("20 окт. 2025 — 26 окт. 2025")
        assertThat(rows).isEqualTo(before)
    }
    @Test fun `year crossing and daylight saving keep exact rows and local Monday`() {
        val zone = ZoneId.of("Europe/Berlin")
        val rows = listOf(row(3, "2027-01-01T12:00:00+01:00"), row(2, "2026-12-28T00:00:00+01:00"),
            row(1, "2026-12-27T23:59:59+01:00"))
        val groups = journalWeeks(rows, rows.first().timestampMillis, zone)
        assertThat(groups.map { it.start }).containsExactly(LocalDate.parse("2026-12-28"), LocalDate.parse("2026-12-21")).inOrder()
        assertThat(groups.map { it.label }).containsExactly("Эта неделя", "Прошлая неделя").inOrder()
        val dst = listOf(row(6, "2026-03-29T03:00:00+02:00"), row(5, "2026-03-23T00:00:00+01:00"))
        assertThat(journalWeeks(dst, stamp("2026-03-30T00:00:00+02:00"), zone).single().label).isEqualTo("Прошлая неделя")
        assertThat(journalWeeks(dst, dst[0].timestampMillis, zone).single().entries).containsExactlyElementsIn(dst).inOrder()
    }
    @Test fun `unlimited history retains every exact entity and insertion order inside weeks`() {
        val rows = (1L..48L).map { row(it, "2026-10-26T12:00:00+03:00").copy(notes = "  заметка $it\nбез изменений  ",
            retainedStarterGrams = if (it % 2 == 0L) 123 else null, photoPath = "feeding_photos/fixture-$it.jpg") }
        val groups = journalWeeks(rows, rows[0].timestampMillis, ZoneId.of("Europe/Moscow"))
        assertThat(groups.single().entries).containsExactlyElementsIn(rows).inOrder()
        assertThat(rows.first().id).isEqualTo(1)
        assertThat(journalWeeks(emptyList(), 0)).isEmpty()
    }
}
