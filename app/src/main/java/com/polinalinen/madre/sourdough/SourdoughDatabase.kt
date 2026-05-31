package com.polinalinen.madre.sourdough

import androidx.room.*

@Entity(tableName = "feedings")
data class Feeding(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val photoPath: String? = null
)

@Entity(tableName = "sourdough_config")
data class SourdoughConfig(
    @PrimaryKey val id: Int = 1,
    val name: String = "Моя закваска",
    val intervalHours: Int = 72
)

@Dao
interface SourdoughDao {
    @Query("SELECT * FROM feedings ORDER BY timestamp DESC")
    suspend fun getAllFeedings(): List<Feeding>

    @Query("SELECT * FROM feedings ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentFeedings(limit: Int): List<Feeding>

    @Query("SELECT * FROM feedings WHERE photoPath IS NOT NULL ORDER BY timestamp DESC")
    suspend fun getFeedingsWithPhotos(): List<Feeding>

    @Insert
    suspend fun insertFeeding(feeding: Feeding): Long

    @Update
    suspend fun updateFeeding(feeding: Feeding)

    @Query("DELETE FROM feedings WHERE id = :id")
    suspend fun deleteFeeding(id: Int)

    @Query("SELECT * FROM sourdough_config WHERE id = 1")
    suspend fun getConfig(): SourdoughConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: SourdoughConfig)

    @Query("SELECT * FROM feedings ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestFeeding(): Feeding?
}

@Database(entities = [Feeding::class, SourdoughConfig::class], version = 1, exportSchema = false)
abstract class SourdoughDatabase : RoomDatabase() {
    abstract fun sourdoughDao(): SourdoughDao
}
