package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository layer поверх Room для закваски — новое в v4 (в v3 ViewModel обращался
 * к DAO напрямую). Все suspend-функции — на Dispatchers.IO (баг v3 #6: blocking I/O
 * на main thread через GSON/file.delete()).
 */
class SourdoughRepository(private val db: MadreDatabase) {

    fun observeConfig(userId: Long): Flow<SourdoughConfigEntity?> =
        db.sourdoughConfigDao().observeForUser(userId)

    fun observeHistory(configId: Long): Flow<List<FeedingEntity>> =
        db.feedingDao().observeHistory(configId)

    suspend fun saveConfig(config: SourdoughConfigEntity): Long = withContext(Dispatchers.IO) {
        db.sourdoughConfigDao().upsert(config)
    }

    suspend fun addFeeding(feeding: FeedingEntity): Long = withContext(Dispatchers.IO) {
        val id = db.feedingDao().insert(feeding)
        // Обновляем lastFeedingMillis в конфиге, чтобы SourdoughProfile.hoursSinceFeeding()
        // считал статус от актуального кормления.
        id
    }
}
