package com.polinalinen.madre.sourdough

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.*
import com.polinalinen.madre.data.repository.*
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MadreApplication::class)
class DiaryDeletionRoomTest {
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var db: MadreDatabase
    private lateinit var repo: SourdoughRepository
    private lateinit var config: SourdoughConfigEntity
    private lateinit var book: DiaryBook
    private lateinit var controller: DiaryDeletion
    private lateinit var jpeg: File
    private lateinit var bytes: ByteArray
    private val scheduler = TestCoroutineScheduler()
    private val scope = TestScope(StandardTestDispatcher(scheduler))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(app, MadreDatabase::class.java).allowMainThreadQueries().build()
        repo = SourdoughRepository(db)
        val userId = runBlocking { db.userDao().upsert(UserEntity(name = "D2")) }
        val seed = SourdoughConfigEntity(userId = userId, name = "Соня", intervalHours = 48, remindersEnabled = false)
        config = seed.copy(id = runBlocking { db.sourdoughConfigDao().upsert(seed) })
        book = DiaryBook(userId, config.id)
        controller = DiaryDeletion(scope, repo::deleteDiaryFeeding, { scheduler.currentTime })
        controller.selectBook(book)
        jpeg = File(app.filesDir, "feeding_photos/d2-real.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        bytes = jpeg.readBytes()
    }
    @After fun cleanup() { controller.leave(); scope.cancel(); db.close(); jpeg.delete() }
    private fun insert(time: Long = 2_000): FeedingEntity = runBlocking {
        val value = FeedingEntity(sourdoughConfigId = config.id, timestampMillis = time,
            retainedStarterGrams = 47, flourGrams = 113, waterGrams = 59, storageLocation = StorageLocation.FRIDGE,
            notes = "Моя заметка", photoPath = "feeding_photos/d2-real.jpg", hydrationPercent = 61,
            starterObservation = "Наблюдение", observedAtMillis = 123, finalHydrationPercent = 67, generatedComment = "Снимок комментария")
        val id = db.feedingDao().insert(value)
        db.sourdoughConfigDao().updateLastFeeding(config.id, time)
        value.copy(id = id)
    }
    private fun tick(millis: Long) { scheduler.advanceTimeBy(millis); scheduler.runCurrent() }
    private fun settle() {
        val deadline = System.nanoTime() + 10_000_000_000
        while (controller.state.value.pending != null && System.nanoTime() < deadline) {
            scheduler.runCurrent(); Thread.sleep(5)
        }
        scheduler.runCurrent()
        assertNull("bounded actual IO completion", controller.state.value.pending)
    }
    private fun row(value: FeedingEntity) = db.feedingDao().getNow(value.id)
    private fun configNow() = db.sourdoughConfigDao().getNow(config.id)!!
    @Test fun rowAndReferencedJpegRemainAt4999AndUndoPreservesEveryFieldAndId() {
        val value = insert(); assertTrue(controller.queue(value)); val token = controller.state.value.pending!!.token
        tick(4_999)
        assertEquals(value, row(value)); assertEquals(value.timestampMillis, configNow().lastFeedingMillis)
        PhotoStore.sweepOrphans(app, runBlocking { db.feedingDao().allPhotoPaths().toSet() })
        assertArrayEquals(bytes, jpeg.readBytes())
        assertTrue(controller.undo(token)); tick(50_000)
        assertEquals(value, row(value)); assertArrayEquals(bytes, jpeg.readBytes()); assertNull(controller.state.value.error)
        assertFalse(controller.undo(token))
    }
    @Test fun exact5000DeletesOnlySelectedLastAndUsesIdOrderDespiteTimestampRollback() {
        val first = insert(9_000); val second = insert(1_000); val last = insert(2_000)
        assertTrue(controller.queue(last)); val token = controller.state.value.pending!!.token
        tick(4_999); assertEquals(last, row(last)); tick(1); settle()
        assertNull(row(last)); assertEquals(first, row(first)); assertEquals(second, row(second))
        assertEquals(config.copy(lastFeedingMillis = 1_000), configNow())
        assertFalse(controller.undo(token)); assertArrayEquals(bytes, jpeg.readBytes())
    }
    @Test fun olderDeleteDoesNotChangeNewestFactsAndOnlyRowLeavesNullableLast() {
        val older = insert(); val newest = insert(1_000)
        assertTrue(controller.queue(older)); tick(5_000); settle()
        assertEquals(newest, row(newest)); assertEquals(config.copy(lastFeedingMillis = 1_000), configNow())
        assertTrue(controller.queue(newest)); tick(5_000); settle()
        assertNull(row(newest)); assertEquals(config, configNow()); assertArrayEquals(bytes, jpeg.readBytes())
    }
    @Test fun duplicateAndSecondDeleteCannotShortenOrReplaceFirstWindowAndLateUndoCannotRevive() {
        val first = insert(); val second = insert(3_000)
        assertTrue(controller.queue(first)); val pending = controller.state.value.pending!!
        tick(3_000); assertFalse(controller.queue(first)); assertFalse(controller.queue(second))
        assertEquals(pending, controller.state.value.pending)
        tick(1_999); assertEquals(first, row(first)); tick(1)
        assertFalse(controller.undo(pending.token)); settle()
        assertNull(row(first)); assertEquals(second, row(second)); assertFalse(controller.undo(pending.token))
    }
    @Test fun actualRoomConfigAndExactSnapshotAreRevalidatedBeforeDelete() {
        val value = insert()
        assertFalse(repo.deleteDiaryFeeding(book.copy(userId = book.userId + 1), value))
        assertFalse(repo.deleteDiaryFeeding(book.copy(configId = book.configId + 1), value))
        assertFalse(repo.deleteDiaryFeeding(book, value.copy(notes = "Устаревшая копия")))
        assertEquals(value, row(value)); assertArrayEquals(bytes, jpeg.readBytes())
    }
    @Test fun ownerABACancelsOldWorkAndRejectsStaleCallbackAndUndoTokens() {
        val value = insert(); val authority = controller.state.value.authority
        assertTrue(controller.queue(value, authority)); val old = controller.state.value.pending!!.token
        controller.selectBook(book.copy(userId = book.userId + 1)); controller.selectBook(book)
        assertFalse(controller.queue(value, authority)); assertFalse(controller.undo(old)); tick(5_000)
        assertEquals(value, row(value)); assertTrue(controller.queue(value)); val current = controller.state.value.pending!!.token
        assertFalse(controller.undo(old)); assertTrue(controller.undo(current)); tick(5_000); assertEquals(value, row(value))
    }
    @Test fun exitAndNewVmAfterScopeDeathLeaveNoHiddenDestructiveWork() {
        val value = insert(); assertTrue(controller.queue(value)); controller.leave(); tick(5_000)
        assertEquals(value, row(value))
        assertTrue(controller.queue(value)); val token = controller.state.value.pending!!.token
        scope.cancel(); tick(20_000); assertEquals(value, row(value))
        val newScope = TestScope(StandardTestDispatcher(scheduler))
        val replacement = DiaryDeletion(newScope, repo::deleteDiaryFeeding, { scheduler.currentTime })
        replacement.selectBook(book); assertNull(replacement.state.value.pending); assertFalse(replacement.undo(token))
        assertTrue(replacement.queue(value)); val newToken = replacement.state.value.pending!!.token
        assertNotEquals(token, newToken); assertFalse(replacement.undo(token)); assertTrue(replacement.undo(newToken))
        tick(20_000); assertEquals(value, row(value)); newScope.cancel()
    }
    @Test fun realSqliteFailureRollsBackRowAndTimestampRetainsJpegAndAllowsRetry() {
        val value = insert()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER d2_full BEFORE UPDATE ON sourdough_configs BEGIN SELECT RAISE(ABORT, 'disk full fixture'); END")
        assertTrue(controller.queue(value)); tick(5_000); settle()
        assertNotNull(controller.state.value.error); assertEquals(value, row(value)); assertEquals(value.timestampMillis, configNow().lastFeedingMillis)
        assertArrayEquals(bytes, jpeg.readBytes())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER d2_full")
        assertTrue(controller.queue(value)); tick(5_000); settle()
        assertNull(controller.state.value.error); assertNull(row(value)); assertEquals(config, configNow())
    }
    @Test fun concurrentNewFeedTransactionWinsLatestWithoutChangingItsSavedHydrationOrComment() = runBlocking {
        val value = insert(); val inserted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val concurrent = SourdoughRepository(db, onPostInsert = { inserted.complete(Unit); release.await() })
        val job = async(Dispatchers.IO) { concurrent.saveFeeding(SourdoughFeedingSaveRequest(config.id, 48, 500,
            51, 101, 53, StorageLocation.KITCHEN, "Новое", null, null)) }
        withTimeout(10_000) { inserted.await() }
        assertTrue(controller.queue(value)); tick(5_000)
        release.complete(Unit)
        val saved = withTimeout(10_000) { job.await() }; settle()
        assertNull(row(value)); assertEquals(saved.feeding, row(saved.feeding)); assertEquals(500L, configNow().lastFeedingMillis)
        assertNotNull(saved.feeding.generatedComment); assertNotNull(saved.feeding.finalHydrationPercent)
        assertEquals(config.copy(lastFeedingMillis = 500), configNow())
    }
    @Test fun ownerChangeWhileRoomWaitsForConcurrentFeedCancelsBeforeActualDelete() = runBlocking {
        val value = insert(); val inserted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val concurrent = SourdoughRepository(db, onPostInsert = { inserted.complete(Unit); release.await() })
        val job = async(Dispatchers.IO) { concurrent.saveFeeding(SourdoughFeedingSaveRequest(config.id, 48, 700,
            51, 101, 53, StorageLocation.KITCHEN, "Новое", null, null)) }
        withTimeout(10_000) { inserted.await() }
        assertTrue(controller.queue(value)); tick(5_000)
        controller.selectBook(book.copy(userId = book.userId + 1)); controller.selectBook(book)
        release.complete(Unit); val saved = withTimeout(10_000) { job.await() }
        tick(10_000)
        assertEquals(value, row(value)); assertEquals(saved.feeding, row(saved.feeding))
        assertEquals(config.copy(lastFeedingMillis = 700), configNow()); assertNull(controller.state.value.pending)
    }
}
