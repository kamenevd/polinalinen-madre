package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.MarginNoteSyncRecord
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 6, фича «Библиотечная книга» (LibraryNotes): чужие заметки
 * на полях получают подпись «семья N, X выпечек». Проверяем стабильную
 * нумерацию семей, счётчик выпечек, склонения и детерминированный наклон
 * чужого почерка.
 */
class LibraryNotesTest {

    private fun note(deviceId: String, text: String = "проверено") =
        MarginNoteSyncRecord(deviceId = deviceId, recipeId = "r1", text = text, writtenAt = "2026-07-20 10:00:00Z")

    private fun bake(deviceId: String) =
        BakeStatRecord(deviceId = deviceId, recipeId = "r1", recipeName = "Хлеб", portions = 1, bakedAt = "2026-07-20 10:00:00Z")

    @Test
    fun `empty notes give an empty margin — the page stays silent`() {
        assertThat(LibraryNotes.from(emptyList(), listOf(bake("a")))).isEmpty()
    }

    @Test
    fun `family numbers are stable regardless of note order`() {
        val bakes = listOf(bake("bbb"), bake("aaa"))
        val forward = LibraryNotes.from(listOf(note("aaa"), note("bbb")), bakes)
        val backward = LibraryNotes.from(listOf(note("bbb"), note("aaa")), bakes)
        assertThat(forward.first { it.text == "проверено" && it.familyLabel.startsWith("семья 1") }).isNotNull()
        assertThat(forward.map { it.familyLabel }.toSet())
            .isEqualTo(backward.map { it.familyLabel }.toSet())
        // aaa < bbb алфавитно → aaa всегда «семья 1», bbb всегда «семья 2».
        assertThat(forward[0].familyLabel).startsWith("семья 1")
        assertThat(backward[0].familyLabel).startsWith("семья 2")
    }

    @Test
    fun `bake count in the signature comes from that family's bake stats`() {
        val notes = listOf(note("dev-a"))
        val bakes = listOf(bake("dev-a"), bake("dev-a"), bake("dev-a"), bake("dev-b"))
        val result = LibraryNotes.from(notes, bakes)
        assertThat(result).hasSize(1)
        assertThat(result[0].familyLabel).endsWith("3 выпечки")
    }

    @Test
    fun `family known only by its note still gets a number and zero bakes`() {
        val result = LibraryNotes.from(listOf(note("quiet-family")), emptyList())
        assertThat(result[0].familyLabel).isEqualTo("семья 1, 0 выпечек")
    }

    @Test
    fun `bake word declension follows russian rules`() {
        assertThat(LibraryNotes.bakeWord(1)).isEqualTo("выпечка")
        assertThat(LibraryNotes.bakeWord(2)).isEqualTo("выпечки")
        assertThat(LibraryNotes.bakeWord(5)).isEqualTo("выпечек")
        assertThat(LibraryNotes.bakeWord(11)).isEqualTo("выпечек")
        assertThat(LibraryNotes.bakeWord(21)).isEqualTo("выпечка")
        assertThat(LibraryNotes.bakeWord(0)).isEqualTo("выпечек")
    }

    @Test
    fun `foreign hand slant is deterministic and leans the opposite way`() {
        val slant = LibraryNotes.slantFor("dev-a")
        assertThat(slant).isEqualTo(LibraryNotes.slantFor("dev-a"))
        // Свои руки (FamilyHand) — от -1° до -3°; чужие всегда вправо.
        listOf("dev-a", "dev-b", "dev-c", "какая-то семья").forEach {
            assertThat(LibraryNotes.slantFor(it)).isGreaterThan(0f)
            assertThat(LibraryNotes.slantFor(it)).isAtMost(2.2f)
        }
    }
}
