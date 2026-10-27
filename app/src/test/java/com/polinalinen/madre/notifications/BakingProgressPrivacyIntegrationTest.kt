package com.polinalinen.madre.notifications

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Room setting -> Application flow -> actual service -> Android notification, no live peer/device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BakingProgressPrivacyIntegrationTest {
    @Test fun privacyChangesRebuildAnUnchangedPausedNotification() {
        val app = ApplicationProvider.getApplicationContext<MadreApplication>()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val bake = BakingProgress(sessionId = 7, recipeName = "Синтетическая выпечка", stepTitle = "Пауза",
            stepIndex = 1, stepCount = 3, remainingSeconds = 2400, stepTotalSeconds = 3600, isPaused = true)
        app.activeBakes.publish(listOf(bake))
        val service = Robolectric.buildService(BakingProgressService::class.java).create()
        val manager = app.getSystemService(NotificationManager::class.java)
        fun current(): Notification? = manager.activeNotifications.firstOrNull {
            it.id == BakingProgress.notificationId(7)
        }?.notification
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue("Actual service notification did not reach the requested privacy state", condition())
        }
        try {
            service.startCommand(0, 1)
            await { current()?.visibility == Notification.VISIBILITY_PRIVATE }
            runBlocking { app.familySettingsRepository.setLockScreenProgress(true) }
            await { current()?.visibility == Notification.VISIBILITY_PUBLIC }
            assertEquals(listOf(bake), app.activeBakes.progress.value)
            runBlocking { app.familySettingsRepository.setLockScreenProgress(false) }
            await { current()?.visibility == Notification.VISIBILITY_PRIVATE }
            assertEquals("Выпечка идёт", current()!!.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(listOf(bake), app.activeBakes.progress.value)
        } finally { service.destroy(); app.activeBakes.clear() }
    }
}
