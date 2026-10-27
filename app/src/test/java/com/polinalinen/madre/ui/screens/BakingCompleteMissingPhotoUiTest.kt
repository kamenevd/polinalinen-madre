package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.ui.kit.RazvorotTheme as MadreTheme
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.fakes.FakeBakeHistory
import com.polinalinen.madre.viewmodel.fakes.FakeBakeSessionLedger
import com.polinalinen.madre.viewmodel.fakes.FakeShelfSync
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class BakingCompleteMissingPhotoUiTest {

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
    fun `missing photo with restored completion finishes privately without forcing a picker`() {
        val history = FakeBakeHistory().apply {
            put(
                BakeRecordEntity(
                    id = 99L,
                    recipeId = "r2",
                    recipeName = "Пшеничный",
                    portions = 1,
                    completedAtMillis = 1_700_000_000_999L,
                ),
            )
        }
        val ledger = FakeBakeSessionLedger().apply { put(sessionId = 12L, recordId = 99L) }
        val sync=FakeShelfSync()
        val vm = rule.runOnIdle { BakingViewModel(app, history, sync, ledger) }
        rule.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.restoreReady.value
        }

        assertThat(vm.session(12L)).isNull()

        var exited by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                MadreTheme {
                    if (exited) {
                        Text("ушли с экрана")
                    } else {
                        BakingFinale(
                            sessionId = 12L,
                            onHome = { exited = true },
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.completions.value[12L]?.recordId?.isCompleted == true
        }
        assertThat(kotlinx.coroutines.runBlocking { vm.completions.value.getValue(12L).recordId.await() })
            .isEqualTo(99L)

        // Force the real durable-cleanup lock to suspend finish, as Room/restore can in CI.
        // Unlock posts its continuation to Robolectric's paused Main, outside Compose's frame clock.
        val durableGate = (app as MadreApplication).plannedBakeOperationGate
        kotlinx.coroutines.runBlocking { durableGate.lock() }
        try {
            rule.onNodeWithText("На главную").assertIsDisplayed().performClick()
            rule.waitForIdle()
            assertThat(vm.finishing.value).contains(12L)
            assertThat(exited).isFalse()
            assertThat(ledger.recordIdFor(12L)).isEqualTo(99L)
        } finally {
            Thread { durableGate.unlock() }.apply { start(); join() }
        }

        rule.waitUntil(5_000) {
            // waitForIdle can finish before IO unlock queues durable cleanup back on Main.
            // Advancing Compose frames alone cannot run that paused Android-looper continuation.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            exited
        }
        assertThat(exited).isTrue()
        assertThat(ledger.recordIdFor(12L)).isNull()
        assertThat(vm.completions.value).doesNotContainKey(12L)
        assertThat(history.attachCalls).isEmpty()
        assertThat(history.recordCalls).isEmpty()
        assertThat(sync.calls).isEmpty()
        rule.onNodeWithText("ушли с экрана").assertExists()
        rule.runOnIdle { androidx.lifecycle.ViewModelStore().apply { put("bake",vm);clear() } }
        rule.waitForIdle()
    }
}
