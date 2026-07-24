package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Одна завершённая выпечка — источник данных для «Формуляра книги» и хитмэпа
 * на Полке (2026-07-21). Пишется один раз, когда BakingSession.isCompleted
 * становится true (см. BakingViewModel.advanceStep). Никаких вычисляемых
 * агрегатов здесь не хранится — формуляр/хитмэп/любимая глава считаются на
 * лету из этой таблицы (см. ui/screens/BookStatsScreen.kt), чтобы цифры
 * никогда не могли разойтись между собой.
 */
@Entity(tableName = "bake_records")
data class BakeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val recipeName: String,
    val portions: Int,
    val completedAtMillis: Long = System.currentTimeMillis(),
    // «Старое фото» (Cycle 6, AgedPhoto): абсолютный путь вклеенной фотокарточки
    // в internal storage (files/bake_photos). null — карточку не вклеивали.
    val photoPath: String? = null,
)
