package com.polinalinen.madre.ui.kit

import android.content.Context
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class PaperHapticsPlatformTest {
    private class RecordingView(context:Context):View(context) {
        var calls=0;var kind=-1;var flags=0
        override fun performHapticFeedback(feedbackConstant:Int,flags:Int):Boolean {
            calls++;kind=feedbackConstant;this.flags=flags;return true
        }
    }
    @Test fun actualViewUsesIgnoreViewSettingAndStrongerTimerType() {
        val context=ApplicationProvider.getApplicationContext<Context>();val view=RecordingView(context)
        val platform=AndroidPaperHapticPlatform(context,view)
        assertTrue(platform.viewFeedback(PaperHaptic.PAGE));assertEquals(HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,view.flags)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK,view.kind)
        assertTrue(platform.viewFeedback(PaperHaptic.TIMER));assertEquals(HapticFeedbackConstants.LONG_PRESS,view.kind)
        assertEquals(2,view.calls)
    }
    @Test fun realPersistedAppOptOutPreventsActualViewDispatch() {
        val context=ApplicationProvider.getApplicationContext<Context>();val view=RecordingView(context)
        val preferences=ReadingPreferences(context)
        preferences.chooseHaptics(false)
        try {assertFalse(performPaperHaptic(context,view));assertEquals(0,view.calls)}
        finally {preferences.chooseHaptics(true)}
    }
}
