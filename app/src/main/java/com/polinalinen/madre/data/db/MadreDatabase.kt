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
import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
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

    // Cycle 11: «Кормить» и «Напоминания» в колофоне наконец пишут в Room, а не
    // в UI-стейт экрана. Точечные UPDATE — по той же причине, что и выше.
    // Колонки существуют с первой версии схемы (SourdoughConfigEntity), новых
    // полей нет — миграция не нужна, версия БД остаётся 6.
    @Query("UPDATE sourdough_configs SET intervalHours = :hours WHERE id = :configId")
    suspend fun updateIntervalHours(configId: Long, hours: Int)

    @Query("UPDATE sourdough_configs SET remindersEnabled = :enabled WHERE id = :configId")
    suspend fun updateRemindersEnabled(configId: Long, enabled: Boolean)

    // Cycle 14: имя закваски наконец редактируется. Колонка name существует с
    // первой версии схемы — новых полей нет, миграция не нужна, версия БД та же.
    @Query("UPDATE sourdough_configs SET name = :name WHERE id = :configId")
    suspend fun updateName(configId: Long, name: String)
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

    // «Старое фото» (Cycle 6, AgedPhoto): точечный UPDATE — фотокарточка
    // вклеивается позже, когда запись формуляра уже создана.
    @Query("UPDATE bake_records SET photoPath = :path WHERE id = :recordId")
    suspend fun attachPhoto(recordId: Long, path: String)
}

/**
 * Cycle 15: незавершённые выпечки. Читается один раз после ребута
 * (notifications/BakeRestoreWorker), пишется на каждом переходе шага — потому
 * suspend, а не Flow: наблюдать за этой таблицей некому, живой отсчёт идёт в
 * BakingViewModel.
 */
@Dao
interface ActiveBakeDao {
    @Query("SELECT * FROM active_bakes")
    suspend fun getAll(): List<ActiveBakeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bake: ActiveBakeEntity)

    @Query("DELETE FROM active_bakes WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: Long)
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

// v5 → v6 (Cycle 6, 25.07.2026): bake_records.photoPath для фичи «Старое фото»
// (AgedPhoto) — nullable, старые выпечки остаются без фотокарточки.
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bake_records` ADD COLUMN `photoPath` TEXT DEFAULT NULL")
    }
}

// v6 → v7 (Cycle 15, 05.08.2026): photoPath перестаёт быть абсолютным путём.
// filesDir у приложения не вечен — он переезжает между /data/data/… и
// /data/user/N/… (второй профиль, рабочий профиль, перенос данных), и после
// такого переезда абсолютный путь указывает в никуда: книга теряет
// фотокарточки целыми страницами. Схема не меняется, меняются только данные.
//
// SQLite не умеет REVERSE, поэтому «отрезать всё до имени файла» приходится
// через якорь. Якорь — папка снимка, а не префикс filesDir: захардкоженный
// /data/user/0/… промахнётся мимо второго профиля (/data/user/10/…), а
// '/bake_photos/' стоит в пути всегда, потому что этот каталог назначает
// PhotoStore.PhotoKind.
//
// Строки, которые якорь не поймал (чужая раскладка, ручная правка), остаются
// абсолютными — и это нормально: PhotoStore.resolve читает такие по-старому.
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("feedings" to "feeding_photos", "bake_records" to "bake_photos")
            .forEach { (table, dir) ->
                val anchor = "/$dir/"
                db.execSQL(
                    "UPDATE `$table` SET `photoPath` = '$dir/' || " +
                        "SUBSTR(`photoPath`, INSTR(`photoPath`, '$anchor') + ${anchor.length}) " +
                        "WHERE `photoPath` IS NOT NULL AND INSTR(`photoPath`, '$anchor') > 0"
                )
            }
    }
}

// v7 → v8 (Cycle 15, 05.08.2026): новая таблица active_bakes — незавершённые
// выпечки, которые BootReceiver восстанавливает после перезагрузки телефона.
// Только CREATE: старые данные не трогаются, на устройствах без активной
// выпечки таблица просто остаётся пустой.
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `active_bakes` (" +
                "`sessionId` INTEGER NOT NULL, " +
                "`recipeId` TEXT NOT NULL, " +
                "`recipeName` TEXT NOT NULL, " +
                "`stepTitle` TEXT NOT NULL, " +
                "`stepIndex` INTEGER NOT NULL, " +
                "`stepDurationMinutes` INTEGER NOT NULL, " +
                "`isWaitStep` INTEGER NOT NULL, " +
                "`startedAtWallClock` INTEGER NOT NULL, " +
                "`pausedAtWallClock` INTEGER, " +
                "PRIMARY KEY(`sessionId`))"
        )
    }
}

/**
 * Cycle 11: «Пометы на полях» и «Конверт на будущее» убраны из приложения, но
 * margin_notes и sealed_notes ОСТАЮТСЯ объявленными сущностями — намеренно.
 * Убрать их из [entities] значит поменять identity hash схемы, и Room откажется
 * открывать уже лежащую на телефонах madre.db. Таблицы никуда не деваются и не
 * дропаются: разрушительной миграции здесь быть не должно, версия остаётся 6.
 * Читать и писать их теперь нечем — DAO удалены вместе с фичами.
 */
@Database(
    entities = [
        UserEntity::class,
        SourdoughConfigEntity::class,
        FeedingEntity::class,
        BakeRecordEntity::class,
        ActiveBakeEntity::class,
        MarginNoteEntity::class,
        SealedNoteEntity::class,
        FamilySettingEntity::class,
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MadreDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sourdoughConfigDao(): SourdoughConfigDao
    abstract fun feedingDao(): FeedingDao
    abstract fun bakeRecordDao(): BakeRecordDao
    abstract fun activeBakeDao(): ActiveBakeDao
    abstract fun familySettingDao(): FamilySettingDao

    companion object {
        /**
         * Единственный список миграций — его же берёт androidTest/MigrationTest.
         * Если миграцию написать, но забыть здесь зарегистрировать, тест
         * упадёт вместе с приложением, а не «пройдёт» на своей копии списка.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8,
        )

        // Room создаётся один раз через Application (см. MadreApplication.kt),
        // а не через lazy-singleton в ViewModel — закрывает баг v3 #1
        // (db.close() в onCleared() → crash при повторном входе).
        fun build(context: Context): MadreDatabase =
            Room.databaseBuilder(context.applicationContext, MadreDatabase::class.java, "madre.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
