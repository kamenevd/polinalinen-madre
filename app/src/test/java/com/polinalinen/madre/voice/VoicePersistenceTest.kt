package com.polinalinen.madre.voice

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity
import com.polinalinen.madre.data.repository.ActiveBakeRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class VoicePersistenceTest {
    private inline fun <T> MadreDatabase.use(block: (MadreDatabase) -> T): T = try { block(this) } finally { close() }
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MadreDatabase::class.java).build()
    private fun row() = ActiveBakeEntity(1, "bread", "Хлеб", "Замес", 0, 10, false, 1000,
        scaleFactor = 3.0, bakeKey = "bake", recipeRevision = "digest")
    private fun receipt() = VoiceCommandReceiptEntity("cmd", "fingerprint", "bake", 0, "APPLIED", 1, 9999999999999L, "PAUSE", "bread")

    @Test fun stateAndReceiptCommitTogetherAndDuplicateDoesNotMutate() = runBlocking {
        db().use { d ->
            val r = ActiveBakeRepository(d); val old = row(); val next = old.copy(revision = 1, pausedAtWallClock = 1100)
            r.commit(1, null, old)
            assertEquals(receipt(), r.commit(1, old, next, receipt()))
            assertEquals(receipt(), r.commit(1, old, next, receipt()))
            assertEquals(1L, r.all().single().revision)
            assertEquals(3.0, r.all().single().scaleFactor)
            assertEquals(receipt(), ActiveBakeRepository(d).receipt("cmd"))
            assertTrue(runCatching { r.commit(1, next, null, receipt().copy(fingerprint = "other")) }.isFailure)
            assertEquals(next, r.all().single())
        }
    }
    @Test fun detachedBeforeTransactionCommitsNeitherRowNorReceipt() = runBlocking {
        db().use { d ->
            val r = ActiveBakeRepository(d); val old = row(); r.commit(1, null, old)
            assertTrue(runCatching { r.commit(1, old, old.copy(revision = 1), receipt()) { false } }.isFailure)
            assertEquals(old, r.all().single()); assertNull(r.receipt("cmd"))
        }
    }
    @Test fun twoConcurrentTransitionsOnlyOneCommits() = runBlocking {
        db().use { d ->
            val r = ActiveBakeRepository(d); val old = row(); r.commit(1, null, old)
            val results = (1..2).map { n -> async(Dispatchers.Default) {
                runCatching { r.commit(1, old, old.copy(revision = 1), receipt().copy(commandId = "cmd$n")) }.isSuccess
            } }.awaitAll()
            assertEquals(1, results.count { it }); assertEquals(1L, r.all().single().revision)
        }
    }
    @Test fun legacyIdentityAssignedOnceScaleAndRecipeRemainUnknown() = runBlocking {
        db().use { d ->
            val r = ActiveBakeRepository(d)
            r.commit(1, null, row().copy(scaleFactor = null, bakeKey = null, recipeRevision = null))
            val first = r.initializeLegacyKeys().single()
            assertNotNull(first.bakeKey); assertNull(first.scaleFactor); assertNull(first.recipeRevision)
            assertEquals(first, ActiveBakeRepository(d).initializeLegacyKeys().single())
        }
    }
    @Test fun cancellationEffectOnceEvenIfEffectMarkerCommitInterrupted() = runBlocking {
        db().use { d ->
            val r = ActiveBakeRepository(d); val old = row(); r.commit(1, null, old)
            r.commit(1, old, null, receipt().copy(operation = "CANCEL"))
            var effects = 0; var durableMarker = false
            r.applyPendingCancellationEffects { if (!durableMarker) { effects++; durableMarker = true }; false }
            ActiveBakeRepository(d).applyPendingCancellationEffects { if (!durableMarker) effects++; true }
            r.applyPendingCancellationEffects { effects++; true }
            assertEquals(1, effects); assertTrue(r.all().isEmpty())
        }
    }
}
