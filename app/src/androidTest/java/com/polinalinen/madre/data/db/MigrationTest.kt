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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
