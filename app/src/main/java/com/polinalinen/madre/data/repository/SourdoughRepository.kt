package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    /** Cycle 11: интервал кормления из колофона — теперь настоящая настройка. */
    suspend fun setIntervalHours(configId: Long, hours: Int) = withContext(Dispatchers.IO) {
        db.sourdoughConfigDao().updateIntervalHours(configId, hours)
    }

    /** Cycle 11: выключенные напоминания снимают запланированную работу. */
    suspend fun setRemindersEnabled(configId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.sourdoughConfigDao().updateRemindersEnabled(configId, enabled)
    }

    suspend fun addFeeding(feeding: FeedingEntity): Long = withContext(Dispatchers.IO) {
        val id = db.feedingDao().insert(feeding)
        // Обновляем lastFeedingMillis в конфиге, чтобы SourdoughProfile.hoursSinceFeeding()
        // считал статус от актуального кормления (раньше этот шаг был обещан в
        // комментарии, но не выполнялся — правка Cycle 3, 2026-07-21).
        db.sourdoughConfigDao().updateLastFeeding(feeding.sourdoughConfigId, feeding.timestampMillis)
        id
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
