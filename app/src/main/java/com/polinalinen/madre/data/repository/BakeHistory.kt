package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import kotlinx.coroutines.flow.Flow

/** Seam for baking completion flow and tests. */
interface BakeHistory {
    fun observeAll(): Flow<List<BakeRecordEntity>>
    suspend fun record(
        recipeId: String,
        recipeName: String,
        portions: Int,
        completedAtMillis: Long,
    ): Long
    suspend fun attachPhoto(recordId: Long, path: String)
    suspend fun getCompletedAt(recordId: Long): Long?
    suspend fun get(recordId: Long): BakeRecordEntity?
}
