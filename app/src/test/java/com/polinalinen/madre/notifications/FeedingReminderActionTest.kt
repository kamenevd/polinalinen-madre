package com.polinalinen.madre.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cycle 18: напоминание о кормлении несёт кнопку «Покормила».
 *
 * До этого шторка только звала: чтобы записать кормление, надо было открыть
 * книгу, дойти до дневника и оттуда до формы. Кнопка ведёт прямо в форму
 * кормления — а не записывает кормление молча. Молча его записать нечем:
 * сколько муки и воды пошло, знает человек, и придумывать за него граммы
 * книга не станет (граммы — это запись в дневнике, а не догадка).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FeedingReminderActionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun postReminder(): android.app.Notification {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        MadreNotifier(context).postFeedingReminder(
            title = "Мадре: пора кормить",
            text = "Пора кормить — мука и вода по вашему обычному соотношению.",
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        return shadowOf(manager).allNotifications.single()
    }

    @Test
    fun `the reminder offers to record the feeding`() {
        val notification = postReminder()
        val labels = notification.actions.orEmpty().map { it.title.toString() }
        assertThat(labels).containsExactly(FeedingReminderAction.LABEL)
        assertThat(FeedingReminderAction.LABEL).isEqualTo("Покормила")
    }

    /**
     * Кнопка ведёт в книгу, на форму кормления, — и это проверяется по самому
     * намерению, а не по тому, что кнопка есть. Кнопка, открывающая просто
     * главную, выглядела бы точно так же.
     */
    @Test
    fun `the button opens the feeding form in the book`() {
        val intent = FeedingReminderAction.openFeedingForm(context)
        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.getBooleanExtra(MainActivity.EXTRA_OPEN_FEEDING, false)).isTrue()
    }

    /**
     * Уведомление снимается само, когда по кнопке ушли в книгу: иначе в
     * шторке остаётся висеть просьба покормить, которую уже выполняют.
     */
    @Test
    fun `the reminder leaves the shade once it is answered`() {
        assertThat(postReminder().flags and android.app.Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
    }
}
