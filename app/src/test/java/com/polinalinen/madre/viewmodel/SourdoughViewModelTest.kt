package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveResult
import com.polinalinen.madre.sourdough.HydrationMath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class SourdoughViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val config = SourdoughConfigEntity(
        id = 1L,
        userId = 1L,
        name = "Мадре",
        intervalHours = 24,
        remindersEnabled = true,
    )

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        runCatching {
            WorkManager.initialize(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        }
    }

    @After
    fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private fun viewModel(
        loadConfig: suspend () -> SourdoughConfigEntity = { config },
        observeConfig: (Long) -> Flow<SourdoughConfigEntity?> = { flowOf(config) },
        describeWeather: suspend (Long) -> String? = { null },
        saveFeeding: suspend (SourdoughFeedingSaveRequest) -> SourdoughFeedingSaveResult =
            { request ->
                SourdoughFeedingSaveResult(
                    11L,
                    request.toSavedFeeding(
                        id = 11L,
                        finalHydrationPercent = HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT,
                    ),
                )
            },
        afterInsert: suspend (Long, Int, Int, Long) -> Unit = { _, _, _, _ -> },
        clock: () -> Long = { 1_700_000_000_000L },
    ) = SourdoughViewModel(
        app,
        loadConfig = loadConfig,
        observeConfig = observeConfig,
        describeWeather = describeWeather,
        saveFeeding = saveFeeding,
        afterInsert = afterInsert,
        clock = clock,
    )

    private fun SourdoughFeedingSaveRequest.toSavedFeeding(
        id: Long,
        finalHydrationPercent: Int,
        generatedComment: String = "Гидратация после кормления: $finalHydrationPercent%.",
    ) = FeedingEntity(
        id = id,
        sourdoughConfigId = configId,
        timestampMillis = savedAtMillis,
        flourGrams = flourGrams,
        waterGrams = waterGrams,
        storageLocation = storageLocation,
        notes = note,
        photoPath = photoPath,
        retainedStarterGrams = retainedStarterGrams,
        finalHydrationPercent = finalHydrationPercent,
        generatedComment = generatedComment,
    )

    @Test
    fun `double save while saving persists only one local row`() = runTest(dispatcher) {
        var insertCount = 0
        val proceed = CompletableDeferred<Unit>()

        val vm = viewModel(
            saveFeeding = { request ->
                insertCount += 1
                proceed.await()
                SourdoughFeedingSaveResult(
                    100L + insertCount,
                    request.toSavedFeeding(
                        id = 100L + insertCount,
                        finalHydrationPercent = 55,
                        generatedComment = "Гидратация после кормления: 55%.",
                    ),
                )
            },
            clock = { 1_700_000_000_100L },
        )

        advanceUntilIdle()
        vm.feed(50, 100, 50, StorageLocation.KITCHEN, null)
        vm.feed(55, 120, 60, StorageLocation.KITCHEN, null)

        assertThat(vm.saveState.value).isEqualTo(FeedingSaveState.Saving)

        proceed.complete(Unit)
        advanceUntilIdle()

        assertThat(insertCount).isEqualTo(1)
        assertThat(vm.saveState.value).isEqualTo(FeedingSaveState.Success(101L))
    }

    @Test
    fun `weather timeout keeps the row and omits the weather claim`() = runTest(dispatcher) {
        var savedComment = ""

        val vm = viewModel(
            describeWeather = {
                delay(6_000)
                "+18°, влажность 55%"
            },
            saveFeeding = { request ->
                savedComment = "Гидратация после кормления: 58%. " +
                    request.weather?.let { "Погода за окном: $it." }.orEmpty()
                SourdoughFeedingSaveResult(
                    321L,
                    request.toSavedFeeding(
                        id = 321L,
                        finalHydrationPercent = 58,
                        generatedComment = savedComment,
                    ),
                )
            },
            clock = { 1_700_000_000_200L },
        )

        advanceUntilIdle()
        vm.feed(50, 100, 50, StorageLocation.KITCHEN, null)
        advanceUntilIdle()

        assertThat(vm.saveState.value).isInstanceOf(FeedingSaveState.Success::class.java)
        assertThat(savedComment).isNotEmpty()
        assertThat(savedComment).doesNotContain("Погода")
        assertThat(savedComment).contains("Гидратация после кормления")
    }

    @Test
    fun `weather exception keeps persistence and removes weather claim from comment`() = runTest(dispatcher) {
        var savedComment = ""

        val vm = viewModel(
            describeWeather = { throw IllegalStateException("permission denied") },
            saveFeeding = { request ->
                savedComment = "Гидратация после кормления: 57%. " +
                    request.weather?.let { "Погода за окном: $it." }.orEmpty()
                SourdoughFeedingSaveResult(
                    222L,
                    request.toSavedFeeding(
                        id = 222L,
                        finalHydrationPercent = 57,
                        generatedComment = savedComment,
                    ),
                )
            },
            clock = { 1_700_000_000_250L },
        )

        advanceUntilIdle()
        vm.feed(50, 100, 50, StorageLocation.KITCHEN, null)
        advanceUntilIdle()

        assertThat(vm.saveState.value).isInstanceOf(FeedingSaveState.Success::class.java)
        assertThat(savedComment).doesNotContain("Погода")
    }

    @Test
    fun `persistence failure does not report success`() = runTest(dispatcher) {
        val vm = viewModel(
            saveFeeding = { throw IllegalStateException("room closed") },
            clock = { 1_700_000_000_300L },
        )

        advanceUntilIdle()
        vm.feed(50, 100, 50, StorageLocation.KITCHEN, null)
        advanceUntilIdle()

        val state = vm.saveState.value
        assertThat(state).isInstanceOf(FeedingSaveState.Error::class.java)
        assertThat((state as FeedingSaveState.Error).message).contains("room closed")
    }
}
