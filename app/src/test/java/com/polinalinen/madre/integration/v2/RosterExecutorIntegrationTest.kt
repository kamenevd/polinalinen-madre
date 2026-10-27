package com.polinalinen.madre.integration.v2

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.navigation.*
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.fakes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

/** Real controller + Room/SQLite, not a policy-only executor imitation. No phone/Station claim. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RosterExecutorIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private val history = FakeBakeHistory()
    private lateinit var vm: BakingViewModel
    private val dispatcher=UnconfinedTestDispatcher()
    private val connection = UUID.randomUUID().toString()
    @Before fun setup() = runBlocking {
        Dispatchers.setMain(dispatcher)
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        vm = BakingViewModel(app, history, FakeShelfSync(), FakeBakeSessionLedger(), rosterEnabled={true})
        withTimeout(15000) { vm.restoreReady.first { it } }; Unit
    }
    @After fun cleanup() = runBlocking {
        val owner=vm.viewModelScope.coroutineContext[Job]!!
        androidx.lifecycle.ViewModelStore().apply { put("bake", vm); clear() }
        // Cancellation is not completion: drain real IO -> Main/timer children before sandbox teardown.
        withTimeout(15000) {
            while(!owner.isCompleted) {
                dispatcher.scheduler.runCurrent()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                delay(1)
            }
        }
        assertTrue(owner.isCompleted)
        Dispatchers.resetMain()
    }
    private suspend fun start() = requireNotNull(vm.startBaking(vm.recipes.value.first(), 2.0))
    private fun command(id: Long, op: RosterOperation = RosterOperation.PAUSE): RosterCommand {
        val s = requireNotNull(vm.session(id)); val now = BakingClock.wallClock()
        return RosterCommand(UUID.randomUUID().toString(), connection, s.bakeKey, s.bakeNumber, s.revision,
            vm.voiceRecipeRevision(), op, now, now + 4000, UUID.randomUUID().toString(), s.currentStepIndex, s.isPaused)
    }
    private suspend fun apply(c: RosterCommand, authority: () -> Boolean = { true }) = vm.executeRoster(c, c.issuedAt, BakingClock.elapsed(), authority)

    @Test fun realPauseCommitsRowRevisionCommandAndLeaseAndReopensDedup() = runBlocking {
        val id = start(); val c = command(id)
        val receipt = requireNotNull(apply(c))
        assertEquals("APPLIED", receipt.outcome)
        val row = app.activeBakeRepository.all().single()
        assertNotNull(row.pausedAtWallClock); assertEquals(1L, row.revision)
        assertEquals(receipt, app.activeBakeRepository.receipt(c.id))
        assertEquals(c.fingerprint(), app.activeBakeRepository.receipt("roster-lease:${c.leaseId}")!!.fingerprint)
        assertEquals(receipt, apply(c)); assertEquals(1L, vm.session(id)!!.revision)
        assertNull(apply(c.copy(operation = RosterOperation.CANCEL)))
        val reusedLease = command(id, RosterOperation.RESUME).copy(leaseId = c.leaseId)
        assertNull(apply(reusedLease)); assertNull(app.activeBakeRepository.receipt(reusedLease.id))
        assertTrue(vm.session(id)!!.isPaused); assertTrue(history.recordCalls.isEmpty())
    }
    @Test fun localAndRemoteRaceHasOneRevisionWinner() = runBlocking {
        val id = start(); val c = command(id)
        val local = vm.togglePause(id)
        val remote = async { apply(c) }
        local.join(); val receipt = requireNotNull(remote.await())
        assertEquals("STALE", receipt.outcome); assertEquals(1L, app.activeBakeRepository.all().single().revision)
    }
    @Test fun actualPersistedNumberAndScaleDriftCannotBeOverwrittenFromUiCache() = runBlocking {
        val id = start(); val c = command(id)
        val original = app.activeBakeRepository.all().single()
        val changed = original.copy(bakeNumber = 9, scaleFactor = null)
        app.activeBakeRepository.remember(changed)
        assertEquals("STALE", apply(c)!!.outcome)
        assertEquals(changed, app.activeBakeRepository.all().single()); assertFalse(vm.session(id)!!.isPaused)
    }
    @Test fun logoutAtSqlFenceHasNoReceiptLeaseOrMutation() = runBlocking {
        val id = start(); val c = command(id); var checks = 0
        assertNull(apply(c) { ++checks < 3 })
        assertEquals(0L, app.activeBakeRepository.all().single().revision)
        assertNull(app.activeBakeRepository.receipt(c.id)); assertNull(app.activeBakeRepository.receipt("roster-lease:${c.leaseId}"))
    }
    @Test fun leaseExpiryAtFinalNativeSqlFenceRollsBackStateRevisionAndBothReceipts() = runBlocking {
        val id=start();val c=command(id);var calls=0
        val before=app.activeBakeRepository.all().single()
        val result=apply(c) {
            calls++
            if(calls==5)org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
            true
        }
        assertTrue(calls>=5);assertNull(result)
        assertEquals(before,app.activeBakeRepository.all().single())
        assertNull(app.activeBakeRepository.receipt(c.id));assertNull(app.activeBakeRepository.receipt("roster-lease:${c.leaseId}"))
        assertFalse(vm.session(id)!!.isPaused)
    }
    @Test fun twoRealConcurrentCommandsHaveOneAppliedRevisionAndOneStaleReceipt() = runBlocking {
        val id=start();val a=command(id);val b=command(id)
        val outcomes=listOf(async{apply(a)},async{apply(b)}).awaitAll().map{it!!.outcome}.sorted()
        assertEquals(listOf("APPLIED","STALE"),outcomes)
        assertEquals(1L,app.activeBakeRepository.all().single().revision)
        assertNotNull(app.activeBakeRepository.receipt(a.id));assertNotNull(app.activeBakeRepository.receipt(b.id))
    }
    @Test fun expiredLeaseHasDurableNegativeOutcomeWithoutStateChange() = runBlocking {
        val id = start(); val fresh = command(id)
        val old = fresh.copy(issuedAt = fresh.issuedAt - 5000, expiresAt = fresh.expiresAt - 5000)
        assertEquals("EXPIRED", apply(old)!!.outcome)
        assertEquals(0L, app.activeBakeRepository.all().single().revision); assertFalse(vm.session(id)!!.isPaused)
    }
    @Test fun sqlReceiptInsertFailureRollsBackStateAndLeaseAndPublishesNothing() = runBlocking {
        val id = start(); val c = command(id)
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_fail_receipt BEFORE INSERT ON voice_command_receipts BEGIN SELECT RAISE(ABORT, 'fixture disk failure'); END")
        try {
            assertNull(apply(c)); assertEquals(0L, app.activeBakeRepository.all().single().revision)
            assertNull(app.activeBakeRepository.receipt(c.id)); assertNull(app.activeBakeRepository.receipt("roster-lease:${c.leaseId}"))
            assertFalse(vm.session(id)!!.isPaused)
        } finally { app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_fail_receipt") }
        assertEquals("APPLIED", apply(c)!!.outcome)
    }
    @Test fun expiryDuringFinalSqlBoundaryRollsBackAllThreeWrites() = runBlocking {
        val id = start(); val c = command(id); var reads = 0
        // Same public repository transaction used by executor, final validity fence fails AFTER writes.
        val row = app.activeBakeRepository.all().single()
        val receipt = com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity(c.id,c.fingerprint(),c.bakeKey,0,"APPLIED",1,c.expiresAt,"PAUSE",row.recipeId)
        assertTrue(runCatching { app.activeBakeRepository.commitRoster(row,row.copy(revision=1),receipt,c.leaseId) { ++reads == 1 } }.isFailure)
        assertEquals(row, app.activeBakeRepository.all().single()); assertNull(app.activeBakeRepository.receipt(c.id))
        assertNull(app.activeBakeRepository.receipt("roster-lease:${c.leaseId}"))
    }
    @Test fun cancelledKeyAndRecycledNumberNeverRetargetNewBake() = runBlocking {
        val id = start(); val old = command(id)
        val cancel = old.copy(operation = RosterOperation.CANCEL)
        assertEquals("APPLIED", apply(cancel)!!.outcome); assertNull(vm.session(id))
        val next = start(); val row = app.activeBakeRepository.all().single()
        app.activeBakeRepository.remember(row.copy(bakeNumber = old.bakeNumber))
        val stale = old.copy(id = UUID.randomUUID().toString(), leaseId = UUID.randomUUID().toString())
        assertEquals("STALE", apply(stale)!!.outcome)
        assertFalse(vm.session(next)!!.isPaused); assertEquals(0L, app.activeBakeRepository.all().single().revision)
        assertNull(apply(cancel.copy(connectionId = UUID.randomUUID().toString())))
        assertTrue(history.recordCalls.isEmpty())
    }
    @Test fun finalAdvanceCannotCompletePhotoPublishOrWriteHistory() = runBlocking {
        val id = start()
        while (!vm.session(id)!!.isLastStep) vm.advanceStep(id).join()
        val c = command(id, RosterOperation.ADVANCE)
        assertEquals("PHONE_REQUIRED", apply(c)!!.outcome)
        assertFalse(vm.session(id)!!.isCompleted); assertTrue(vm.completions.value.isEmpty())
        assertTrue(history.recordCalls.isEmpty())
    }
    @Test fun persistedRosterIncludesEmptyRunningPausedPlanAndFinaleWithStableIdentity() = runBlocking {
        assertTrue(vm.persistedRoster(connection,0).bakes.isEmpty())
        val a = start(); val b = start(); vm.togglePause(b).join()
        val recipe = vm.recipes.value.first()
        val plan = PlannedBake(recipe.id, 2, BakingClock.wallClock()+3600000, 3)
        BookPreferences(app).use { assertTrue(it.putPlan(plan)) }
        app.bakeNumbers.rememberFinale(BakeNumberStore.Finale(900, recipe.id, recipe.name,2,BakingClock.wallClock(),4,
            bakeKey=UUID.randomUUID().toString(),revision=12,recipeRevision=vm.voiceRecipeRevision()))
        val first = vm.persistedRoster(connection,1)
        assertEquals(listOf(RosterBakeState.RUNNING,RosterBakeState.PAUSED,RosterBakeState.SCHEDULED,RosterBakeState.FINALE),first.bakes.map{it.state})
        assertEquals(first.bakes.map{it.bakeKey},vm.persistedRoster(connection,2).bakes.map{it.bakeKey})
        assertTrue(first.bakes.take(2).all{it.controlReady && it.portions==2})
        assertTrue(first.bakes.drop(2).all{!it.controlReady && it.portions==null && it.quantities==null})
        assertEquals(vm.session(a)!!.bakeKey,first.bakes.first().bakeKey)
    }
    @Test fun planStartAndFinaleKeepKeyNumberAndMonotonicRevisionAcrossRealStores() = runBlocking {
        val recipe=vm.recipes.value.first();val clock=java.time.Clock.systemUTC()
        val plan=PlannedBake(recipe.id,2,clock.millis()+3600000)
        vm.plannedBakes.save(plan,clock)
        val scheduled=vm.persistedRoster(connection,1).bakes.single()
        val id=vm.plannedBakes.start(plan.id)!!
        val running=vm.persistedRoster(connection,2).bakes.single()
        assertEquals(scheduled.bakeKey,running.bakeKey);assertEquals(scheduled.bakeNumber,running.bakeNumber)
        assertTrue(running.revision>scheduled.revision)
        while(!vm.session(id)!!.isCompleted) vm.advanceStep(id).join()
        val finale=vm.persistedRoster(connection,3).bakes.single()
        assertEquals(running.bakeKey,finale.bakeKey);assertEquals(running.bakeNumber,finale.bakeNumber)
        assertTrue(finale.revision>running.revision);assertEquals(RosterBakeState.FINALE,finale.state)
        assertFalse(finale.controlReady)
    }
    @Test fun nativeFileDatabaseReopenPreservesCommandAndConsumedLease() = runBlocking {
        val id=start();val c=command(id);val receipt=apply(c)!!
        val reopened=com.polinalinen.madre.data.db.MadreDatabase.build(app)
        try {
            val repository=com.polinalinen.madre.data.repository.ActiveBakeRepository(reopened)
            assertEquals(receipt,repository.receipt(c.id))
            assertEquals(c.fingerprint(),repository.receipt("roster-lease:${c.leaseId}")!!.fingerprint)
            assertEquals(1L,repository.all().single().revision)
        } finally {reopened.close()}
    }
    @Test fun realSqliteFullDuringReceiptInsertRollsBackStateAndLease() = runBlocking {
        val id=start();val c=command(id);val row=app.activeBakeRepository.all().single()
        // Single native connection: max_page_count is connection-local in SQLite;
        // a WAL read-pool PRAGMA would NOT constrain the production writer.
        val full=androidx.room.Room.databaseBuilder(app,com.polinalinen.madre.data.db.MadreDatabase::class.java,"fixture-full")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        val repository=com.polinalinen.madre.data.repository.ActiveBakeRepository(full)
        try {
            repository.remember(row)
            val db=full.openHelper.writableDatabase
            val count=db.query("PRAGMA page_count").use {it.moveToFirst();it.getLong(0)}
            db.query("PRAGMA max_page_count=$count").use {it.moveToFirst();assertEquals(count,it.getLong(0))}
            val receipt=com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity(c.id,"x".repeat(2000000),c.bakeKey,0,"APPLIED",1,c.expiresAt,"PAUSE",row.recipeId)
            val error=runCatching{repository.commitRoster(row,row.copy(revision=1),receipt,c.leaseId){true}}.exceptionOrNull()
            assertTrue("Expected native SQLITE_FULL, got $error",generateSequence(error){it.cause}.any{it is android.database.sqlite.SQLiteFullException})
            assertEquals(row,repository.all().single());assertNull(repository.receipt(c.id))
            assertNull(repository.receipt("roster-lease:${c.leaseId}"))
        } finally {full.close()}
    }
    @Test fun malformedPlansAndFinalesDoNotBecomeEmptyReplacementOrRewritePrefs() = runBlocking {
        start(); val prefs = app.getSharedPreferences("madre_prefs",0)
        prefs.edit().putString("planned_bakes","not-json").commit()
        val before = prefs.all.toMap()
        assertTrue(runCatching{vm.persistedRoster(connection,1)}.isFailure); assertEquals(before,prefs.all)
        prefs.edit().putString("planned_bakes","[]").putString("bake_number_finale_33","broken").commit()
        assertTrue(runCatching{vm.persistedRoster(connection,2)}.isFailure)
    }
    @Test fun actualNinePersistedRowsHaveUniqueStableNumbersAndTenthCannotCreateRow() = runBlocking {
        val ids=(1..9).map{start()}
        val roster=vm.persistedRoster(connection,1)
        assertEquals((1..9).toList(),roster.bakes.map{it.bakeNumber})
        assertEquals(9,roster.bakes.map{it.bakeKey}.distinct().size)
        assertNull(vm.startBaking(vm.recipes.value.first(),1.0))
        assertEquals(9,app.activeBakeRepository.all().size)
        assertEquals(ids.map{vm.session(it)!!.bakeKey},vm.persistedRoster(connection,2).bakes.map{it.bakeKey})
    }
    @Test fun unknownRecipeRevisionOrUntrustedPortionsNeverSendQuantitiesOrControl() = runBlocking {
        start(); val row=app.activeBakeRepository.all().single()
        app.activeBakeRepository.remember(row.copy(recipeRevision="old-recipe",scaleFactor=null))
        val bake=vm.persistedRoster(connection,1).bakes.single()
        assertFalse(bake.controlReady); assertNull(bake.portions); assertNull(bake.stepText); assertNull(bake.quantities)
    }
}
