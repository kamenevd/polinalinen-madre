package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 12: форма кормления не проверяла ничего. Пустые поля читались как
 * `toIntOrNull() ?: 0`, и «Вписать в дневник» заводило запись «0 г муки,
 * 0 г воды» — она уходила в Room, сдвигала дату последнего кормления, меняла
 * фазу закваски и переставляла напоминание. Отменить это в книге нечем.
 */
class FeedingInputTest {

    @Test
    fun `the usual fifty on fifty is fine`() {
        val v = FeedingInput.validate("50", "50")
        assertThat(v.canSave).isTrue()
        assertThat(v.flourGrams).isEqualTo(50)
        assertThat(v.waterGrams).isEqualTo(50)
        assertThat(v.message).isNull()
    }

    @Test
    fun `an empty field is not a zero`() {
        val v = FeedingInput.validate("", "50")
        assertThat(v.canSave).isFalse()
        assertThat(v.message).isNotEmpty()
    }

    @Test
    fun `a feeding of nothing is not a feeding`() {
        assertThat(FeedingInput.validate("0", "50").canSave).isFalse()
        assertThat(FeedingInput.validate("50", "0").canSave).isFalse()
        assertThat(FeedingInput.validate("0", "0").canSave).isFalse()
    }

    @Test
    fun `a slip of the finger on the keyboard is caught`() {
        // Четыре цифры в поле помещаются — 9999 г муки это не кормление,
        // а мешок, и почти наверняка лишний ноль.
        val v = FeedingInput.validate("9999", "50")
        assertThat(v.canSave).isFalse()
        assertThat(v.message).contains("${FeedingInput.MAX_GRAMS}")
    }

    @Test
    fun `the largest sensible feeding still goes through`() {
        assertThat(FeedingInput.validate("${FeedingInput.MAX_GRAMS}", "${FeedingInput.MAX_GRAMS}").canSave)
            .isTrue()
    }

    @Test
    fun `garbage never turns into grams`() {
        val v = FeedingInput.validate("пятьдесят", "50")
        assertThat(v.canSave).isFalse()
        assertThat(v.flourGrams).isNull()
    }
}
