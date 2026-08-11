package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 19: «раз в 48 часов» — не по-человечески.
 *
 * Строка в колофоне говорила часами, потому что часами говорит база:
 * intervalHours — это ключ, от которого считается фаза закваски
 * (profileForInterval). Человек же кормит закваску не «раз в 72 часа», а раз в
 * три дня. Перевод одного в другое живёт здесь, чистой функцией, а не внутри
 * composable: список ключей обязан совпадать с тем, что понимает профиль, и
 * проверяется это тестом, а не глазами.
 */
class FeedingIntervalTest {

    @Test
    fun `the offered intervals are exactly the keys the starter profile understands`() {
        // 12/24/48/72/168 — те же, что в profileForInterval. Разойтись им нельзя:
        // колофон запишет в базу число, от которого фаза посчитается неверно.
        assertThat(FeedingInterval.HOURS).containsExactly(12, 24, 48, 72, 168).inOrder()
    }

    @Test
    fun `every interval says itself the way a person says it`() {
        assertThat(FeedingInterval.label(12)).isEqualTo("раз в 12 часов")
        assertThat(FeedingInterval.label(24)).isEqualTo("раз в сутки")
        assertThat(FeedingInterval.label(48)).isEqualTo("раз в два дня")
        assertThat(FeedingInterval.label(72)).isEqualTo("раз в три дня")
        assertThat(FeedingInterval.label(168)).isEqualTo("раз в неделю")
    }

    /** Ни одно предложенное значение не проговаривается часами, кроме самого частого. */
    @Test
    fun `no offered interval is spelled in bare hours past the first day`() {
        FeedingInterval.HOURS.filter { it >= 24 }.forEach { hours ->
            assertThat(FeedingInterval.label(hours)).doesNotContain("часов")
        }
    }

    /**
     * В базе может лежать число, которого колофон не предлагает, — например
     * оставшееся от прошлой версии. Врать про него книга не будет: скажет
     * часами, как есть.
     */
    @Test
    fun `an interval the book does not offer is still named honestly`() {
        assertThat(FeedingInterval.label(36)).isEqualTo("каждые 36 часов")
    }

    @Test
    fun `an unknown interval does not break the chosen row`() {
        // Строка настройки должна на чём-то стоять: неизвестное значение не
        // выбрано ни одним из предложенных, и подсветить книге нечего.
        assertThat(FeedingInterval.HOURS).doesNotContain(36)
    }
}
