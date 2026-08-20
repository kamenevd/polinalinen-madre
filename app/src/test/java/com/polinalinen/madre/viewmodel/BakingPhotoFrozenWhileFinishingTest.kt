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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class BakingPhotoFrozenWhileFinishingTest {
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
    fun `photo controls are frozen after finish starts`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val history = FakeBakeHistory().apply { holdRecord = gate }
        val sync = FakeShelfSync()
        val vm = BakingViewModel(app, history, sync, FakeBakeSessionLedger())

        val a = "bake_photos/a.jpg"
        val b = "bake_photos/b.jpg"
        val fileA = File(app.filesDir, a).apply {
            parentFile?.mkdirs()
            writeText("A")
        }
        File(app.filesDir, b).apply {
            parentFile?.mkdirs()
            writeText("B")
        }

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe(), scaleFactor = 1.0)
        vm.advanceStep(id)
        advanceUntilIdle()
        vm.stageBakePhoto(id, a)

        var exits = 0
        vm.finish(id, ShelfShareDecision.PUT_WITH_PHOTO) { exits += 1 }
        vm.stageBakePhoto(id, b)
        vm.unstageBakePhoto(id)

        assertThat(vm.bakePhotoPaths.value[id]).isEqualTo(a)
        assertThat(fileA.exists()).isTrue()
        assertThat(vm.finishing.value.contains(id)).isTrue()

        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(history.attachCalls).containsExactly(1L to a)
        assertThat(sync.calls.single().photoPath).isEqualTo(a)
        assertThat(exits).isEqualTo(1)
    }
}
