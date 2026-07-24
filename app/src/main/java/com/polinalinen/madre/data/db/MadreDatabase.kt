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
import com.polinalinen.madre.data.db.entities.FamilySettingEntity
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.MarginNoteEntity
import com.polinalinen.madre.data.db.entities.SealedNoteEntity
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

@Dao
interface SealedNoteDao {
    @Query("SELECT * FROM sealed_notes WHERE recipeId = :recipeId ORDER BY createdAtMillis ASC")
    fun observeForRecipe(recipeId: String): Flow<List<SealedNoteEntity>>

    @Insert
    suspend fun insert(note: SealedNoteEntity): Long

    @Query("UPDATE sealed_notes SET unlockedAtMillis = :millis WHERE id = :noteId")
    suspend fun markUnlocked(noteId: Long, millis: Long)
}

@Dao
interface FamilySettingDao {
    @Query("SELECT * FROM family_settings WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<FamilySettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: FamilySettingEntity)
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

// v2 → v3 (Cycle 2, 24.07.2026): margin_notes.userId для фичи «Голоса семьи»
// (FamilyHand) — nullable, старые записи остаются без автора (fallback на recipeId).
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `margin_notes` ADD COLUMN `userId` INTEGER DEFAULT NULL")
    }
}

// v3 → v4 (Cycle 2, 24.07.2026): новая таблица sealed_notes для фичи «Конверт
// на будущее» (TimeCapsule).
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sealed_notes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipeId` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`unlockAfterBakes` INTEGER NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, " +
                "`unlockedAtMillis` INTEGER)"
        )
    }
}

// v4 → v5 (Cycle 3, 25.07.2026): новая key-value таблица family_settings для
// фичи «Экслибрис» (Bookplate) — пока хранит только family_name.
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `family_settings` (" +
                "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"
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
        SealedNoteEntity::class,
        FamilySettingEntity::class,
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MadreDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sourdoughConfigDao(): SourdoughConfigDao
    abstract fun feedingDao(): FeedingDao
    abstract fun bakeRecordDao(): BakeRecordDao
    abstract fun marginNoteDao(): MarginNoteDao
    abstract fun sealedNoteDao(): SealedNoteDao
    abstract fun familySettingDao(): FamilySettingDao

    companion object {
        // Room создаётся один раз через Application (см. MadreApplication.kt),
        // а не через lazy-singleton в ViewModel — закрывает баг v3 #1
        // (db.close() в onCleared() → crash при повторном входе).
        fun build(context: Context): MadreDatabase =
            Room.databaseBuilder(context.applicationContext, MadreDatabase::class.java, "madre.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
