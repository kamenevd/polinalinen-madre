package com.polinalinen.madre.ui.screens

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.ui.theme.MadreTheme
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
    fun `missing photo with restored completion does not leave complete screen`() {
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
        val vm = BakingViewModel(app, history, FakeShelfSync(), ledger)

        assertThat(vm.session(12L)).isNull()

        var exited = false
        rule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                MadreTheme {
                    if (exited) {
                        Text("ушли с экрана")
                    } else {
                        BakingCompleteScreen(
                            sessionId = 12L,
                            onHome = { exited = true },
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("На главную").performScrollTo().performClick()
        rule.waitForIdle()

        assertThat(exited).isFalse()
        rule.onNodeWithText("На главную").assertExists()
        rule.onNodeWithText("ушли с экрана").assertDoesNotExist()
    }
}
