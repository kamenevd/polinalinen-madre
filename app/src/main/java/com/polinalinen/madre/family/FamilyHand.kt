package com.polinalinen.madre.family

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.polinalinen.madre.ui.theme.AppColors

/**
 * DESIGN-V4.md Cycle 2, фича «Голоса семьи» (FamilyHand): у каждого автора
 * заметок на полях и записей дневника — своя рука. Три варианта хватает,
 * чтобы страница не выглядела написанной одним человеком; настоящий выбор
 * пользователя пока не подключён нигде в UI (v4 decision #13), поэтому
 * почерк определяется детерминированно — либо по userId автора, либо, если
 * его нет, по любому другому стабильному ключу записи (обычно recipeId).
 */
enum class FamilyHand {
    ESPRESSO,
    COCOA,
    CARAMEL;

    companion object {
        /**
         * userId=0/null → ESPRESSO, 1 → COCOA, 2 → CARAMEL, дальше по кругу
         * (mod, а не %, — чтобы отрицательные fallbackKey.hashCode() не ломали выбор).
         */
        fun forUser(userId: Long?, fallbackKey: Long): FamilyHand =
            when ((userId ?: fallbackKey).mod(3L)) {
                1L -> COCOA
                2L -> CARAMEL
                else -> ESPRESSO
            }
    }
}

/** Наклон/цвет/насыщенность одного почерка — применяется к Cursive-тексту. */
data class FamilyHandStyle(
    val ink: Color,
    val rotationDeg: Float,
    val fontWeight: FontWeight,
)

@Composable
fun FamilyHand.style(): FamilyHandStyle {
    val colors = AppColors.current
    return when (this) {
        FamilyHand.ESPRESSO -> FamilyHandStyle(colors.espresso, -1f, FontWeight.Normal)
        FamilyHand.COCOA -> FamilyHandStyle(colors.cocoa, -2f, FontWeight.Medium)
        FamilyHand.CARAMEL -> FamilyHandStyle(colors.caramel, -3f, FontWeight.Normal)
    }
}
