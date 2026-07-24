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
    // Cycle 2, фича «Голоса семьи» (FamilyHand): автор заметки, если известен.
    // Null пока не появился реальный UI выбора пользователя (v4 decision #13) —
    // FamilyHand.forUser в этом случае берёт детерминированный fallback по recipeId.
    val userId: Long? = null,
)
