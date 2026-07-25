package com.polinalinen.madre.model

import com.polinalinen.madre.data.remote.GuestNoteRecord
import java.net.URLEncoder
import kotlin.math.abs

/**
 * «Гостевая страница» (DESIGN-V4.md Cycle 7, фича GuestPage): после выпечки
 * хозяева показывают гостям QR — те открывают простую HTML-форму на
 * PocketBase (без установки приложения) и оставляют пару слов. Отзыв
 * вклеивается в книгу чужим рукописным почерком на гостевой странице рецепта.
 *
 * Здесь чистая сборка отзывов из записей PocketBase и адрес формы
 * (юнит-тест GuestPageTest); сеть и состояние — GuestNotesViewModel.
 */
data class GuestNote(
    val text: String,
    val authorLabel: String,
    /** Наклон почерка гостя — у каждого имени своя рука (знак и величина). */
    val slantDeg: Float,
    /** 0/1 — оттенок чернил (Espresso/Cocoa), тоже от имени. */
    val inkIndex: Int,
)

object GuestPage {

    fun from(records: List<GuestNoteRecord>): List<GuestNote> =
        records.filter { it.text.isNotBlank() }.map { record ->
            GuestNote(
                text = record.text.trim(),
                authorLabel = authorLabel(record.author),
                slantDeg = slantFor(record.author),
                inkIndex = inkFor(record.author),
            )
        }

    fun authorLabel(author: String): String {
        val name = author.trim()
        return if (name.isEmpty()) "гость книги" else "$name · гость книги"
    }

    /**
     * Рука гостя: детерминированный наклон 0.8°/1.6°/2.4°, знак — от чётности
     * хэша имени. Один и тот же гость пишет одной рукой во всех отзывах;
     * ключ — имя, потому что другого стабильного признака у гостя нет.
     */
    fun slantFor(author: String): Float {
        val h = author.trim().hashCode()
        val magnitude = 0.8f + 0.8f * abs(h).mod(3)
        return if (h % 2 == 0) magnitude else -magnitude
    }

    /** Оттенок чернил гостя: 0 — Espresso, 1 — Cocoa. */
    fun inkFor(author: String): Int = abs(author.trim().hashCode()).mod(2)

    /**
     * Адрес гостевой формы: PocketBase раздаёт pb_public с корня, поэтому
     * guest.html живёт рядом с API. Имя рецепта едет в query, чтобы форма
     * поздоровалась названием, не зная recipes.json.
     */
    fun guestUrl(baseUrl: String, recipeId: String, recipeName: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/guest.html?recipe=${encode(recipeId)}&name=${encode(recipeName)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
