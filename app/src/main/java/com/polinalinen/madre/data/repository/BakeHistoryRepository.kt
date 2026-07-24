package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * История завершённых выпечек — питает «Формуляр книги» и хитмэп на Полке.
 * Пишем один раз при завершении сессии (BakingViewModel), читаем как Flow
 * для BookStatsScreen. IO — на Dispatchers.IO (баг v3 #6).
 */
class BakeHistoryRepository(db: MadreDatabase) {
    private val dao = db.bakeRecordDao()

    fun observeAll(): Flow<List<BakeRecordEntity>> = dao.observeAll()

    suspend fun record(recipeId: String, recipeName: String, portions: Int) = withContext(Dispatchers.IO) {
        dao.insert(BakeRecordEntity(recipeId = recipeId, recipeName = recipeName, portions = portions))
    }

    /** «Старое фото» (Cycle 6): вклеить фотокарточку в уже созданную запись формуляра. */
    suspend fun attachPhoto(recordId: Long, path: String) = withContext(Dispatchers.IO) {
        dao.attachPhoto(recordId, path)
    }
}
