package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.viewmodel.fakes.FakeBakeHistory
import com.polinalinen.madre.viewmodel.fakes.FakeBakeSessionLedger
import com.polinalinen.madre.viewmodel.fakes.FakeShelfSync
import com.polinalinen.madre.viewmodel.fakes.seedActiveSession
import com.polinalinen.madre.viewmodel.fakes.sampleRecipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
class BakeCompletionOnceTest {
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
    fun `double advance and double finish still persist and share once`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val history = FakeBakeHistory().apply { holdRecord = gate }
        val sync = FakeShelfSync()
        val ledger = FakeBakeSessionLedger()
        val vm = BakingViewModel(app, history, sync, ledger)

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe(), scaleFactor = 1.0)
        vm.advanceStep(id)
        vm.advanceStep(id)
        advanceUntilIdle()

        assertThat(history.recordCalls).hasSize(1)

        vm.stageBakePhoto(id, "bake_photos/frozen.jpg")
        var exits = 0
        vm.finish(id, ShelfShareDecision.PUT_WITH_PHOTO) { exits += 1 }
        vm.finish(id, ShelfShareDecision.PUT_WITH_PHOTO) { exits += 1 }

        advanceTimeBy(1_000)
        runCurrent()
        assertThat(sync.calls).isEmpty()
        assertThat(exits).isEqualTo(0)

        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(history.attachCalls).hasSize(1)
        assertThat(sync.calls).hasSize(1)
        assertThat(exits).isEqualTo(1)
    }

    @Test
    fun `finish exits after timeout when record id never becomes ready`() = runTest(dispatcher) {
        val history = FakeBakeHistory().apply { holdRecord = CompletableDeferred() }
        val sync = FakeShelfSync()
        val vm = BakingViewModel(app, history, sync, FakeBakeSessionLedger())

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe("timeout"), scaleFactor = 1.0)
        vm.advanceStep(id)
        advanceUntilIdle()

        var exits = 0
        vm.finish(id, ShelfShareDecision.KEEP) { exits += 1 }
        advanceTimeBy(5_100)
        advanceUntilIdle()

        assertThat(history.attachCalls).isEmpty()
        assertThat(sync.calls).isEmpty()
        assertThat(vm.session(id)).isNull()
        assertThat(exits).isEqualTo(1)
    }
}
