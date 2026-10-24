package com.polinalinen.madre.viewmodel

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.*
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.ui.components.CoffeeRing
import com.polinalinen.madre.viewmodel.fakes.*
import com.polinalinen.madre.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=MadreApplication::class)
class BakeNavigationUndoTest {
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val history=FakeBakeHistory()
    private val sync=FakeShelfSync()
    private val mainDispatcher=UnconfinedTestDispatcher()
    private lateinit var vm:BakingViewModel
    @Before fun setup() {
        Dispatchers.setMain(mainDispatcher)
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
        vm=BakingViewModel(app,history,sync,FakeBakeSessionLedger())
    }
    @After fun cleanup() {
        val scope=vm.viewModelScope.coroutineContext[Job]
        androidx.lifecycle.ViewModelStore().apply{put("bake",vm);clear()}
        runBlocking { withTimeout(10_000) {
            while(scope?.isCompleted==false){mainDispatcher.scheduler.runCurrent();delay(1)}
        } }
        Dispatchers.resetMain()
    }
    private fun twoSteps()=sampleRecipe().copy(timeline=listOf(
        TimelineStep(StepType.WAIT,"Опара","Синтетический шаг",10),
        TimelineStep(StepType.ACTION,"Замес","Другой синтетический шаг",20)))

    @Test fun undoStartDeletesActualRoomRowWithoutCancellationMarkOrHistory()=runBlocking {
        val prefs=app.getSharedPreferences("madre_prefs",0)
        val rings=prefs.getInt(CoffeeRing.prefsKey("rye"),0)
        val id=vm.startBakingWithUndo(twoSteps(),2.0)!!
        assertNotNull(app.activeBakeRepository.all().find{it.sessionId==id})
        val token=vm.undo.value!!
        vm.undo(token.token).join();vm.undo(token.token).join()
        assertNull(vm.session(id));assertNull(app.activeBakeRepository.all().find{it.sessionId==id})
        assertEquals(0,vm.cancelledCount.value)
        assertEquals(rings,prefs.getInt(CoffeeRing.prefsKey("rye"),0))
        assertTrue(history.recordCalls.isEmpty());assertTrue(sync.calls.isEmpty())
    }
    @Test fun advanceUndoRestoresRemainingNotFullDurationAndOldVoiceCommandIsStale()=runBlocking {
        val id=vm.startBaking(twoSteps(),2.0)!!
        SystemClock.sleep(17_000)
        val before=vm.session(id)!!
        val remaining=before.remainingSeconds(BakingClock.elapsed())
        assertTrue(remaining < before.currentStep.durationMinutes*60L)
        val generation=UUID.randomUUID().toString();val connection=UUID.randomUUID().toString()
        val issued=BakingClock.wallClock()
        val command=VoiceCommand(UUID.randomUUID().toString(),VoiceOperation.ADVANCE,before.bakeKey,vm.voiceRecipeRevision(),
            generation,connection,before.revision,before.currentStepIndex,before.isPaused,issued,issued+4000)
        vm.advanceStepWithUndo(id).join()
        assertEquals(1,vm.session(id)!!.currentStepIndex)
        val token=vm.undo.value!!
        vm.undo(token.token).join()
        val restored=vm.session(id)!!
        assertEquals(0,restored.currentStepIndex)
        assertEquals(remaining,restored.remainingSeconds(BakingClock.elapsed()))
        assertEquals(before.revision+2,restored.revision)
        val durable=app.activeBakeRepository.all().first{it.sessionId==id}
        assertEquals(restored.revision,durable.revision);assertEquals(0,durable.stepIndex)
        val receipt=vm.applyVoiceCommand(command,BakingClock.elapsed()) {
            VoiceAuthority(true,true,before.bakeKey,generation,connection,vm.voiceRecipeRevision(),issued)
        }!!
        assertEquals("STALE",receipt.outcome)
        assertEquals(restored.revision,vm.session(id)!!.revision)
        assertEquals(receipt,app.activeBakeRepository.receipt(command.id))
        assertTrue(history.recordCalls.isEmpty())
        val work=androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(
            com.polinalinen.madre.notifications.BootRestorePlanner.uniqueWorkName(id)).get()
        assertTrue(work.any{it.state==androidx.work.WorkInfo.State.ENQUEUED})
    }
    @Test fun anotherCommittedMutationInvalidatesAnUndoToken()=runBlocking {
        val id=vm.startBaking(twoSteps(),2.0)!!
        vm.advanceStepWithUndo(id).join();val token=vm.undo.value!!
        vm.togglePause(id).join();val current=vm.session(id)!!
        vm.undo(token.token).join()
        assertEquals(current,vm.session(id));assertNull(vm.undo.value)
    }
    @Test fun explicitFinishWithoutPhotoSharesOnlyTheStatAndOnlyOnce()=runBlocking {
        val id=vm.startBaking(sampleRecipe(),2.0)!!
        vm.advanceStep(id).join()
        vm.finish(id,ShelfShareDecision.PUT_WITHOUT_PHOTO)!!.join()
        vm.finish(id,ShelfShareDecision.PUT_WITHOUT_PHOTO)?.join()
        assertEquals(1,history.recordCalls.size);assertTrue(history.attachCalls.isEmpty())
        assertEquals(1,sync.calls.size);assertNull(sync.calls.single().photoPath)
    }
    @Test fun pausedSnapshotKeepsPauseAndItsExactRemainderWithANewerRevision() {
        val recipe=twoSteps()
        val before=BakingSession(1,recipe,startedAtElapsed=1000,startedAtWallClock=10_000,
            isPaused=true,pausedAtElapsed=5000,scaleFactor=2.0,bakeKey="synthetic",revision=4)
        val current=before.copy(currentStepIndex=1,revision=5,isPaused=false,pausedAtElapsed=null)
        val token=BakeUndo("token",before,5,20_000,BakeUndo.Kind.ADVANCE,596_000,"Вернуть")
        val restored=token.restoredStep(current,9000,19_000)
        assertTrue(restored.isPaused);assertEquals(6L,restored.revision)
        assertEquals(before.remainingSeconds(5000),restored.remainingSeconds(12_000))
        assertEquals(before.bakeKey,restored.bakeKey);assertEquals(before.recipeRevision,restored.recipeRevision)
    }

