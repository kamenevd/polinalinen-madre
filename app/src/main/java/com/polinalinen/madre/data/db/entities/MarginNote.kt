package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Помета на полях рецепта — семейная рукописная заметка, привязанная к
 * конкретному recipeId. DESIGN-V4.md Cycle 1, фича «Пометы на полях».
 */
@Entity(tableName = "margin_notes")
data class MarginNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
)
