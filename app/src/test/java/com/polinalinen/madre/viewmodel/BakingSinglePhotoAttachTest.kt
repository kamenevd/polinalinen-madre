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
class BakingSinglePhotoAttachTest {
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
    fun `second staged photo is the only attached one`() = runTest(dispatcher) {
        val history = FakeBakeHistory()
        val sync = FakeShelfSync()
        val vm = BakingViewModel(app, history, sync, FakeBakeSessionLedger())

        val firstPath = "bake_photos/first.jpg"
        val secondPath = "bake_photos/second.jpg"
        val firstFile = File(app.filesDir, firstPath).apply {
            parentFile?.mkdirs()
            writeText("first")
        }
        File(app.filesDir, secondPath).apply {
            parentFile?.mkdirs()
            writeText("second")
        }

        val id = seedActiveSession(vm, sessionId = 1L, recipe = sampleRecipe(), scaleFactor = 1.0)
        vm.advanceStep(id)
        advanceUntilIdle()

        vm.stageBakePhoto(id, firstPath)
        vm.stageBakePhoto(id, secondPath)
        repeat(20) {
            if (!firstFile.exists()) return@repeat
            Thread.sleep(10)
        }

        vm.finish(id, ShelfShareDecision.PUT_WITH_PHOTO)
        advanceUntilIdle()

        assertThat(history.attachCalls).containsExactly(1L to secondPath)
        assertThat(sync.calls.single().photoPath).isEqualTo(secondPath)
        assertThat(firstFile.exists()).isFalse()
    }
}