    @Test fun abandonUndoKeepsTheActualRunningSessionAndNeverTouchesRoomOrCancellationMark()=runBlocking {
        val id=vm.startBaking(twoSteps(),2.0)!!
        val before=vm.session(id)!!
        val snapshot=app.activeBakeRepository.all().single{it.sessionId==id}
        val prefs=app.getSharedPreferences("madre_prefs",0);val rings=prefs.getInt(CoffeeRing.prefsKey("rye"),0)
        vm.abandonWithUndo(id).join();val token=vm.undo.value!!
        assertTrue(vm.pendingAbandons.value.containsKey(id))
        SystemClock.sleep(7000)
        vm.undo(token.token).join()
        assertSame(before,vm.session(id))
        assertTrue(vm.session(id)!!.remainingSeconds(BakingClock.elapsed())<before.currentStep.durationMinutes*60L)
        assertEquals(snapshot,app.activeBakeRepository.all().single{it.sessionId==id})
        assertTrue(vm.pendingAbandons.value.isEmpty());assertEquals(0,vm.cancelledCount.value)
        assertEquals(rings,prefs.getInt(CoffeeRing.prefsKey("rye"),0));assertTrue(history.recordCalls.isEmpty())
    }
    @Test fun abandonCommitsOnlyAfterGraceThenCannotBeReplayed()=runBlocking {
        val id=vm.startBaking(twoSteps(),2.0)!!
        val prefs=app.getSharedPreferences("madre_prefs",0);val key=CoffeeRing.prefsKey("rye");val rings=prefs.getInt(key,0)
        vm.abandonWithUndo(id).join();val token=vm.undo.value!!
        mainDispatcher.scheduler.advanceTimeBy(7999);mainDispatcher.scheduler.runCurrent()
        assertNotNull(vm.session(id));assertEquals(rings,prefs.getInt(key,0))
        SystemClock.sleep(8000)
        mainDispatcher.scheduler.advanceTimeBy(1);mainDispatcher.scheduler.runCurrent()
        withTimeout(10_000){while(vm.cancelledCount.value!=1 || prefs.getInt(key,0)!=rings+1)delay(5)}
        assertNull(vm.session(id));assertNull(app.activeBakeRepository.all().find{it.sessionId==id})
        vm.undo(token.token).join()
        assertEquals(1,vm.cancelledCount.value);assertTrue(history.recordCalls.isEmpty());assertTrue(sync.calls.isEmpty())
    }
    @Test fun plusFiveChangesDurableDeadlineAndNotificationWithoutChangingTheRecipeAsset()=runBlocking {
        val recipe=twoSteps();val id=vm.startBaking(recipe,2.0)!!;val before=vm.session(id)!!
        val now=BakingClock.elapsed();val deadline=before.stepEndsAtElapsed(now)
        vm.adjustTime(id,5).join();val after=vm.session(id)!!
        assertEquals(before.revision+1,after.revision)
        assertEquals(deadline+5*60_000L,after.stepEndsAtElapsed(BakingClock.elapsed()))
        assertEquals(10,recipe.timeline[0].durationMinutes)
        val row=app.activeBakeRepository.all().single{it.sessionId==id}
        assertEquals(after.currentStep.durationMinutes,row.stepDurationMinutes);assertEquals(after.revision,row.revision)
        val work=androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(
            com.polinalinen.madre.notifications.BootRestorePlanner.uniqueWorkName(id)).get()
        assertTrue(work.any{it.state==androidx.work.WorkInfo.State.ENQUEUED})
    }
    @Test fun retainedPlanOwnerConsumesAfterCallerCancellationAndRejectsQueuedSnoozeAndSheetEdit()=runBlocking {
        withTimeout(10_000){while(!vm.restoreReady.value){mainDispatcher.scheduler.runCurrent();delay(1)}}
        val plan=com.polinalinen.madre.navigation.PlannedBake(vm.recipes.value.first().id,2,System.currentTimeMillis()-3_600_000L)
        com.polinalinen.madre.navigation.BookPreferences(app).use{assertTrue(it.putPlan(plan))}
        val owner=vm.plannedBakes
        val locked=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val barrier=Thread{synchronized(app.activeBakeRepository.commitFence){
            locked.countDown();check(release.await(30,java.util.concurrent.TimeUnit.SECONDS))
        }}.apply{start()}
        assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
        val oldComposition=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            oldComposition.async{owner.start(plan.id,expected=plan)}
            withTimeout(10_000){while(!owner.busy.value){mainDispatcher.scheduler.runCurrent();delay(1)}}
            assertNull(vm.sessionForPlan(plan.id))
            oldComposition.cancel()
            assertSame(owner,vm.plannedBakes)
            val clock=java.time.Clock.systemUTC()
            val snooze=async{owner.snooze(plan.id,clock)}
            val replacement=plan.copy(startAtMillis=clock.millis()+7_200_000L)
            val sheetEdit=async{runCatching{owner.save(replacement,clock,replacingId=plan.id,expected=plan)}}
            yield();assertFalse(snooze.isCompleted);assertFalse(sheetEdit.isCompleted)
            release.countDown();barrier.join(10_000)
            assertNull(withTimeout(10_000){snooze.await()})
            assertTrue(withTimeout(10_000){sheetEdit.await()}.isFailure)
            val session=vm.sessionForPlan(plan.id)!!
            assertEquals(session.id,owner.start(plan.id))
            assertNull(owner.snooze(plan.id,clock))
            assertEquals(1,vm.sessions.value.size)
            assertEquals(1,app.activeBakeRepository.all().size)
            com.polinalinen.madre.navigation.BookPreferences(app).use{assertTrue(it.snapshotPlans().isEmpty())}
            assertTrue(history.recordCalls.isEmpty());assertTrue(sync.calls.isEmpty())
        } finally {release.countDown();barrier.join(10_000);oldComposition.cancel()}
    }

    @Test fun plannedStartIsIdempotentAcrossARealControllerRecreation()=runBlocking {
        val recipe=app.recipeRepository.getRecipes().first()
        val planId="${recipe.id}:${System.currentTimeMillis()}"
        val id=vm.startPlannedBake(recipe,2.0,planId)!!
        val first=vm.session(id)!!
        assertEquals(id,vm.startPlannedBake(recipe,2.0,planId))
        val oldScope=vm.viewModelScope.coroutineContext[Job]
        androidx.lifecycle.ViewModelStore().apply{put("bake",vm);clear()}
        withTimeout(10_000){while(oldScope?.isCompleted==false){mainDispatcher.scheduler.runCurrent();delay(1)}}
        vm=BakingViewModel(app,history,sync,FakeBakeSessionLedger())
        assertEquals(id,vm.startPlannedBake(recipe,2.0,planId))
        assertEquals(first.bakeKey,vm.session(id)!!.bakeKey)
        assertEquals(1,app.activeBakeRepository.all().count{it.bakeKey==first.bakeKey})
        assertTrue(history.recordCalls.isEmpty());assertTrue(sync.calls.isEmpty())
    }
}
