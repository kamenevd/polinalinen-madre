package com.polinalinen.madre.ui.screens

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.ui.theme.MadreTheme
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.fakes.FakeBakeHistory
import com.polinalinen.madre.viewmodel.fakes.FakeBakeSessionLedger
import com.polinalinen.madre.viewmodel.fakes.FakeShelfSync
import com.polinalinen.madre.viewmodel.fakes.sampleRecipe
import com.polinalinen.madre.viewmodel.fakes.seedActiveSession
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class BakingCompleteBusyWhileFinishingUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        runCatching {
            WorkManager.initialize(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        }
    }

    @Test
    fun `system back during finish does not trigger second exit while record is held`() {
        val hold = CompletableDeferred<Unit>()
        val history = FakeBakeHistory().apply { holdRecord = hold }
        val vm = BakingViewModel(app, history, FakeShelfSync(), FakeBakeSessionLedger())

        val sessionId = seedActiveSession(
            vm = vm,
            sessionId = 41L,
            recipe = sampleRecipe("busy"),
            scaleFactor = 1.0,
        )
        vm.advanceStep(sessionId)
        rule.waitForIdle()

        var exits = 0
        vm.finish(sessionId, ShelfShareDecision.KEEP) { exits += 1 }
        rule.waitForIdle()

        rule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                MadreTheme {
                    BakingCompleteScreen(
                        sessionId = sessionId,
                        onHome = { exits += 1 },
                        viewModel = vm,
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("На главную").assertIsNotEnabled()

        rule.runOnIdle {
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()

        assertThat(rule.activity.isFinishing).isFalse()
        assertThat(exits).isEqualTo(0)

        hold.complete(Unit)
        rule.waitUntil(timeoutMillis = 5_000) { exits == 1 }
        assertThat(exits).isEqualTo(1)
    }
}
