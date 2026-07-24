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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.MarginNoteEntity
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

    // Точечный UPDATE вместо read-modify-write всей сущности — не рискуем
    // затереть параллельные изменения name/intervalHours/remindersEnabled
    // устаревшей копией конфига.
    @Query("UPDATE sourdough_configs SET lastFeedingMillis = :millis WHERE id = :configId")
    suspend fun updateLastFeeding(configId: Long, millis: Long)
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

@Dao
interface MarginNoteDao {
    @Query("SELECT * FROM margin_notes WHERE recipeId = :recipeId ORDER BY timestampMillis ASC")
    fun observeForRecipe(recipeId: String): Flow<List<MarginNoteEntity>>

    @Insert
    suspend fun insert(note: MarginNoteEntity): Long
}

class Converters {
    @TypeConverter
    fun fromStorageLocation(value: StorageLocation): String = value.name

    @TypeConverter
    fun toStorageLocation(value: String): StorageLocation = StorageLocation.valueOf(value)
}

// v1 → v2 (Cycle 1, 24.07.2026): новая таблица margin_notes для фичи «Пометы
// на полях». Настоящая миграция, а не fallbackToDestructiveMigration — на
// устройствах уже есть реальная история кормлений/выпечек, терять её нельзя.
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `margin_notes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipeId` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL)"
        )
    }
}

@Database(
    entities = [
        UserEntity::class,
        SourdoughConfigEntity::class,
        FeedingEntity::class,
        BakeRecordEntity::class,
        MarginNoteEntity::class,
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MadreDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sourdoughConfigDao(): SourdoughConfigDao
    abstract fun feedingDao(): FeedingDao
    abstract fun bakeRecordDao(): BakeRecordDao
    abstract fun marginNoteDao(): MarginNoteDao

    companion object {
        // Room создаётся один раз через Application (см. MadreApplication.kt),
        // а не через lazy-singleton в ViewModel — закрывает баг v3 #1
        // (db.close() в onCleared() → crash при повторном входе).
        fun build(context: Context): MadreDatabase =
            Room.databaseBuilder(context.applicationContext, MadreDatabase::class.java, "madre.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
