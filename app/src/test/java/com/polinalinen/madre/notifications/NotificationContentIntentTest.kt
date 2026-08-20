package com.polinalinen.madre.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MainActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cycle 20: у уведомления есть куда вести.
 *
 * До этого цикла «время вышло» и «достаньте масло» никуда не вели: тап по ним
 * не делал ничего вовсе — ни книги, ни выпечки, ни даже главной страницы. Так
 * же молчало и напоминание о кормлении: дорога в форму была только через
 * кнопку «Покормить», а сам текст был мёртвой строкой. Уведомление, зовущее
 * что-то сделать и не открывающее места, где это делают, — та же нечестная
 * кнопка, только в шторке (hard rule №8).
 *
 * Проверяется по самому намерению, а не по «интент не null»: уведомление,
 * открывающее просто главную, выглядело бы точно так же.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NotificationContentIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = MadreNotifier(context)
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantPostNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun shown(): Notification = shadowOf(manager).allNotifications.single()

    private fun destinationOf(notification: Notification) =
        shadowOf(notification.contentIntent).savedIntent

    @Test
    fun `the step-is-over notification opens that very bake`() {
        notifier.postBakingNotification(
            sessionId = 7L,
            key = BakingNotificationPlanner.stepDoneKey(7L, 2),
            title = "«Бородинский» — время вышло",
            text = "Расстойка: шаг закончен, можно продолжать.",
        )
        val destination = destinationOf(shown())
        assertThat(destination.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(destination.getLongExtra(MainActivity.EXTRA_SESSION_ID, -1L)).isEqualTo(7L)
    }

    /** Две выпечки — две дороги: строка одной не должна открывать другую. */
    @Test
    fun `two bakes lead to two different pages, not both to the first one`() {
        assertThat(BakeOpenIntent.requestCode(7L)).isNotEqualTo(BakeOpenIntent.requestCode(8L))
        val seven = BakeOpenIntent.intent(context, 7L)
        val eight = BakeOpenIntent.intent(context, 8L)
        assertThat(seven.getLongExtra(MainActivity.EXTRA_SESSION_ID, -1L)).isEqualTo(7L)
        assertThat(eight.getLongExtra(MainActivity.EXTRA_SESSION_ID, -1L)).isEqualTo(8L)
    }

    /** Пока выпечка неизвестна (первые доли секунды сервиса) — просто книга. */
    @Test
    fun `an intent without a bake still opens the book, with no phantom session`() {
        val intent = BakeOpenIntent.intent(context, null)
        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.hasExtra(MainActivity.EXTRA_SESSION_ID)).isFalse()
    }

    @Test
    fun `the feeding reminder opens the feeding form, not just the book`() {
        notifier.postFeedingReminder("Мадре: пора кормить", "прошло 12 часов")
        val destination = destinationOf(shown())
        assertThat(destination.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(destination.getBooleanExtra(MainActivity.EXTRA_OPEN_FEEDING, false)).isTrue()
    }

    /** Тап уносит уведомление из шторки — иначе оно остаётся звать уже сделанное. */
    @Test
    fun `both notifications go away when they are tapped`() {
        notifier.postFeedingReminder("Мадре: пора кормить", "прошло 12 часов")
        assertThat(shown().flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        manager.cancelAll()

        notifier.postBakingNotification(
            sessionId = 1L,
            key = BakingNotificationPlanner.stepDoneKey(1L, 0),
            title = "«Бородинский» — время вышло",
            text = "Расстойка: шаг закончен.",
        )
        assertThat(shown().flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
    }

    /**
     * Записанное кормление снимает своё напоминание. Иначе шторка продолжает
     * звать кормить закваску, которую только что покормили, — и человек идёт
     * проверять, записалось ли.
     */
    @Test
    fun `writing the feeding down takes the reminder off the shade`() {
        notifier.postFeedingReminder("Мадре: пора кормить", "прошло 12 часов")
        assertThat(shadowOf(manager).allNotifications).hasSize(1)

        notifier.cancelFeedingReminder()
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }
}
