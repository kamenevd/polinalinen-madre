package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.data.db.entities.UserEntity
import com.polinalinen.madre.sourdough.FeedingComment
import com.polinalinen.madre.sourdough.FeedingFacts
import com.polinalinen.madre.sourdough.HydrationMath
import com.polinalinen.madre.sourdough.StarterName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.room.withTransaction

data class SourdoughFeedingSaveRequest(
    val configId: Long,
    val intervalHours: Int,
    val savedAtMillis: Long,
    val retainedStarterGrams: Int,
    val flourGrams: Int,
    val waterGrams: Int,
    val storageLocation: StorageLocation,
    val note: String?,
    val photoPath: String?,
    val weather: String?,
)

data class SourdoughFeedingSaveResult(
    val feedingId: Long,
    val feeding: FeedingEntity,
)

/**
 * Repository layer поверх Room для закваски — новое в v4 (в v3 ViewModel обращался
 * к DAO напрямую). Все suspend-функции — на Dispatchers.IO (баг v3 #6: blocking I/O
 * на main thread через GSON/file.delete()).
 */
class SourdoughRepository(
    private val db: MadreDatabase,
    private val onPostInsert: suspend () -> Unit = {},
) {

    fun observeConfig(userId: Long): Flow<SourdoughConfigEntity?> =
        db.sourdoughConfigDao().observeForUser(userId)

    fun observeHistory(configId: Long): Flow<List<FeedingEntity>> =
        db.feedingDao().observeHistory(configId)

    suspend fun saveConfig(config: SourdoughConfigEntity): Long = withContext(Dispatchers.IO) {
        db.sourdoughConfigDao().upsert(config)
    }

    /** Cycle 11: интервал кормления из колофона — теперь настоящая настройка. */
    suspend fun setIntervalHours(configId: Long, hours: Int) = withContext(Dispatchers.IO) {
        db.sourdoughConfigDao().updateIntervalHours(configId, hours)
    }

    /**
     * Cycle 14: переименовать закваску. Имя приводится к общему виду ДО записи
     * ([StarterName.sanitize]), а не при показе: в базе не должно оказаться
     * строки, которую каждый экран потом чинит по-своему.
     */
    suspend fun setName(configId: Long, name: String) = withContext(Dispatchers.IO) {
        db.sourdoughConfigDao().updateName(configId, StarterName.sanitize(name))
    }

    /** Cycle 11: выключенные напоминания снимают запланированную работу. */
    suspend fun setRemindersEnabled(configId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.sourdoughConfigDao().updateRemindersEnabled(configId, enabled)
    }

    /** Прошлое кормление целиком — из него комментарий берёт «в прошлый раз дали…». */
    suspend fun lastFeeding(configId: Long): FeedingEntity? = withContext(Dispatchers.IO) {
        db.feedingDao().getLast(configId)
    }

    /**
     * Cycle 26: гидратация, от которой считается новое кормление. null — ни
     * одного посчитанного кормления ещё нет, и тогда (и только тогда) берётся
     * HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT.
     */
    suspend fun latestFinalHydration(configId: Long): Int? = withContext(Dispatchers.IO) {
        db.feedingDao().latestFinalHydration(configId)
    }

    suspend fun saveFeeding(request: SourdoughFeedingSaveRequest): SourdoughFeedingSaveResult = withContext(
        Dispatchers.IO
    ) {
        db.withTransaction {
            val priorHydrationRow = db.feedingDao().latestFinalHydration(request.configId)
            val priorHydration = priorHydrationRow
                ?: HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT

            val previous = db.feedingDao().getLast(request.configId)
            val finalHydration = HydrationMath.finalHydrationPercent(
                retainedStarterGrams = request.retainedStarterGrams,
                priorHydrationPercent = priorHydration,
                addedFlourGrams = request.flourGrams,
                addedWaterGrams = request.waterGrams,
            )

            val feeding = FeedingEntity(
                sourdoughConfigId = request.configId,
                timestampMillis = request.savedAtMillis,
                flourGrams = request.flourGrams,
                waterGrams = request.waterGrams,
                storageLocation = request.storageLocation,
                notes = request.note,
                photoPath = request.photoPath,
                retainedStarterGrams = request.retainedStarterGrams,
                finalHydrationPercent = finalHydration,
                generatedComment = FeedingComment.compose(
                    FeedingFacts(
                            savedAtMillis = request.savedAtMillis,
                            intervalHours = request.intervalHours,
                            previousFeedingMillis = previous?.timestampMillis,
                            previousFlourGrams = previous?.flourGrams,
                            previousWaterGrams = previous?.waterGrams,
                            priorHydrationPercent = priorHydration,
                            priorHydrationIsInitial = priorHydrationRow == null,
                            retainedStarterGrams = request.retainedStarterGrams,
                            addedFlourGrams = request.flourGrams,
                            addedWaterGrams = request.waterGrams,
                            finalHydrationPercent = finalHydration,
                            weather = request.weather,
                    )
                ),
            )
            val id = db.feedingDao().insert(feeding)
            onPostInsert()
            db.sourdoughConfigDao().updateLastFeeding(request.configId, request.savedAtMillis)
            SourdoughFeedingSaveResult(
                feedingId = id,
                feeding = feeding.copy(id = id),
            )
        }
    }

    @Deprecated("Use saveFeeding() with request/result object.")
    suspend fun addFeeding(feeding: FeedingEntity): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val id = db.feedingDao().insert(feeding)
            // Обновляем lastFeedingMillis в той же транзакции, чтобы вставка и
            // отметка «последнее кормление» никогда не разъехались.
            db.sourdoughConfigDao().updateLastFeeding(feeding.sourdoughConfigId, feeding.timestampMillis)
            onPostInsert()
            id
        }
    }

    /**
     * v4 decision #13 — модель поддерживает много пользователей/заквасок, но
     * переключающего UI пока нигде нет (см. CLAUDE.md/память). Вместо этого здесь
     * находим-или-создаём ровно одну неявную пару User+Config при первом запуске —
     * этого достаточно для одной семьи на одном устройстве, без лишнего экрана.
     */
    suspend fun getOrCreateDefaultConfig(): SourdoughConfigEntity = withContext(Dispatchers.IO) {
        val user = db.userDao().observeActive().first()
            ?: run {
                val newId = db.userDao().upsert(UserEntity(name = "Пекарь"))
                UserEntity(id = newId, name = "Пекарь")
            }
        db.sourdoughConfigDao().observeForUser(user.id).first()
            ?: run {
                val newId = db.sourdoughConfigDao().upsert(SourdoughConfigEntity(userId = user.id))
                SourdoughConfigEntity(id = newId, userId = user.id)
            }
    }
}
