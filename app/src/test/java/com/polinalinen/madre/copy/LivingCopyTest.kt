package com.polinalinen.madre.copy

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.account.NetworkFailure
import com.polinalinen.madre.notifications.FeedingReminderAction
import com.polinalinen.madre.sourdough.FeedingMassCopy
import com.polinalinen.madre.ui.screens.HomeCopy
import org.junit.Test

/**
 * Cycle 27: изменённые подписи говорят нейтрально. Женский род остаётся
 * у закваски от первого лица, а не у обращения к читателю.
 */
class LivingCopyTest {

    private val changedLabels = listOf(
        FeedingReminderAction.LABEL,
        FeedingMassCopy.STARTER,
        FeedingMassCopy.FLOUR,
        FeedingMassCopy.WATER,
        HomeCopy.BAKERY,
        NetworkFailure.OFFLINE.message,
    )

    @Test
    fun `changed labels drop feminine-only address`() {
        assertThat(FeedingReminderAction.LABEL).isEqualTo("Покормить")
        assertThat(FeedingMassCopy.STARTER).isEqualTo("Оставить закваски")
        assertThat(FeedingMassCopy.FLOUR).isEqualTo("Добавить муки")
        assertThat(FeedingMassCopy.WATER).isEqualTo("Добавить воды")
        assertThat(HomeCopy.BAKERY).isEqualTo("Домашняя пекарня")
        assertThat(HomeCopy.BAKERY).doesNotContain("Полины")

        val banned = listOf("хозяйка", "покормила", "написала", "оставила", "дала муки", "дала воды")
        changedLabels.forEach { label ->
            val lower = label.lowercase()
            banned.forEach { word ->
                assertThat(lower).doesNotContain(word)
            }
        }
    }

    @Test
    fun `offline copy never says the forbidden family phrase`() {
        assertThat(NetworkFailure.OFFLINE.message.lowercase()).doesNotContain("общая книга")
        assertThat(NetworkFailure.OFFLINE.message).contains("полка")
    }
}
