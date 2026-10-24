package com.polinalinen.madre.notifications

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real Android notification payload and public version, not a synthetic server/Station acknowledgement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class BakingNotificationPrivacyTest {
    @Test fun privateProgressHasOnlyGenericLockScreenTextAndNoTimerOrAction() {
        val context = ApplicationProvider.getApplicationContext<MadreApplication>()
        val notification = NotificationCompat.Builder(context, "synthetic-test-channel")
            .setContentTitle("Синтетическая выпечка · текущий шаг")
            .setContentText("Осталось 41:12")
            .bakingLockScreenPrivacy(context, "synthetic-test-channel", false).build()
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Синтетическая выпечка · текущий шаг", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        val public = requireNotNull(notification.publicVersion)
        assertEquals("Мадре", public.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Выпечка идёт", public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(public.actions.isNullOrEmpty())
        assertNull(public.bigContentView)
    }
    @Test fun publicProgressRequiresTheExplicitEnabledSetting() {
        val context = ApplicationProvider.getApplicationContext<MadreApplication>()
        val notification = NotificationCompat.Builder(context, "synthetic-test-channel")
            .setContentTitle("Синтетическая выпечка")
            .bakingLockScreenPrivacy(context, "synthetic-test-channel", true).build()
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
    }
}
