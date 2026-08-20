package com.polinalinen.madre.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest
import com.polinalinen.madre.data.repository.SourdoughRepository
import com.polinalinen.madre.sourdough.HydrationMath
import com.polinalinen.madre.notifications.FeedingSchedule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cycle 26: посчитанная гидратация обязана пережить выключенный телефон и
 * стать основанием следующего расчёта.
 *
 * База здесь НЕ in-memory — настоящий файл, который закрывают и открывают
 * заново (тот же приём, что в [StarterNamePersistenceTest]): in-memory
 * проверила бы только, что insert вернул управление.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FeedingHydrationPersistenceTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var db: MadreDatabase? = null

    private fun openBook(onPostInsert: suspend () -> Unit = {}): SourdoughRepository {
        val opened = MadreDatabase.build(context)
        db = opened
        return SourdoughRepository(opened, onPostInsert = onPostInsert)
    }

    private fun closeBook() {
        db?.close()
        db = null
    }

    @After
    fun tearDown() {
        closeBook()
        context.deleteDatabase("madre.db")
    }

    private fun feeding(
        configId: Long,
        timestampMillis: Long,
        finalHydrationPercent: Int? = null,
        hydrationPercent: Int? = null,
    ) = FeedingEntity(
        sourdoughConfigId = configId,
        timestampMillis = timestampMillis,
        flourGrams = 100,
        waterGrams = 50,
        storageLocation = StorageLocation.KITCHEN,
        retainedStarterGrams = if (finalHydrationPercent == null) null else 50,
        finalHydrationPercent = finalHydrationPercent,
        hydrationPercent = hydrationPercent,
        generatedComment = if (finalHydrationPercent == null) null else "снимок фактов",
    )

    private fun saveRequest(
        configId: Long,
        intervalHours: Int,
        timestampMillis: Long,
        retainedStarterGrams: Int = 50,
        flourGrams: Int = 100,
        waterGrams: Int = 50,
        weather: String? = null,
    ) = SourdoughFeedingSaveRequest(
        configId = configId,
        intervalHours = intervalHours,
        savedAtMillis = timestampMillis,
        retainedStarterGrams = retainedStarterGrams,
        flourGrams = flourGrams,
        waterGrams = waterGrams,
        storageLocation = StorageLocation.KITCHEN,
        note = "снимок фактов",
        photoPath = null,
        weather = weather,
    )

    @Test
    fun `a saved feeding supplies the next feeding's prior hydration`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()
        assertThat(repository.latestFinalHydration(config.id)).isNull()

        repository.addFeeding(feeding(config.id, 1_000L, finalHydrationPercent = 65))
        closeBook()

        // Телефон выключили и включили: книга открывается заново, с нуля.
        val afterRestart = openBook()
        assertThat(afterRestart.latestFinalHydration(config.id)).isEqualTo(65)
        assertThat(afterRestart.lastFeeding(config.id)?.generatedComment).isEqualTo("снимок фактов")
    }

    @Test
    fun `the newest computed hydration wins over older ones`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()
        repository.addFeeding(feeding(config.id, 1_000L, finalHydrationPercent = 50))
        repository.addFeeding(feeding(config.id, 2_000L, finalHydrationPercent = 72))

        assertThat(repository.latestFinalHydration(config.id)).isEqualTo(72)
    }

    @Test
    fun `equal timestamps pick the newest saved computed hydration`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()

        val first = repository.addFeeding(feeding(config.id, 1_000L, finalHydrationPercent = 50))
        val second = repository.addFeeding(feeding(config.id, 1_000L, finalHydrationPercent = 80))

        assertThat(first).isNotEqualTo(0L)
        assertThat(second).isGreaterThan(first)
        assertThat(repository.lastFeeding(config.id)?.id).isEqualTo(second)
        assertThat(repository.latestFinalHydration(config.id)).isEqualTo(80)
    }

    @Test
    fun `saveFeeding uses newest insertion for previous feeding and prior hydration`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()

        val first = repository.saveFeeding(saveRequest(config.id, config.intervalHours, 1_000L))
        val second = repository.saveFeeding(saveRequest(config.id, config.intervalHours, 1_000L))
        val rollback = repository.saveFeeding(saveRequest(config.id, config.intervalHours, 500L))

        val expectedRollbackHydration = HydrationMath.finalHydrationPercent(
            retainedStarterGrams = 50,
            priorHydrationPercent = second.feeding.finalHydrationPercent!!,
            addedFlourGrams = 100,
            addedWaterGrams = 50,
        )

        assertThat(repository.lastFeeding(config.id)?.id).isEqualTo(rollback.feedingId)
        assertThat(repository.latestFinalHydration(config.id)).isEqualTo(expectedRollbackHydration)
        assertThat(rollback.feeding.finalHydrationPercent).isEqualTo(expectedRollbackHydration)
        assertThat(first.feedingId).isLessThan(second.feedingId)
        assertThat(second.feedingId).isLessThan(rollback.feedingId)
    }

    @Test fun `saveFeeding with clock rollback keeps insertion-order authority`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()

        val first = repository.saveFeeding(saveRequest(config.id, config.intervalHours, 1_000L))
        val second = repository.saveFeeding(saveRequest(config.id, config.intervalHours, 500L))

        val expectedSecondHydration = HydrationMath.finalHydrationPercent(
            retainedStarterGrams = 50,
            priorHydrationPercent = first.feeding.finalHydrationPercent!!,
            addedFlourGrams = 100,
            addedWaterGrams = 50,
        )

        val latest = repository.lastFeeding(config.id)

        assertThat(latest?.id).isEqualTo(second.feedingId)
        assertThat(latest?.timestampMillis).isEqualTo(500L)
        assertThat(repository.latestFinalHydration(config.id)).isEqualTo(expectedSecondHydration)
        assertThat(second.feeding.finalHydrationPercent).isEqualTo(expectedSecondHydration)

        val dueFromSecond = FeedingSchedule.dueAtMillis(second.feeding.timestampMillis, config.intervalHours)
        val dueFromFirst = FeedingSchedule.dueAtMillis(first.feeding.timestampMillis, config.intervalHours)
        assertThat(dueFromSecond).isNotNull()
        assertThat(dueFromSecond).isEqualTo(FeedingSchedule.dueAtMillis(latest?.timestampMillis ?: 0L, config.intervalHours))
        assertThat(dueFromSecond).isNotEqualTo(dueFromFirst)
    }

    @Test
    fun `saveFeeding updates feeding row and config last feeding on success`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()

        val first = repository.saveFeeding(saveRequest(config.id, config.intervalHours, 1_000L, weather = "ясно"))
        val refreshedConfig = repository.getOrCreateDefaultConfig()

        assertThat(first.feeding.id).isEqualTo(first.feedingId)
        assertThat(refreshedConfig.lastFeedingMillis).isEqualTo(first.feeding.timestampMillis)
        assertThat(repository.lastFeeding(config.id)?.generatedComment).isNotEmpty()
        assertThat(repository.lastFeeding(config.id)?.generatedComment).contains("Погода за окном: ясно.")
    }

    @Test
    fun `saveFeeding transaction rollback leaves no partial changes when insert fails`() = runTest {
        val repository = openBook { throw IllegalStateException("post insert failure") }
        val config = repository.getOrCreateDefaultConfig()

        val request = saveRequest(config.id, config.intervalHours, 1_000L, weather = "сезон")
        val thrown = runCatching {
            repository.saveFeeding(request)
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("post insert failure", thrown?.message)

        assertThat(repository.lastFeeding(config.id)).isNull()
        assertThat(repository.latestFinalHydration(config.id)).isNull()
        assertThat(repository.getOrCreateDefaultConfig().lastFeedingMillis).isNull()
    }

    /**
     * Записи без расчёта пропускаются, а не считаются нулём: и совсем старая,
     * и та, где гидратацию когда-то подтверждали руками (v9). Ручное поле
     * питает только показ, но не арифметику следующего кормления.
     */
    @Test
    fun `legacy rows never seed the calculation`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()
        repository.addFeeding(feeding(config.id, 1_000L, finalHydrationPercent = 60))
        repository.addFeeding(feeding(config.id, 2_000L))
        repository.addFeeding(feeding(config.id, 3_000L, hydrationPercent = 90))

        assertThat(repository.latestFinalHydration(config.id)).isEqualTo(60)
        // А до самого первого расчёта источник ровно один — объявленная константа.
        assertThat(
            HydrationMath.priorHydrationPercent(listOf(null, null)),
        ).isEqualTo(HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT)
    }
}
