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
        val vm = BakingViewModel(app, history, sync, ledger)

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

        rule.onNodeWithText("На главную").assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.waitUntil(5_000){exited}
        assertThat(exited).isTrue()
        assertThat(history.recordCalls).isEmpty()
        assertThat(sync.calls).isEmpty()
        rule.onNodeWithText("ушли с экрана").assertExists()
        rule.runOnIdle { androidx.lifecycle.ViewModelStore().apply { put("bake",vm);clear() } }
        rule.waitForIdle()
    }
}
