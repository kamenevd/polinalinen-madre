package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Уведомления книги делят одно пространство id, и заезжать друг другу на место
 * им нельзя.
 *
 * Раньше id уведомления по ключу был просто key.hashCode() — любое целое, в том
 * числе 1001 (напоминание о кормлении) и 2000+id сессии (строка хода выпечки).
 * Совпадение редкое, но последствие у него не косметическое: «время вышло»
 * встало бы на место напоминания о кормлении, а cancelByKey снял бы строку
 * чужой, ещё идущей выпечки.
 */
class MadreNotifierIdTest {

    private val keys: List<String> = buildList {
        (1L..200L).forEach { session ->
            (0..12).forEach { step ->
                add(BakingNotificationPlanner.stepDoneKey(session, step))
                add(BakingNotificationPlanner.butterPrepKey(session, step))
            }
        }
    }

    @Test
    fun `a keyed notification never lands on the feeding reminder`() {
        assertThat(keys.map { MadreNotifier.notificationId(it) })
            .doesNotContain(MadreNotifier.ID_FEEDING)
    }

    @Test
    fun `a keyed notification never lands on a running bake's progress line`() {
        val progressIds = (0L..500L).map { BakingProgress.notificationId(it) }.toSet()
        val keyedIds = keys.map { MadreNotifier.notificationId(it) }.toSet()
        assertThat(keyedIds.intersect(progressIds)).isEmpty()
    }

    @Test
    fun `the same key always means the same notification`() {
        val key = BakingNotificationPlanner.stepDoneKey(sessionId = 3L, stepIndex = 2)
        assertThat(MadreNotifier.notificationId(key)).isEqualTo(MadreNotifier.notificationId(key))
        // Показать и снять — по одному и тому же id, иначе снимать нечего.
        assertThat(MadreNotifier.notificationId(key))
            .isNotEqualTo(MadreNotifier.notificationId(BakingNotificationPlanner.butterPrepKey(3L, 2)))
    }

    @Test
    fun `different steps of one bake keep different notifications`() {
        val ids = (0..12).map { MadreNotifier.notificationId(BakingNotificationPlanner.stepDoneKey(1L, it)) }
        assertThat(ids).containsNoDuplicates()
    }

    /** Даже у самого злого ключа id остаётся положительным и в своей полке. */
    @Test
    fun `an id stays inside its own shelf, whatever the key hashes to`() {
        val cruel = listOf("", "step-done-0-0", "😀", "a".repeat(1000), "polygenelubricants")
        (cruel + keys).forEach { key ->
            val id = MadreNotifier.notificationId(key)
            assertThat(id).isAtLeast(MadreNotifier.ID_KEYED_BASE)
            assertThat(id).isLessThan(MadreNotifier.ID_KEYED_BASE + MadreNotifier.ID_KEYED_RANGE)
        }
    }
}
