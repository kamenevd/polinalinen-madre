package com.polinalinen.madre.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.data.db.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity)
}

@Dao
interface SourdoughConfigDao {
    @Query("SELECT * FROM sourdough_configs WHERE userId = :userId LIMIT 1")
    fun observeForUser(userId: Long): Flow<SourdoughConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: SourdoughConfigEntity): Long

    @Update
    suspend fun update(config: SourdoughConfigEntity)
}

@Dao
interface FeedingDao {
    @Query("SELECT * FROM feedings WHERE sourdoughConfigId = :configId ORDER BY timestampMillis DESC")
    fun observeHistory(configId: Long): Flow<List<FeedingEntity>>

    @Query("SELECT * FROM feedings WHERE sourdoughConfigId = :configId ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun getLast(configId: Long): FeedingEntity?

    @Insert
    suspend fun insert(feeding: FeedingEntity): Long

    @Delete
    suspend fun delete(feeding: FeedingEntity)
}

@Dao
interface BakeRecordDao {
    @Query("SELECT * FROM bake_records ORDER BY completedAtMillis DESC")
    fun observeAll(): Flow<List<BakeRecordEntity>>

    @Insert
    suspend fun insert(record: BakeRecordEntity): Long
}

class Converters {
    @TypeConverter
    fun fromStorageLocation(value: StorageLocation): String = value.name

    @TypeConverter
    fun toStorageLocation(value: String): StorageLocation = StorageLocation.valueOf(value)
}

@Database(
    entities = [UserEntity::class, SourdoughConfigEntity::class, FeedingEntity::class, BakeRecordEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MadreDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sourdoughConfigDao(): SourdoughConfigDao
    abstract fun feedingDao(): FeedingDao
    abstract fun bakeRecordDao(): BakeRecordDao

    companion object {
        // Room создаётся один раз через Application (см. MadreApplication.kt),
        // а не через lazy-singleton в ViewModel — закрывает баг v3 #1
        // (db.close() в onCleared() → crash при повторном входе).
        fun build(context: Context): MadreDatabase =
            Room.databaseBuilder(context.applicationContext, MadreDatabase::class.java, "madre.db")
                .build()
    }
}
