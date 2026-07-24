package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запечатанная записка на будущее — DESIGN-V4.md Cycle 2, фича «Конверт на
 * будущее» (TimeCapsule). Пишется один раз при запечатывании, читается как
 * закрытый конверт до тех пор, пока bakeCount этого рецепта не догонит
 * unlockAfterBakes; unlockedAtMillis проставляется в момент, когда семья
 * реально вскрывает конверт (не раньше, чтобы дата открытия была настоящей).
 */
@Entity(tableName = "sealed_notes")
data class SealedNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val text: String,
    val unlockAfterBakes: Int,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val unlockedAtMillis: Long? = null,
) {
    /** Готов к вскрытию, но ещё не вскрыт — конверт можно открыть. */
    fun isUnlockable(bakeCount: Int): Boolean = unlockedAtMillis == null && bakeCount >= unlockAfterBakes
}
