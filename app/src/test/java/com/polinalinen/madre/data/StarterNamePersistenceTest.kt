package com.polinalinen.madre.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.repository.SourdoughRepository
import com.polinalinen.madre.sourdough.StarterName
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cycle 14: имя закваски обязано пережить перезапуск.
 *
 * Поэтому база здесь НЕ in-memory: настоящий файл на диске, который закрывают
 * и открывают заново — ровно то, что делает с книгой выключенный телефон.
 * In-memory база проверила бы только, что setName вернул управление.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StarterNamePersistenceTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var db: MadreDatabase? = null

    private fun openBook(): SourdoughRepository {
        val opened = MadreDatabase.build(context)
        db = opened
        return SourdoughRepository(opened)
    }

    private fun closeBook() {
        db?.close()
        db = null
    }

    @After
    fun tearDown() {
        closeBook()
        context.deleteDatabase("madre.db")
    }

    @Test
    fun `a starter is born with the name the book has always used`() = runTest {
        val repository = openBook()
        assertThat(repository.getOrCreateDefaultConfig().name).isEqualTo(StarterName.DEFAULT)
    }

    @Test
    fun `a renamed starter still answers to the new name after a restart`() = runTest {
        val first = openBook()
        val config = first.getOrCreateDefaultConfig()
        first.setName(config.id, "Соня")
        closeBook()

        // Телефон выключили и включили: книга открывается заново, с нуля.
        val afterRestart = openBook()
        assertThat(afterRestart.getOrCreateDefaultConfig().name).isEqualTo("Соня")
    }

    @Test
    fun `a blank rename does not leave the starter nameless`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()
        repository.setName(config.id, "   ")
        assertThat(repository.getOrCreateDefaultConfig().name).isEqualTo(StarterName.DEFAULT)
    }

    @Test
    fun `renaming touches the name and nothing else about the starter`() = runTest {
        val repository = openBook()
        val config = repository.getOrCreateDefaultConfig()
        repository.setIntervalHours(config.id, 48)
        repository.setRemindersEnabled(config.id, false)
        repository.setName(config.id, "Соня")

        val after = repository.getOrCreateDefaultConfig()
        assertThat(after.name).isEqualTo("Соня")
        assertThat(after.intervalHours).isEqualTo(48)
        assertThat(after.remindersEnabled).isFalse()
    }
}
