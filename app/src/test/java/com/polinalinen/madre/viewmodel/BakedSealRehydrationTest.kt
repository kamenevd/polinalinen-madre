package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
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
class BakedSealRehydrationTest {
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
    fun `restore completion uses persisted bake record date`() = runTest(dispatcher) {
        val completedAt = 1_700_086_680_000L
        val history = FakeBakeHistory().apply {
            put(
                BakeRecordEntity(
                    id = 23L,
                    recipeId = "rye",
                    recipeName = "Ржаной",
                    portions = 2,
                    completedAtMillis = completedAt,
                ),
            )
        }
        val ledger = FakeBakeSessionLedger().apply { put(sessionId = 4L, recordId = 23L) }
        val vm = BakingViewModel(app, history, FakeShelfSync(), ledger)
        advanceUntilIdle()

        vm.restoreCompletion(4L)
        advanceUntilIdle()

        assertThat(vm.completions.value[4L]?.completedAtMillis).isEqualTo(completedAt)
    }
}
