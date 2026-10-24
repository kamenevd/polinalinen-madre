package com.polinalinen.madre.voice

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.MIGRATION_10_11
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class VoiceMigrationTest {
    @Test fun realRoomOpensMigratedVersionTenWithLegacyData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "voice-migration-10-11.db"
        context.deleteDatabase(name)
        val schemaFile = listOf(java.io.File("schemas/com.polinalinen.madre.data.db.MadreDatabase/10.json"),
            java.io.File("app/schemas/com.polinalinen.madre.data.db.MadreDatabase/10.json")).first { it.isFile }
        val schema = com.google.gson.JsonParser.parseString(schemaFile.readText()).asJsonObject.getAsJsonObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in schema.getAsJsonArray("entities")) {
                        val e = entity.asJsonObject
                        val table = e.get("tableName").asString
                        db.execSQL(e.get("createSql").asString.replace("\${TABLE_NAME}", table))
                        for (index in e.getAsJsonArray("indices") ?: com.google.gson.JsonArray()) db.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
                    }
                    for (sql in schema.getAsJsonArray("setupQueries")) db.execSQL(sql.asString)
                    db.execSQL("INSERT INTO active_bakes VALUES (7,'bread','Хлеб','Замес',1,10,1,1000,1200)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        val room = androidx.room.Room.databaseBuilder(context, com.polinalinen.madre.data.db.MadreDatabase::class.java, name)
            .addMigrations(*com.polinalinen.madre.data.db.MadreDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.writableDatabase // Runs Room's full schema validation, not just SQL.
            assertEquals(12, db.version)
            db.query("SELECT scaleFactor,recipeRevision,pausedAtWallClock FROM active_bakes WHERE sessionId=7").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1)); assertEquals(1200L,it.getLong(2))
            }
        } finally { room.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationPreservesLegacySnapshotAndDoesNotInventPortions() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE active_bakes (sessionId INTEGER NOT NULL PRIMARY KEY,recipeId TEXT NOT NULL,recipeName TEXT NOT NULL,stepTitle TEXT NOT NULL,stepIndex INTEGER NOT NULL,stepDurationMinutes INTEGER NOT NULL,isWaitStep INTEGER NOT NULL,startedAtWallClock INTEGER NOT NULL,pausedAtWallClock INTEGER)")
                    db.execSQL("INSERT INTO active_bakes VALUES (7,'bread','Хлеб','Замес',1,10,1,1000,1200)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.use {
            val db = it.writableDatabase
            MIGRATION_10_11.migrate(db)
            db.query("SELECT * FROM active_bakes").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(7, c.getInt(c.getColumnIndexOrThrow("sessionId")))
                assertEquals("Хлеб", c.getString(c.getColumnIndexOrThrow("recipeName")))
                assertEquals(1200, c.getInt(c.getColumnIndexOrThrow("pausedAtWallClock")))
                for (name in listOf("scaleFactor", "recipeRevision", "bakeKey")) assertTrue(c.isNull(c.getColumnIndexOrThrow(name)))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("revision")))
            }
            db.query("SELECT COUNT(*) FROM voice_command_receipts").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        }
    }
}
