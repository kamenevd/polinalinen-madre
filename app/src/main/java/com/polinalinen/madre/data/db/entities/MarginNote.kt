package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cycle 11: фича «Пометы на полях» из приложения убрана, но таблица остаётся —
 * иначе Room не откроет уже сохранённую на телефоне madre.db (identity hash
 * схемы обязан совпадать). Это чистое описание существующей таблицы: ни DAO,
 * ни репозитория, ни экрана у неё больше нет.
 */
@Entity(tableName = "margin_notes")
data class MarginNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val userId: Long? = null,
)
