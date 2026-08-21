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
class BakingFinishOrderTest {
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
    fun `photo is attached before share and exit happens after both`() = runTest(dispatcher) {
        val history = FakeBakeHistory()
        val sync = FakeShelfSync()
        val ledger = FakeBakeSessionLedger()
        val vm = BakingViewModel(app, history, sync, ledger)
        val events = mutableListOf<String>()
        history.onEvent = { events += it }
        sync.onEvent = { events += it }

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe(), scaleFactor = 2.0)
        vm.advanceStep(id)
        advanceUntilIdle()
        vm.stageBakePhoto(id, "bake_photos/final.jpg")
        vm.finish(id, ShelfShareDecision.PUT_WITH_PHOTO) { events += "exit" }
        advanceUntilIdle()

        assertThat(history.attachCalls).hasSize(1)
        assertThat(sync.calls).hasSize(1)
        assertThat(sync.calls.single().photoPath).isEqualTo("bake_photos/final.jpg")
        assertThat(events.indexOf("attach")).isLessThan(events.indexOf("share"))
        assertThat(events.indexOf("share")).isLessThan(events.indexOf("exit"))
    }

    @Test
    fun `keep decision writes personal photo but does not enqueue share`() = runTest(dispatcher) {
        val history = FakeBakeHistory()
        val sync = FakeShelfSync()
        val ledger = FakeBakeSessionLedger()
        val vm = BakingViewModel(app, history, sync, ledger)

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe("keep"), scaleFactor = 1.0)
        vm.advanceStep(id)
        advanceUntilIdle()
        vm.stageBakePhoto(id, "bake_photos/private.jpg")
        vm.finish(id, ShelfShareDecision.KEEP)
        advanceUntilIdle()

        assertThat(history.attachCalls).containsExactly(1L to "bake_photos/private.jpg")
        assertThat(sync.calls).isEmpty()
    }
}
