package com.polinalinen.madre.ui.kit

import android.content.Context
import android.media.AudioAttributes
import android.os.*
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,31,35])
class PaperHapticDirectTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun motor()=if(Build.VERSION.SDK_INT>=31)context.getSystemService(VibratorManager::class.java)!!.defaultVibrator
        else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private class AcceptedView(context:Context):View(context) {
        var calls=0
        override fun performHapticFeedback(kind:Int,flags:Int):Boolean{calls++;return true}
    }
    @Test fun touchOffAndTrueViewStillSendExactlyOneReal25msRequest() {
        Settings.System.putInt(context.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,0)
        val motor=motor();val shadow=Shadows.shadowOf(motor);shadow.setHasVibrator(true);shadow.setHasAmplitudeControl(false)
        val view=AcceptedView(context);val effects=mutableListOf<VibrationEffect>()
        var usage:Int?=null
        val platform=AndroidPaperHapticPlatform(context,view,vibrationSender={v,e,a->effects+=e;usage=a?.usage;v.vibrate(e,requireNotNull(a))})
        assertTrue(PaperHapticDispatcher(platform).perform(true,PaperHaptic.PAGE))
        assertEquals(listOf(VibrationEffect.createOneShot(25,VibrationEffect.DEFAULT_AMPLITUDE)),effects)
        assertEquals(25L,shadow.milliseconds);assertEquals(0,view.calls)
        assertEquals(false,PaperHapticDiagnostics.state.amplitudeControl)
        assertEquals(false,PaperHapticDiagnostics.state.systemTouchEnabled)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,usage)
        assertTrue(PaperHapticDiagnostics.state.lastCall.contains("TOUCH"))
        assertTrue(PaperHapticDiagnostics.state.lastCall.contains("25 мс"))
    }
    @Test fun defaultPublicApiActuallyRequestsMotorOnEverySupportedApi() {
        val motor=motor();val shadow=Shadows.shadowOf(motor);shadow.setHasVibrator(true)
        assertTrue(PaperHapticDispatcher(AndroidPaperHapticPlatform(context,null)).perform(true))
        assertEquals(25L,shadow.milliseconds)
    }
    @Test fun realTimerUsesFiniteWaveformAndAlarmCategoryNotTouch() {
        val motor=motor();val shadow=Shadows.shadowOf(motor);shadow.setHasVibrator(true)
        var attributes:AudioAttributes?=null
        val platform=AndroidPaperHapticPlatform(context,null,vibrationSender={v,e,a->attributes=a;v.vibrate(e,a!!)})
        assertTrue(PaperHapticDispatcher(platform).perform(true,PaperHaptic.TIMER))
        assertArrayEquals(longArrayOf(80,60,120),shadow.pattern.filter{it>0}.toLongArray());assertEquals(-1,shadow.repeat)
        assertEquals(AudioAttributes.USAGE_ALARM,attributes!!.usage)
    }
    @Test fun absentServiceAndMotorRemainDistinctEvenWhenViewSaysTrue() {
        val view=AcceptedView(context)
        assertFalse(PaperHapticDispatcher(AndroidPaperHapticPlatform(context,view,vibratorProvider={null})).perform(true))
        assertTrue(PaperHapticDiagnostics.state.lastCall.contains("Нет сервиса"))
        val motor=motor();Shadows.shadowOf(motor).setHasVibrator(false)
        assertFalse(PaperHapticDispatcher(AndroidPaperHapticPlatform(context,view,vibratorProvider={motor})).perform(true))
        assertEquals(false,PaperHapticDiagnostics.state.hasVibrator);assertTrue(PaperHapticDiagnostics.state.lastCall.contains("мотора нет"))
    }
    @Test fun platformExceptionIsReportedWithoutClaimingPhysicalSuccess() {
        val motor=motor();Shadows.shadowOf(motor).setHasVibrator(true)
        val platform=AndroidPaperHapticPlatform(context,null,vibrationSender={_,_,_->throw SecurityException("fixture policy denial")})
        assertFalse(PaperHapticDispatcher(platform).perform(true))
        assertEquals("SecurityException",PaperHapticDiagnostics.state.error)
    }
    @Test fun explicitTestDoesNotEnableSavedAutomaticFeedback() {
        val preferences=ReadingPreferences(context);preferences.chooseHaptics(false)
        val motor=motor();Shadows.shadowOf(motor).setHasVibrator(true)
        try {
            assertFalse(performPaperHaptic(context))
            assertTrue(performPaperHaptic(context,enabled=true))
            assertFalse(ReadingPreferences(context).haptics)
        }finally{preferences.chooseHaptics(true)}
    }
}
