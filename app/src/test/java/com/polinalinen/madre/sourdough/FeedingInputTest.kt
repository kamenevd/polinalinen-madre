package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 12 завёл этот файл потому, что форма кормления не проверяла ничего:
 * пустые поля читались как `toIntOrNull() ?: 0`, и «Вписать в дневник»
 * заводило запись «0 г муки, 0 г воды» — она уходила в Room, сдвигала дату
 * последнего кормления и переставляла напоминание. Отменить это в книге нечем.
 *
 * Cycle 26 отобрал у нуля последнюю дорогу: массы задаются ползунком в своих
 * пределах, а набранный руками вес проходит через те же пределы. Проверяется
 * то же самое правило, но на новом входе.
 */
class FeedingInputTest {

    @Test
    fun `the usual masses are inside their ranges`() {
        assertThat(FeedingInput.STARTER.parse("50")).isEqualTo(50)
        assertThat(FeedingInput.FLOUR.parse("100")).isEqualTo(100)
        assertThat(FeedingInput.WATER.parse("50")).isEqualTo(50)
    }

    @Test
    fun `an empty field is not a zero`() {
        assertThat(FeedingInput.STARTER.parse("")).isNull()
        assertThat(FeedingInput.FLOUR.parse("   ")).isNull()
    }

    @Test
    fun `a feeding of nothing is not a feeding`() {
        assertThat(FeedingInput.STARTER.parse("0")).isNull()
        assertThat(FeedingInput.FLOUR.parse("0")).isNull()
        assertThat(FeedingInput.WATER.parse("0")).isNull()
        // И ползунок в ноль не уезжает: у каждой массы свой нижний край.
        assertThat(FeedingInput.STARTER.down(FeedingInput.STARTER.min)).isEqualTo(5)
        assertThat(FeedingInput.FLOUR.down(FeedingInput.FLOUR.min)).isEqualTo(10)
        assertThat(FeedingInput.WATER.down(FeedingInput.WATER.min)).isEqualTo(5)
    }

    @Test
    fun `a slip of the finger on the keyboard is caught`() {
        // Четыре цифры в поле помещаются — 9999 г муки это не кормление,
        // а мешок, и почти наверняка лишний ноль.
        assertThat(FeedingInput.FLOUR.parse("9999")).isNull()
        assertThat(FeedingInput.STARTER.parse("251")).isNull()
        assertThat(FeedingInput.WATER.parse("301")).isNull()
    }

    @Test
    fun `the largest sensible feeding still goes through`() {
        assertThat(FeedingInput.STARTER.parse("250")).isEqualTo(250)
        assertThat(FeedingInput.FLOUR.parse("500")).isEqualTo(500)
        assertThat(FeedingInput.WATER.parse("300")).isEqualTo(300)
        // Прибавлять дальше края некуда.
        assertThat(FeedingInput.FLOUR.up(FeedingInput.FLOUR.max)).isEqualTo(500)
    }

    @Test
    fun `garbage never turns into grams`() {
        assertThat(FeedingInput.STARTER.parse("пятьдесят")).isNull()
        assertThat(FeedingInput.FLOUR.parse("50,5")).isNull()
        assertThat(FeedingInput.WATER.parse("-50")).isNull()
    }

    /** Точный вес с весов не обязан быть кратен пяти — банка не знает о шаге. */
    @Test
    fun `an exact kitchen weight need not be a multiple of five`() {
        assertThat(FeedingInput.STARTER.parse("47")).isEqualTo(47)
        assertThat(FeedingInput.STARTER.up(47)).isEqualTo(52)
        assertThat(FeedingInput.STARTER.down(47)).isEqualTo(42)
    }

    /** Шкала ползунка ходит ровно по пятёркам от края до края. */
    @Test
    fun `slider steps land on multiples of the step`() {
        assertThat(FeedingInput.STEP_GRAMS).isEqualTo(5)
        assertThat(FeedingInput.STARTER.sliderSteps).isEqualTo(48)
        assertThat(FeedingInput.FLOUR.sliderSteps).isEqualTo(97)
        assertThat(FeedingInput.WATER.sliderSteps).isEqualTo(58)
    }
}
