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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class BakingUnstagePhotoTest {
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
    fun `unstage removes photo and photo-required finish does nothing`() = runTest(dispatcher) {
        val history = FakeBakeHistory()
        val sync = FakeShelfSync()
        val vm = BakingViewModel(app, history, sync, FakeBakeSessionLedger())

        val path = "bake_photos/to_remove.jpg"
        val file = File(app.filesDir, path).apply {
            parentFile?.mkdirs()
            writeText("photo")
        }

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe(), scaleFactor = 1.0)
        vm.advanceStep(id)
        advanceUntilIdle()

        vm.stageBakePhoto(id, path)
        vm.unstageBakePhoto(id)
        repeat(20) {
            if (!file.exists()) return@repeat
            Thread.sleep(10)
        }

        var exits = 0
        vm.finish(id, ShelfShareDecision.PUT_WITH_PHOTO) { exits += 1 }

        assertThat(vm.bakePhotoPaths.value[id]).isNull()
        assertThat(file.exists()).isFalse()
        assertThat(history.attachCalls).isEmpty()
        assertThat(sync.calls).isEmpty()
        assertThat(vm.session(id)).isNotNull()
        assertThat(exits).isEqualTo(0)

        vm.exitSession(id)
    }
}
