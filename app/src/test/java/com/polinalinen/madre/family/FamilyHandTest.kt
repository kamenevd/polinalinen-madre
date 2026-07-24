package com.polinalinen.madre.family

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 2, фича «Голоса семьи» (FamilyHand): почерк должен быть
 * детерминированным и стабильным между рекомпозициями — проверяем ровно то,
 * от чего это зависит: маппинг userId (или fallback-ключа) на конкретную руку.
 */
class FamilyHandTest {

    @Test
    fun `userId 0 or null maps to Espresso`() {
        assertThat(FamilyHand.forUser(userId = 0L, fallbackKey = 999L)).isEqualTo(FamilyHand.ESPRESSO)
        assertThat(FamilyHand.forUser(userId = null, fallbackKey = 0L)).isEqualTo(FamilyHand.ESPRESSO)
    }

    @Test
    fun `userId 1 maps to Cocoa`() {
        assertThat(FamilyHand.forUser(userId = 1L, fallbackKey = 0L)).isEqualTo(FamilyHand.COCOA)
    }

    @Test
    fun `userId 2 maps to Caramel`() {
        assertThat(FamilyHand.forUser(userId = 2L, fallbackKey = 0L)).isEqualTo(FamilyHand.CARAMEL)
    }

    @Test
    fun `userId wraps around every three ids`() {
        assertThat(FamilyHand.forUser(userId = 3L, fallbackKey = 0L)).isEqualTo(FamilyHand.ESPRESSO)
        assertThat(FamilyHand.forUser(userId = 4L, fallbackKey = 0L)).isEqualTo(FamilyHand.COCOA)
        assertThat(FamilyHand.forUser(userId = 5L, fallbackKey = 0L)).isEqualTo(FamilyHand.CARAMEL)
    }

    @Test
    fun `null userId falls back to the given key deterministically`() {
        assertThat(FamilyHand.forUser(userId = null, fallbackKey = 1L)).isEqualTo(FamilyHand.COCOA)
        assertThat(FamilyHand.forUser(userId = null, fallbackKey = 2L)).isEqualTo(FamilyHand.CARAMEL)
        // Отрицательный hashCode (например recipeId.hashCode()) не должен ломать выбор.
        assertThat(FamilyHand.forUser(userId = null, fallbackKey = -1L)).isEqualTo(FamilyHand.CARAMEL)
    }

    @Test
    fun `same inputs always produce the same hand`() {
        val a = FamilyHand.forUser(userId = null, fallbackKey = "recipe-42".hashCode().toLong())
        val b = FamilyHand.forUser(userId = null, fallbackKey = "recipe-42".hashCode().toLong())
        assertThat(a).isEqualTo(b)
    }
}
