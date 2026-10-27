package com.polinalinen.madre.ui.razvorot

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class ReadingMotionDefaultTest {
    @get:Rule val rule=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() {
        context.getSharedPreferences("madre_prefs",0).edit().clear().commit()
        context.getSharedPreferences("motion-default",0).edit().clear().commit()
    }
    @After fun restoreScale() { Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f) }
    @Test fun freshInstallIsBookAndDoesNotReduceMotion() {
        val preferences=ReadingPreferences(context,"motion-default")
        assertThat(preferences.mode).isEqualTo(ReadingMode.BOOK)
        assertThat(preferences.reducedMotion).isFalse()
        assertThat(CalmModeSetting(SharedPreferencesFlagStore(context.getSharedPreferences("madre_prefs",0))).isCalm()).isFalse()
    }
    @Test fun migratesOnlyExplicitLegacyCalmChoice() {
        context.getSharedPreferences("madre_prefs",0).edit().putBoolean("calm_mode",true).commit()
        assertThat(ReadingPreferences(context,"motion-default").reducedMotion).isTrue()
    }
    @Test fun explicitReadingChoiceWinsOverConflictingLegacyCalmAndSurvivesRestart() {
        context.getSharedPreferences("madre_prefs",0).edit().putBoolean("calm_mode",true).commit()
        context.getSharedPreferences("motion-default",0).edit().putBoolean("reducedMotion",false).commit()
        val preferences=ReadingPreferences(context,"motion-default")
        assertThat(preferences.reducedMotion).isFalse()
        preferences.setReduced(true)
        assertThat(ReadingPreferences(context,"motion-default").reducedMotion).isTrue()
        preferences.setReduced(false)
        assertThat(ReadingPreferences(context,"motion-default").reducedMotion).isFalse()
    }
    @Test fun explicitLegacyFalseStaysFalse() {
        context.getSharedPreferences("madre_prefs",0).edit().putBoolean("calm_mode",false).commit()
        assertThat(ReadingPreferences(context,"motion-default").reducedMotion).isFalse()
    }
    @Test fun explicitSavedReductionStillUsesRealSlideFallbackAtSystemScaleZero() {
        Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        ReadingPreferences(context,"motion-default").setReduced(true)
        val preferences=ReadingPreferences(context,"motion-default")
        val debug=PaperDebugState()
        rule.setContent { CompositionLocalProvider(LocalPaperDebug provides debug) {
            RazvorotTheme(preferences,Atmosphere.DAWN) {
                Box(Modifier.size(412.dp,800.dp)) { PaperTurn(0,2,preferences,{}) { Text("Страница $it") } }
            }
        } }
        rule.waitForIdle()
        assertThat(preferences.reducedMotion).isTrue()
        assertThat(debug.renderer).isEqualTo("SLIDE")
        assertThat(debug.reason).isEqualTo("reduced-motion")
    }
    @Test fun migrationDoesNotResetExplicitScrollOrSlideChoices() {
        for(mode in listOf(ReadingMode.SCROLL,ReadingMode.SLIDE)) {
            context.getSharedPreferences("motion-default",0).edit().putString("mode",mode.name).commit()
            assertThat(ReadingPreferences(context,"motion-default").mode).isEqualTo(mode)
        }
    }
    @Test fun actualComposeTurnAtSystemScaleZeroUsesOnlyRealRendererFallbackNotAutomaticReduction() {
        Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        val preferences=ReadingPreferences(context,"motion-default")
        val debug=PaperDebugState()
        rule.setContent { CompositionLocalProvider(LocalPaperDebug provides debug) {
            RazvorotTheme(preferences,Atmosphere.DAWN) {
                Box(Modifier.size(412.dp,800.dp)) { PaperTurn(0,2,preferences,{}) { Text("Страница $it") } }
            }
        } }
        rule.waitForIdle()
        assertThat(preferences.reducedMotion).isFalse()
        // Native Compose reports hardware eligibility here, but Robolectric cannot start EGL.
        // PENDING is deliberately not called a working GPU curl. Scale zero must not force SLIDE.
        assertThat(debug.renderer).isEqualTo("PENDING")
        assertThat(debug.reason).isEqualTo("awaiting-surface")
    }
}
