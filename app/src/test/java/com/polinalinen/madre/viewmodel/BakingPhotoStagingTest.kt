package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.viewmodel.fakes.FakeBakeHistory
import com.polinalinen.madre.viewmodel.fakes.FakeBakeSessionLedger
import com.polinalinen.madre.viewmodel.fakes.FakeShelfSync
import com.polinalinen.madre.viewmodel.fakes.seedActiveSession
import com.polinalinen.madre.viewmodel.fakes.sampleRecipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class BakingPhotoStagingTest {
    private val dispatcher = StandardTestDispatcher()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        runCatching {
            WorkManager.initialize(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        }
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `stage photo updates memory immediately and does not touch room`() = runTest(dispatcher) {
        val history = FakeBakeHistory().apply { holdRecord = CompletableDeferred() }
        val vm = BakingViewModel(app, history, FakeShelfSync(), FakeBakeSessionLedger())

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe(), scaleFactor = 1.0)
        vm.advanceStep(id)
        advanceUntilIdle()

        vm.stageBakePhoto(id, "bake_photos/staged.jpg")

        assertThat(vm.bakePhotoPaths.value[id]).isEqualTo("bake_photos/staged.jpg")
        assertThat(history.attachCalls).isEmpty()
    }
}
