package com.polinalinen.madre.data.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.polinalinen.madre.MadreApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=MadreApplication::class)
class BakeNumberMigrationTest {
    private val context get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private fun legacy(name:String,count:Int=3) {
        context.deleteDatabase(name)
        val schemaFile=listOf(File("schemas/com.polinalinen.madre.data.db.MadreDatabase/11.json"),File("app/schemas/com.polinalinen.madre.data.db.MadreDatabase/11.json")).first{it.isFile}
        val schema=JsonParser.parseString(schemaFile.readText()).asJsonObject.getAsJsonObject("database")
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object:SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db:SupportSQLiteDatabase) {
                    schema.getAsJsonArray("entities").forEach { entity ->
                        val e=entity.asJsonObject;val table=e.get("tableName").asString
                        db.execSQL(e.get("createSql").asString.replace("\${TABLE_NAME}",table))
                        e.getAsJsonArray("indices").forEach{db.execSQL(it.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}",table))}
                    }
                    schema.getAsJsonArray("setupQueries").forEach{db.execSQL(it.asString)}
                    // Tied clocks must use sessionId, never SQLite insertion order.
                    (count downTo 1).forEach { n -> db.execSQL("INSERT INTO active_bakes(sessionId,recipeId,recipeName,stepTitle,stepIndex,stepDurationMinutes,isWaitStep,startedAtWallClock,pausedAtWallClock,scaleFactor,bakeKey,revision,recipeRevision) VALUES(?, 'ciabatta','Чиабатта','Отдых',4,30,1,?,1200,NULL,?,7,'digest')",arrayOf<Any>(n,if(n==1)2000 else 1000,"key-$n")) }
                    db.execSQL("INSERT INTO family_settings VALUES('kept','семья')")
                    db.execSQL("INSERT INTO bake_records(id,recipeId,recipeName,portions,completedAtMillis,photoPath) VALUES(90,'ciabatta','Чиабатта',2,999,'bake_photos/kept.jpg')")
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
            }).build())
        helper.writableDatabase;helper.close()
    }
    @Test fun realRoomValidatesTwelveAndPreservesBookPauseUnknownScaleAndIdentity() {
        val name="numbers-migration.db";legacy(name)
        val room=Room.databaseBuilder(context,MadreDatabase::class.java,name).addMigrations(*MadreDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val db=room.openHelper.writableDatabase
            assertEquals(12,db.version)
            db.query("SELECT sessionId,bake_number,pausedAtWallClock,scaleFactor,bakeKey,revision,recipeRevision FROM active_bakes ORDER BY bake_number").use {
                listOf(2L,3L,1L).forEachIndexed { index,id -> assertTrue(it.moveToNext());assertEquals(id,it.getLong(0));assertEquals(index+1,it.getInt(1));assertEquals(1200L,it.getLong(2));assertTrue(it.isNull(3));assertEquals("key-$id",it.getString(4));assertEquals(7L,it.getLong(5));assertEquals("digest",it.getString(6)) }
                assertFalse(it.moveToNext())
            }
            db.query("SELECT photoPath FROM bake_records WHERE id=90").use{assertTrue(it.moveToFirst());assertEquals("bake_photos/kept.jpg",it.getString(0))}
            db.query("SELECT value FROM family_settings WHERE `key`='kept'").use{assertTrue(it.moveToFirst());assertEquals("семья",it.getString(0))}
        } finally {room.close();context.deleteDatabase(name)}
    }
    @Test fun overflowDoesNotDeleteLegacyBakesOrAssignAnOutOfRangeNumber() {
        val name="numbers-overflow.db";legacy(name,12)
        val room=Room.databaseBuilder(context,MadreDatabase::class.java,name).addMigrations(*MadreDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try { room.openHelper.writableDatabase.query("SELECT COUNT(*),COUNT(DISTINCT NULLIF(bake_number,0)),SUM(CASE WHEN bake_number=0 THEN 1 ELSE 0 END),MAX(bake_number) FROM active_bakes").use{
            assertTrue(it.moveToFirst());assertEquals(12,it.getInt(0));assertEquals(9,it.getInt(1));assertEquals(3,it.getInt(2));assertEquals(9,it.getInt(3))
        } } finally {room.close();context.deleteDatabase(name)}
    }
    @Test fun backupIsVerifiedBeforeRoomUpgradeAndRemainsReadableAsVersionEleven() {
        legacy("madre.db")
        LegacyDatabaseBackup.beforeNumberMigration(context)
        val dir=File(context.noBackupFilesDir,"room-11-before-12")
        val backup=dir.listFiles()!!.filter{it.extension=="db"}.maxBy{it.lastModified()}
        assertTrue(File(dir,"${backup.name}.sha256").isFile)
        android.database.sqlite.SQLiteDatabase.openDatabase(backup.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(11,it.version)
            it.rawQuery("SELECT COUNT(*) FROM active_bakes",null).use{rows->assertTrue(rows.moveToFirst());assertEquals(3,rows.getInt(0))}
        }
        context.deleteDatabase("madre.db")
    }
}
