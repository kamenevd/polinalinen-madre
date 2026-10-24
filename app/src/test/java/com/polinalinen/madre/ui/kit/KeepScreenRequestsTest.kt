package com.polinalinen.madre.ui.kit

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Actual shared Android View + production composables; not a window-flag source-string check. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MadreApplication::class)
class KeepScreenRequestsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun timerAndOverlayBothReleaseKeepOnWhenTheSharedPreferenceTurnsOff() {
        var enabled by mutableStateOf(true)
        var overlay by mutableStateOf(true)
        lateinit var view: android.view.View
        rule.setContent {
            view = LocalView.current
            Immersive(keepScreenOn = enabled)
            if (overlay) Immersive(keepScreenOn = enabled)
        }
        rule.runOnIdle { assertTrue(view.keepScreenOn); enabled = false }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(view.keepScreenOn); overlay = false }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(view.keepScreenOn) }
    }
    @Test fun releasingAnOverlayDoesNotClearAStillActiveTimerRequest() {
        var overlay by mutableStateOf(true)
        lateinit var view: android.view.View
        rule.setContent {
            view = LocalView.current
            Immersive(keepScreenOn = true)
            if (overlay) Immersive(keepScreenOn = true)
        }
        rule.runOnIdle { overlay = false }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(view.keepScreenOn) }
    }
}
