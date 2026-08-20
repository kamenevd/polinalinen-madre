package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.viewmodel.fakes.FakeBakeHistory
import com.polinalinen.madre.viewmodel.fakes.FakeBakeSessionLedger
import com.polinalinen.madre.viewmodel.fakes.FakeShelfSync
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
class BakingRestoreFinishFromRecordTest {
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
    fun `finish can share from restored completion without active session`() = runTest(dispatcher) {
        val history = FakeBakeHistory().apply {
            put(
                BakeRecordEntity(
                    id = 55L,
                    recipeId = "r1",
                    recipeName = "Ржаной",
                    portions = 2,
                    completedAtMillis = 1_700_000_000_555L,
                ),
            )
        }
        val ledger = FakeBakeSessionLedger().apply { put(sessionId = 7L, recordId = 55L) }
        val sync = FakeShelfSync()
        val vm = BakingViewModel(app, history, sync, ledger)
        advanceUntilIdle()

        vm.stageBakePhoto(7L, "bake_photos/restored.jpg")
        var exits = 0
        vm.finish(7L, ShelfShareDecision.PUT_WITH_PHOTO) { exits += 1 }
        advanceUntilIdle()

        assertThat(sync.calls).hasSize(1)
        assertThat(sync.calls.single().recordId).isEqualTo(55L)
        assertThat(sync.calls.single().photoPath).isEqualTo("bake_photos/restored.jpg")
        assertThat(exits).isEqualTo(1)
    }

    @Test
    fun `restored finish with photo-required decision stays fail closed without staged photo`() = runTest(dispatcher) {
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
        val sync = FakeShelfSync()
        val vm = BakingViewModel(app, history, sync, ledger)
        advanceUntilIdle()

        var exits = 0
        vm.finish(12L, ShelfShareDecision.PUT_WITH_PHOTO) { exits += 1 }
        advanceUntilIdle()

        assertThat(sync.calls).isEmpty()
        assertThat(history.attachCalls).isEmpty()
        assertThat(vm.finishing.value.contains(12L)).isFalse()
        assertThat(exits).isEqualTo(0)
    }
}
