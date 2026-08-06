package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cycle 15: незавершённые выпечки — то немногое, что переживает перезагрузку
 * телефона. Пишет BakingViewModel на каждом переходе шага, читает после ребута
 * notifications/BakeRestoreWorker. IO — на Dispatchers.IO (баг v3 #6).
 */
class ActiveBakeRepository(db: MadreDatabase) {
    private val dao = db.activeBakeDao()

    suspend fun all(): List<ActiveBakeEntity> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun remember(bake: ActiveBakeEntity) = withContext(Dispatchers.IO) { dao.upsert(bake) }

    /** Выпечка закончена или брошена — восстанавливать после ребута больше нечего. */
    suspend fun forget(sessionId: Long) = withContext(Dispatchers.IO) { dao.delete(sessionId) }
}
