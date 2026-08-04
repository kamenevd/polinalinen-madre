package com.polinalinen.madre.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Уведомления книги делят одно пространство имён, и заезжать друг другу на
 * место им нельзя.
 *
 * Раньше id уведомления по ключу считался из самого ключа — сначала голым
 * key.hashCode(), потом floorMod по миллиону. Оба раза это отображение
 * бесконечного множества ключей в конечное: совпадения в нём есть ПО
 * ПОСТРОЕНИЮ, а не по невезению. «step-done-1408-0» и «step-done-1605-10» —
 * настоящие ключи этой книги — попадали в один слот 352312, и «время вышло»
 * одного шага молча вставало на место другого, а cancelByKey снимал чужое.
 *
 * Теперь ключ уходит в систему как есть — тегом, — и проверяется это не
 * арифметикой, а настоящим NotificationManager: что в шторке лежит после
 * показа и после снятия.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MadreNotifierKeyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)
    private val notifier = MadreNotifier(context)

    /** Без гранта [MadreNotifier.post] честно молчит — здесь он есть. */
    @Before
    fun grantPostNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun shown(): List<Pair<String?, Int>> =
        manager.activeNotifications.map { it.tag to it.id }

    /** Та самая пара ключей, что делила один id до этой правки. */
    private val collidingA = BakingNotificationPlanner.stepDoneKey(sessionId = 1408L, stepIndex = 0)
    private val collidingB = BakingNotificationPlanner.stepDoneKey(sessionId = 1605L, stepIndex = 10)

    @Test
    fun `two keys that used to share an id now keep two notifications`() {
        notifier.postBakingNotification(collidingA, "Первая", "шаг закончен")
        notifier.postBakingNotification(collidingB, "Вторая", "шаг закончен")

        assertThat(shown()).containsExactly(
            collidingA to MadreNotifier.ID_KEYED,
            collidingB to MadreNotifier.ID_KEYED,
        )
    }

    @Test
    fun `cancelling one of the two colliding keys leaves the other alone`() {
        notifier.postBakingNotification(collidingA, "Первая", "шаг закончен")
        notifier.postBakingNotification(collidingB, "Вторая", "шаг закончен")

        notifier.cancelByKey(collidingA)

        assertThat(shown()).containsExactly(collidingB to MadreNotifier.ID_KEYED)
    }

    /** Ключ — это тег, и никакого преобразования по дороге нет. */
    @Test
    fun `a keyed notification is tagged with the key itself`() {
        val key = BakingNotificationPlanner.butterPrepKey(sessionId = 3L, stepIndex = 2)
        notifier.postBakingNotification(key, "Достаньте масло", "через полчаса")

        assertThat(shown()).containsExactly(key to MadreNotifier.ID_KEYED)
    }

    /** Повтор того же ключа заменяет своё уведомление, а не плодит второе. */
    @Test
    fun `the same key replaces its own notification`() {
        val key = BakingNotificationPlanner.stepDoneKey(sessionId = 1L, stepIndex = 4)
        notifier.postBakingNotification(key, "Первый показ", "шаг закончен")
        notifier.postBakingNotification(key, "Второй показ", "шаг закончен")

        assertThat(shown()).containsExactly(key to MadreNotifier.ID_KEYED)
    }

    /** Каждый шаг каждой выпечки — своё уведомление, ни одно не потерялось. */
    @Test
    fun `every step of every bake keeps its own notification`() {
        val keys = buildList {
            (1L..50L).forEach { session ->
                (0..12).forEach { step ->
                    add(BakingNotificationPlanner.stepDoneKey(session, step))
                    add(BakingNotificationPlanner.butterPrepKey(session, step))
                }
            }
        }
        keys.forEach { notifier.postBakingNotification(it, "Заголовок", "текст") }

        assertThat(manager.activeNotifications.map { it.tag }).containsExactlyElementsIn(keys)
    }

    @Test
    fun `a keyed notification never touches the feeding reminder`() {
        notifier.postFeedingReminder("Покормите закваску", "прошло 12 часов")
        val key = BakingNotificationPlanner.stepDoneKey(sessionId = 7L, stepIndex = 1)

        notifier.postBakingNotification(key, "время вышло", "шаг закончен")
        notifier.cancelByKey(key)

        assertThat(shown()).containsExactly(null to MadreNotifier.ID_FEEDING)
    }

    /**
     * Строку хода идущей выпечки рисует сервис — без тега, своим id. Снять её
     * уведомлением по ключу нельзя, даже если числа окажутся рядом.
     */
    @Test
    fun `a keyed notification never touches a running bake's progress line`() {
        val progressId = BakingProgress.notificationId(sessionId = 1000L)
        notifier.notifySafely(progressId, progressNotification())
        val key = BakingNotificationPlanner.stepDoneKey(sessionId = 1000L, stepIndex = 0)

        notifier.postBakingNotification(key, "время вышло", "шаг закончен")
        notifier.cancelByKey(key)

        assertThat(shown()).containsExactly(null to progressId)
    }

    /** Ключ бывает каким угодно — тегом он остаётся собой. */
    @Test
    fun `odd keys are still told apart`() {
        val odd = listOf("", "😀", "a".repeat(1000), "Aa", "BB", "step-done-0-0")
        odd.forEach { notifier.postBakingNotification(it, "Заголовок", "текст") }

        assertThat(manager.activeNotifications.map { it.tag }).containsExactlyElementsIn(odd)
    }

    private fun progressNotification() =
        androidx.core.app.NotificationCompat.Builder(context, MadreNotifier.CHANNEL_BAKING)
            .setSmallIcon(com.polinalinen.madre.R.drawable.ic_notification_bread)
            .setContentTitle("Хлебушек домашний")
            .build()
}
