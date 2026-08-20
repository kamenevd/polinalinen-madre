package com.polinalinen.madre.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * DESIGN-V4.md: madre.db на телефонах несёт настоящую историю кормлений и
 * выпечек, поэтому fallbackToDestructiveMigration здесь запрещён — потерять
 * книгу семьи нельзя. Значит, каждая миграция обязана быть проверена на
 * настоящей SQLite, а не «прочитана глазами».
 *
 * История схем (app/schemas, файлы 1.json…6.json) восстановлена в Cycle 15 из коммитов, где
 * версия БД поднималась; сходимость проверена по identityHash версии 6.
 *
 * Тесты инструментальные: им нужна SQLite устройства, на JVM их не запустить.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MadreDatabase::class.java,
    )

    /** Читает одно значение и закрывает курсор — иначе Room ругается на утечку. */
    private fun SupportSQLiteDatabase.queryLong(sql: String): Long =
        query(sql).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.queryNullableString(sql: String): String? =
        query(sql).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.columnsOf(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun SupportSQLiteDatabase.tableNames(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    @Test
    fun migrate_8_to_9_preservesFeedingAndPhoto() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL("INSERT INTO sourdough_configs (id,userId,name,intervalHours,remindersEnabled,lastFeedingMillis) VALUES (1,1,'Мадре',72,1,1700000000000)")
            db.execSQL("INSERT INTO feedings (id,sourdoughConfigId,timestampMillis,flourGrams,waterGrams,storageLocation,notes,photoPath) VALUES (9,1,1700000000000,50,25,'FRIDGE','note','feeding_photos/kept.jpg')")
            db.execSQL("INSERT INTO feedings (id,sourdoughConfigId,timestampMillis,flourGrams,waterGrams,storageLocation,notes,photoPath) VALUES (10,1,1700000001000,60,30,'KITCHEN',NULL,NULL)")
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, *MadreDatabase.MIGRATIONS)
        assertThat(db.queryLong("SELECT COUNT(*) FROM feedings WHERE id=9")).isEqualTo(1)
        assertThat(db.queryLong("SELECT COUNT(*) FROM feedings")).isEqualTo(2)
        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id=9")).isEqualTo("feeding_photos/kept.jpg")
        assertThat(db.queryNullableString("SELECT hydrationPercent FROM feedings WHERE id=9")).isNull()
        assertThat(db.queryNullableString("SELECT starterObservation FROM feedings WHERE id=9")).isNull()
        assertThat(db.queryNullableString("SELECT notes FROM feedings WHERE id=10")).isNull()
        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id=10")).isNull()
        assertThat(db.queryNullableString("SELECT hydrationPercent FROM feedings WHERE id=10")).isNull()
        assertThat(db.tableNames()).containsAtLeast("margin_notes", "sealed_notes", "active_bakes")
        db.close()
    }

    /**
     * v9 → v10 (Cycle 26): кормление становится расчётом — к feedings
     * добавляются оставленная закваска, посчитанная гидратация и снимок
     * «Комментария закваски».
     *
     * Главное здесь — что миграция НИЧЕГО не переписывает: legacy-гидратация,
     * наблюдение, время наблюдения, заметка и путь к фотокарточке обязаны
     * доехать буква в букву, а новые колонки у старых записей — остаться NULL.
     * Задним числом посчитать гидратацию за те кормления нельзя: масс закваски
     * в них никто не называл.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_9_to_10_keepsLegacyFactsAndAddsEmptyColumns() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                "INSERT INTO sourdough_configs " +
                    "(id,userId,name,intervalHours,remindersEnabled,lastFeedingMillis) " +
                    "VALUES (1,1,'Мадре',72,1,1700000000000)"
            )
            // Запись с подтверждённой руками гидратацией и наблюдением (v9).
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id,sourdoughConfigId,timestampMillis,flourGrams,waterGrams,storageLocation," +
                    "notes,photoPath,hydrationPercent,starterObservation,observedAtMillis) VALUES " +
                    "(11,1,1700000000000,50,25,'FRIDGE','пахнет яблоками'," +
                    "'feeding_photos/kept.jpg',80,'На пике',1700000000500)"
            )
            // И запись ещё старше — без гидратации вовсе.
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id,sourdoughConfigId,timestampMillis,flourGrams,waterGrams,storageLocation," +
                    "notes,photoPath) VALUES " +
                    "(12,1,1700000001000,60,30,'KITCHEN',NULL,NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, *MadreDatabase.MIGRATIONS)

        assertThat(db.columnsOf("feedings")).containsAtLeast(
            "retainedStarterGrams",
            "finalHydrationPercent",
            "generatedComment",
        )
        // Ни одна запись не потерялась и ни одна не переписана.
        assertThat(db.queryLong("SELECT COUNT(*) FROM feedings")).isEqualTo(2)
        assertThat(db.queryNullableString("SELECT notes FROM feedings WHERE id=11"))
            .isEqualTo("пахнет яблоками")
        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id=11"))
            .isEqualTo("feeding_photos/kept.jpg")
        assertThat(db.queryNullableString("SELECT hydrationPercent FROM feedings WHERE id=11"))
            .isEqualTo("80")
        assertThat(db.queryNullableString("SELECT starterObservation FROM feedings WHERE id=11"))
            .isEqualTo("На пике")
        assertThat(db.queryNullableString("SELECT observedAtMillis FROM feedings WHERE id=11"))
            .isEqualTo("1700000000500")
        // Новые колонки у старых записей пусты — и это единственный честный ответ.
        assertThat(db.queryNullableString("SELECT retainedStarterGrams FROM feedings WHERE id=11")).isNull()
        assertThat(db.queryNullableString("SELECT finalHydrationPercent FROM feedings WHERE id=11")).isNull()
        assertThat(db.queryNullableString("SELECT generatedComment FROM feedings WHERE id=11")).isNull()
        assertThat(db.queryNullableString("SELECT hydrationPercent FROM feedings WHERE id=12")).isNull()
        assertThat(db.queryNullableString("SELECT finalHydrationPercent FROM feedings WHERE id=12")).isNull()
        db.close()
    }

    /** Полная дорога с первой версии до сегодняшней — с фото и заметкой. */
    @Test
    @Throws(IOException::class)
    fun migrate_1_to_10() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO users (id, name, isActive, createdAtMillis) " +
                    "VALUES (1, 'Полина', 1, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO sourdough_configs " +
                    "(id, userId, name, intervalHours, remindersEnabled, lastFeedingMillis) " +
                    "VALUES (1, 1, 'Мадре', 12, 1, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id, sourdoughConfigId, timestampMillis, flourGrams, waterGrams, " +
                    "storageLocation, notes, photoPath) VALUES " +
                    "(1, 1, 1700000000000, 50, 50, 'ROOM', 'первое кормление', " +
                    "'/data/user/0/com.polinalinen.madre/files/feeding_photos/feed_1_11.jpg')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, *MadreDatabase.MIGRATIONS)

        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id = 1"))
            .isEqualTo("feeding_photos/feed_1_11.jpg")
        assertThat(db.queryNullableString("SELECT notes FROM feedings WHERE id = 1"))
            .isEqualTo("первое кормление")
        assertThat(db.queryNullableString("SELECT generatedComment FROM feedings WHERE id = 1")).isNull()
        db.close()
    }

    /**
     * Полная дорога с самой первой версии. Данные кладём настоящие: миграция,
     * которая проходит на пустой БД и роняет заполненную, — обычное дело.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_1_to_6() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO users (id, name, isActive, createdAtMillis) " +
                    "VALUES (1, 'Полина', 1, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO sourdough_configs " +
                    "(id, userId, name, intervalHours, remindersEnabled, lastFeedingMillis) " +
                    "VALUES (1, 1, 'Мадре', 12, 1, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id, sourdoughConfigId, timestampMillis, flourGrams, waterGrams, " +
                    "storageLocation, notes, photoPath) " +
                    "VALUES (1, 1, 1700000000000, 50, 50, 'ROOM', 'первое кормление', NULL)"
            )
            db.execSQL(
                "INSERT INTO bake_records (id, recipeId, recipeName, portions, completedAtMillis) " +
                    "VALUES (1, 'pane', 'Пане', 2, 1700000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, *MadreDatabase.MIGRATIONS)

        // Таблицы всех шести версий на месте.
        assertThat(db.tableNames()).containsAtLeast(
            "users",
            "sourdough_configs",
            "feedings",
            "bake_records",
            "margin_notes",
            "sealed_notes",
            "family_settings",
        )

        // И, главное, история пережила дорогу — а не была пересоздана начисто.
        assertThat(db.queryLong("SELECT COUNT(*) FROM users")).isEqualTo(1)
        assertThat(db.queryLong("SELECT COUNT(*) FROM feedings")).isEqualTo(1)
        assertThat(db.queryLong("SELECT COUNT(*) FROM bake_records")).isEqualTo(1)
        assertThat(db.queryNullableString("SELECT name FROM users WHERE id = 1"))
            .isEqualTo("Полина")
        assertThat(db.queryNullableString("SELECT notes FROM feedings WHERE id = 1"))
            .isEqualTo("первое кормление")
        assertThat(db.queryNullableString("SELECT recipeName FROM bake_records WHERE id = 1"))
            .isEqualTo("Пане")
        db.close()
    }

    /** v5 → v6: «Старое фото» — bake_records.photoPath, nullable для старых выпечек. */
    @Test
    @Throws(IOException::class)
    fun migrate_5_to_6() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO bake_records (id, recipeId, recipeName, portions, completedAtMillis) " +
                    "VALUES (7, 'focaccia', 'Фокачча', 4, 1700000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, *MadreDatabase.MIGRATIONS)

        assertThat(db.columnsOf("bake_records")).contains("photoPath")
        // Выпечка, снятая до Cycle 6, остаётся без фотокарточки, но остаётся.
        assertThat(db.queryLong("SELECT COUNT(*) FROM bake_records")).isEqualTo(1)
        assertThat(db.queryNullableString("SELECT photoPath FROM bake_records WHERE id = 7"))
            .isNull()
        assertThat(db.queryNullableString("SELECT recipeName FROM bake_records WHERE id = 7"))
            .isEqualTo("Фокачча")
        db.close()
    }

    /**
     * v6 → v7: photoPath из абсолютного становится относительным filesDir.
     *
     * Проверяем обе живые раскладки (/data/data/… и /data/user/N/…) и обе
     * таблицы, а заодно — что миграция не трогает то, чего не понимает:
     * непойманная строка обязана остаться как есть, её потом прочитает
     * PhotoStore.resolve по legacy-ветке.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_6_to_7() {
        val foreignPath = "/mnt/sdcard/DCIM/old_bake.jpg"
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO sourdough_configs " +
                    "(id, userId, name, intervalHours, remindersEnabled, lastFeedingMillis) " +
                    "VALUES (1, 1, 'Мадре', 12, 1, 1700000000000)"
            )
            // Кормление на основном профиле…
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id, sourdoughConfigId, timestampMillis, flourGrams, waterGrams, " +
                    "storageLocation, notes, photoPath) VALUES " +
                    "(1, 1, 1700000000000, 50, 50, 'ROOM', NULL, " +
                    "'/data/user/0/com.polinalinen.madre/files/feeding_photos/feed_1_11.jpg')"
            )
            // …и на старой раскладке /data/data.
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id, sourdoughConfigId, timestampMillis, flourGrams, waterGrams, " +
                    "storageLocation, notes, photoPath) VALUES " +
                    "(2, 1, 1700000000000, 50, 50, 'ROOM', NULL, " +
                    "'/data/data/com.polinalinen.madre/files/feeding_photos/feed_2_22.jpg')"
            )
            // Кормление без снимка — его миграция трогать не должна вовсе.
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id, sourdoughConfigId, timestampMillis, flourGrams, waterGrams, " +
                    "storageLocation, notes, photoPath) " +
                    "VALUES (3, 1, 1700000000000, 50, 50, 'ROOM', NULL, NULL)"
            )
            // Второй профиль: именно тот случай, мимо которого промахнулся бы
            // захардкоженный префикс /data/user/0/.
            db.execSQL(
                "INSERT INTO bake_records " +
                    "(id, recipeId, recipeName, portions, completedAtMillis, photoPath) VALUES " +
                    "(1, 'pane', 'Пане', 2, 1700000000000, " +
                    "'/data/user/10/com.polinalinen.madre/files/bake_photos/bake_1_33.jpg')"
            )
            // Путь, который якорь не поймает: остаётся нетронутым.
            db.execSQL(
                "INSERT INTO bake_records " +
                    "(id, recipeId, recipeName, portions, completedAtMillis, photoPath) " +
                    "VALUES (2, 'pane', 'Пане', 2, 1700000000000, '$foreignPath')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, *MadreDatabase.MIGRATIONS)

        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id = 1"))
            .isEqualTo("feeding_photos/feed_1_11.jpg")
        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id = 2"))
            .isEqualTo("feeding_photos/feed_2_22.jpg")
        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id = 3"))
            .isNull()
        assertThat(db.queryNullableString("SELECT photoPath FROM bake_records WHERE id = 1"))
            .isEqualTo("bake_photos/bake_1_33.jpg")
        assertThat(db.queryNullableString("SELECT photoPath FROM bake_records WHERE id = 2"))
            .isEqualTo(foreignPath)

        // Ни одна фотокарточка не потерялась по дороге.
        assertThat(db.queryLong("SELECT COUNT(*) FROM feedings")).isEqualTo(3)
        assertThat(db.queryLong("SELECT COUNT(*) FROM bake_records")).isEqualTo(2)
        db.close()
    }

    /**
     * v7 → v8: новая таблица active_bakes (Cycle 15) — незавершённые выпечки,
     * которые BootReceiver поднимает после перезагрузки телефона. Миграция
     * только создаёт таблицу, и главное здесь — что она ничего не трогает в
     * уже накопленной истории.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_7_to_8() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO bake_records " +
                    "(id, recipeId, recipeName, portions, completedAtMillis, photoPath) " +
                    "VALUES (1, 'pane', 'Пане', 2, 1700000000000, 'bake_photos/bake_1_33.jpg')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, *MadreDatabase.MIGRATIONS)

        assertThat(db.tableNames()).contains("active_bakes")
        assertThat(db.columnsOf("active_bakes")).containsExactly(
            "sessionId",
            "recipeId",
            "recipeName",
            "stepTitle",
            "stepIndex",
            "stepDurationMinutes",
            "isWaitStep",
            "startedAtWallClock",
            "pausedAtWallClock",
        )
        // На телефоне без активной выпечки таблица просто пуста — это не сбой.
        assertThat(db.queryLong("SELECT COUNT(*) FROM active_bakes")).isEqualTo(0)
        // Формуляр не пострадал.
        assertThat(db.queryLong("SELECT COUNT(*) FROM bake_records")).isEqualTo(1)
        assertThat(db.queryNullableString("SELECT photoPath FROM bake_records WHERE id = 1"))
            .isEqualTo("bake_photos/bake_1_33.jpg")
        db.close()
    }

    /** Полная дорога до сегодняшней версии — на ней стоит и migrate_1_to_6. */
    @Test
    @Throws(IOException::class)
    fun migrate_1_to_8() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO users (id, name, isActive, createdAtMillis) " +
                    "VALUES (1, 'Полина', 1, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO sourdough_configs " +
                    "(id, userId, name, intervalHours, remindersEnabled, lastFeedingMillis) " +
                    "VALUES (1, 1, 'Мадре', 12, 1, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO feedings " +
                    "(id, sourdoughConfigId, timestampMillis, flourGrams, waterGrams, " +
                    "storageLocation, notes, photoPath) VALUES " +
                    "(1, 1, 1700000000000, 50, 50, 'ROOM', 'первое кормление', " +
                    "'/data/user/0/com.polinalinen.madre/files/feeding_photos/feed_1_11.jpg')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, *MadreDatabase.MIGRATIONS)

        // Снимок, вклеенный ещё в первой версии книги, открывается и сегодня.
        assertThat(db.queryNullableString("SELECT photoPath FROM feedings WHERE id = 1"))
            .isEqualTo("feeding_photos/feed_1_11.jpg")
        assertThat(db.queryNullableString("SELECT notes FROM feedings WHERE id = 1"))
            .isEqualTo("первое кормление")
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
