package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cycle 11: фича «Конверт на будущее» (TimeCapsule) из приложения убрана, но
 * таблица остаётся — иначе Room не откроет уже сохранённую на телефоне
 * madre.db (identity hash схемы обязан совпадать). Это чистое описание
 * существующей таблицы: ни DAO, ни репозитория, ни экрана у неё больше нет.
 */
@Entity(tableName = "sealed_notes")
data class SealedNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val text: String,
    val unlockAfterBakes: Int,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val unlockedAtMillis: Long? = null,
)
