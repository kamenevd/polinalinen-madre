package com.polinalinen.madre.model

import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Одна фотокарточка главы. Несёт ровно то, что нужно плитке и viewer'у, —
 * путь, время съёмки и название главы для подписи; ни Bitmap, ни Context.
 */
data class ChapterPhoto(
    val recordId: Long,
    val path: String,
    val takenAtMillis: Long,
    val recipeName: String,
)

/**
 * Cycle 14: у главы с выпечкой на полке появляется лицо.
 *
 * Плитка берёт ПОСЛЕДНЕЕ доступное фото своей главы, а fullscreen-viewer
 * листает ровно её снимки и ничьи больше. Отбор живёт здесь, а не в композиции:
 * решать «какое фото показать» по ходу отрисовки — самый простой способ
 * незаметно показать чужое или уже удалённое.
 *
 * Декодированием занимается Coil в момент показа: список — это пути, и
 * галерея целиком в память не поднимается.
 */
object ChapterPhotos {

    /** Снимки главы, свежие первыми. Записи без карточки сюда не попадают. */
    fun of(records: List<BakeRecordEntity>, recipeId: String): List<ChapterPhoto> =
        records.asSequence()
            .filter { it.recipeId == recipeId }
            .filter { !it.photoPath.isNullOrBlank() }
            .sortedByDescending { it.completedAtMillis }
            .map {
                ChapterPhoto(
                    recordId = it.id,
                    path = it.photoPath!!,
                    takenAtMillis = it.completedAtMillis,
                    recipeName = it.recipeName,
                )
            }
            .toList()

    /**
     * Лицо главы — последний снимок. null означает честное «фотографии нет»:
     * главу либо не пекли, либо пекли, но не снимали. Плитка в этом случае
     * показывает подпись, а не пустую рамку.
     */
    fun cover(records: List<BakeRecordEntity>, recipeId: String): ChapterPhoto? =
        of(records, recipeId).firstOrNull()

    /** Viewer открывается на последнем снимке — он же первый в списке. */
    fun startIndex(size: Int): Int = 0

    /** Границы главы держат: за края её снимков листание не уходит. */
    fun clampIndex(index: Int, size: Int): Int =
        if (size <= 0) 0 else index.coerceIn(0, size - 1)

    /** Подпись снимка: «Бородинский, 3 августа» — глава и день выпечки. */
    fun caption(photo: ChapterPhoto, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(photo.takenAtMillis).atZone(zone).toLocalDate()
        return "${photo.recipeName}, ${RuDate.dayAndMonth(date)}"
    }

    /** «3 из 7». Единственный снимок считать вслух незачем. */
    fun counterText(index: Int, size: Int): String =
        if (size <= 1) "" else "${clampIndex(index, size) + 1} из $size"
}
