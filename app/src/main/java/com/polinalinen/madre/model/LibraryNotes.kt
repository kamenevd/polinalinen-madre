package com.polinalinen.madre.model

import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.MarginNoteSyncRecord

/**
 * «Библиотечная книга» (DESIGN-V4.md Cycle 6, фича LibraryNotes): на полях
 * рецепта проступают заметки других семей — бледные, чужим почерком, с
 * подписью «семья N, X выпечек». Чистая сборка из записей PocketBase —
 * сеть и состояние живут в LibraryNotesViewModel (как CommunityStats).
 */
data class LibraryNote(
    val text: String,
    val familyLabel: String,
    /** Наклон чужого почерка — положительный, в противоход рук своей семьи (FamilyHand). */
    val slantDeg: Float,
)

object LibraryNotes {

    /**
     * [notes] — margin_notes_sync ДРУГИХ семей по этому рецепту, [bakeStats] —
     * bake_stats других семей целиком (для счётчика выпечек в подписи).
     * Номер семьи — стабильный: место device_id в отсортированном списке всех
     * чужих устройств, известных хоть по заметке, хоть по выпечке, — от
     * запроса к запросу подпись не меняется.
     */
    fun from(notes: List<MarginNoteSyncRecord>, bakeStats: List<BakeStatRecord>): List<LibraryNote> {
        if (notes.isEmpty()) return emptyList()
        val familyNumbers = (notes.map { it.deviceId } + bakeStats.map { it.deviceId })
            .distinct()
            .sorted()
            .withIndex()
            .associate { (i, deviceId) -> deviceId to i + 1 }
        val bakesByDevice = bakeStats.groupingBy { it.deviceId }.eachCount()
        return notes.map { note ->
            val family = familyNumbers.getValue(note.deviceId)
            val bakes = bakesByDevice[note.deviceId] ?: 0
            LibraryNote(
                text = note.text,
                familyLabel = "семья $family, $bakes ${bakeWord(bakes)}",
                slantDeg = slantFor(note.deviceId),
            )
        }
    }

    /**
     * Чужая рука — детерминированный лёгкий наклон 0.8°/1.5°/2.2° от device_id.
     * Знак положительный: свои руки (FamilyHand) клонятся влево, чужие — вправо.
     */
    fun slantFor(deviceId: String): Float =
        0.8f + 0.7f * deviceId.hashCode().mod(3)

    fun bakeWord(n: Int): String = when {
        n % 100 in 11..14 -> "выпечек"
        n % 10 == 1 -> "выпечка"
        n % 10 in 2..4 -> "выпечки"
        else -> "выпечек"
    }
}
