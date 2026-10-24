package com.polinalinen.madre.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** Run before Room's first connection: checkpoint WAL, copy, then verify SQLite and SHA-256.
 * A failed backup stops the upgrade rather than silently risking the user's book.
 */
internal object LegacyDatabaseBackup {
    @Synchronized fun beforeNumberMigration(context: Context) {
        val source = context.getDatabasePath("madre.db")
        if (!source.isFile) return
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (db.version != 11) return
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { result ->
                check(result.moveToFirst() && result.getInt(0) == 0) { "Database backup checkpoint is busy" }
            }
            val dir = File(context.noBackupFilesDir, "room-11-before-12").apply { check(isDirectory || mkdirs()) }
            val target = File(dir, "madre-${System.currentTimeMillis()}.db")
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            fun digest(file: File): String = file.inputStream().use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                hash.digest().joinToString("") { "%02x".format(it) }
            }
            val hash = digest(source)
            check(hash == digest(target)) { "Database backup checksum differs" }
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                copy.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
                check(copy.version == 11)
            }
            File(dir, "${target.name}.sha256").writeText("$hash  ${target.name}\n")
            // Keep the preferences paired with this database without logging their contents.
            val prefs = File(context.applicationInfo.dataDir, "shared_prefs/madre_prefs.xml")
            if (prefs.isFile) prefs.copyTo(File(dir, "${target.name}.prefs.xml"))
        }
    }
}
