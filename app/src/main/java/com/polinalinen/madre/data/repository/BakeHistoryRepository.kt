package com.polinalinen.madre.data.repository

import android.content.Context
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BakeHistoryRepository(
    private val context: Context,
    db: MadreDatabase,
) : BakeHistory {
    private val dao = db.bakeRecordDao()

    override fun observeAll(): Flow<List<BakeRecordEntity>> = dao.observeAll()

    override suspend fun record(
        recipeId: String,
        recipeName: String,
        portions: Int,
        completedAtMillis: Long,
    ): Long = withContext(Dispatchers.IO) {
        dao.insert(
            BakeRecordEntity(
                recipeId = recipeId,
                recipeName = recipeName,
                portions = portions,
                completedAtMillis = completedAtMillis,
            )
        )
    }

    /** «Старое фото» (Cycle 6): вклеить фотокарточку; прежний JPEG убираем. */
    override suspend fun attachPhoto(recordId: Long, path: String) = withContext(Dispatchers.IO) {
        val previous = dao.photoPath(recordId)
        dao.attachPhoto(recordId, path)
        PhotoStore.deleteIfUnreferenced(context, previous) { it == path }
    }

    override suspend fun getCompletedAt(recordId: Long): Long? = withContext(Dispatchers.IO) {
        dao.getCompletedAt(recordId)
    }

    override suspend fun get(recordId: Long): BakeRecordEntity? = withContext(Dispatchers.IO) {
        dao.get(recordId)
    }
}
