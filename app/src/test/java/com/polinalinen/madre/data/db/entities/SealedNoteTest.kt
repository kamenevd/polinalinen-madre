package com.polinalinen.madre.data.db.entities

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 2, фича «Конверт на будущее» (TimeCapsule): конверт
 * можно открыть ровно когда bakeCount догнал unlockAfterBakes и записка ещё
 * не была вскрыта — проверяем оба условия и их границу.
 */
class SealedNoteTest {

    private fun note(unlockAfterBakes: Int, unlockedAtMillis: Long? = null) = SealedNoteEntity(
        recipeId = "recipe-1",
        text = "секрет для будущего пекаря",
        unlockAfterBakes = unlockAfterBakes,
        unlockedAtMillis = unlockedAtMillis,
    )

    @Test
    fun `not unlockable while bakeCount is below the threshold`() {
        assertThat(note(unlockAfterBakes = 3).isUnlockable(bakeCount = 2)).isFalse()
    }

    @Test
    fun `unlockable exactly at the threshold`() {
        assertThat(note(unlockAfterBakes = 3).isUnlockable(bakeCount = 3)).isTrue()
    }

    @Test
    fun `stays unlockable past the threshold`() {
        assertThat(note(unlockAfterBakes = 3).isUnlockable(bakeCount = 10)).isTrue()
    }

    @Test
    fun `already unlocked notes are never unlockable again`() {
        assertThat(note(unlockAfterBakes = 3, unlockedAtMillis = 123L).isUnlockable(bakeCount = 99)).isFalse()
    }
}
