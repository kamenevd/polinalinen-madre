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
) {
    private val dao = db.bakeRecordDao()

    fun observeAll(): Flow<List<BakeRecordEntity>> = dao.observeAll()

    suspend fun record(recipeId: String, recipeName: String, portions: Int): Long = withContext(Dispatchers.IO) {
        dao.insert(
            BakeRecordEntity(
                recipeId = recipeId,
                recipeName = recipeName,
                portions = portions,
            )
        )
    }

    /** «Старое фото» (Cycle 6): вклеить фотокарточку; прежний JPEG убираем. */
    suspend fun attachPhoto(recordId: Long, path: String) = withContext(Dispatchers.IO) {
        val previous = dao.photoPath(recordId)
        dao.attachPhoto(recordId, path)
        PhotoStore.deleteIfUnreferenced(context, previous) { it == path }
    }
}
