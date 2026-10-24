package com.polinalinen.madre.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.model.BookDisplaySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A separate synthetic Room file, closed and reopened; never the family's live database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class BookDisplaySettingsRepositoryTest {
    @Test fun existingKeyValueTablePersistsBothSettingsWithoutChangingTheBookName() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MadreApplication>()
        val name = "synthetic-book-display-policy.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, MadreDatabase::class.java, name).build()
        var database = open()
        try {
            val first = FamilySettingsRepository(database)
            assertEquals(BookDisplaySettings(true, false), first.observeBookDisplaySettings().first())
            first.setFamilyName("Синтетическая подпись")
            first.setKeepScreenOn(false)
            first.setLockScreenProgress(true)
            database.close(); database = open()
            val cold = FamilySettingsRepository(database)
            assertEquals(BookDisplaySettings(false, true), cold.observeBookDisplaySettings().first())
            assertEquals("Синтетическая подпись", cold.observeFamilyName().first())
            cold.setLockScreenProgress(false)
            assertEquals(BookDisplaySettings(false, false), cold.observeBookDisplaySettings().first())
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
