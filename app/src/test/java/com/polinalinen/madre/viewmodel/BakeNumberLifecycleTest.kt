package com.polinalinen.madre.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.*
import com.polinalinen.madre.navigation.*
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.viewmodel.fakes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=MadreApplication::class)
class BakeNumberLifecycleTest {
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val dispatcher=UnconfinedTestDispatcher()
    private val history=FakeBakeHistory()
    private val ledger=FakeBakeSessionLedger()
    private val controllers=mutableListOf<BakingViewModel>()
    private fun controller()=BakingViewModel(app,history,FakeShelfSync(),ledger).also{controllers.add(it)}
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
    }
    private suspend fun close(vm:BakingViewModel) {
        val job=vm.viewModelScope.coroutineContext[Job]
        ViewModelStore().apply{put("bake",vm);clear()}
        withTimeout(10_000){while(job?.isCompleted==false){dispatcher.scheduler.runCurrent();delay(1)}}
        controllers.remove(vm)
    }
    @After fun cleanup() {
        runBlocking {controllers.toList().forEach{close(it)}}
        Dispatchers.resetMain()
    }
    private val clock get()=Clock.fixed(Instant.ofEpochMilli(System.currentTimeMillis()),ZoneId.of("Europe/Moscow"))
    @Test fun concurrentStartAndPlanShareNineSlotsAndRejectTenthWithoutWriting()=runBlocking {
        val vm=controller();val recipe=sampleRecipe()
        coroutineScope {
            (1..9).map { index -> async {
                if(index%2==0)vm.plannedBakes.save(PlannedBake(recipe.id,2,clock.millis()+index*60_000L),clock)
                else vm.startBaking(recipe,2.0)
            } }.awaitAll()
        }
        val plans=BookPreferences(app).use{it.snapshotPlans()}
        val active=app.activeBakeRepository.all()
        assertEquals(9,active.size+plans.size)
        assertEquals((1..9).toSet(),(active.map{it.bakeNumber}+plans.map{it.number}).toSet())
        assertNull(vm.startBaking(recipe,2.0));assertEquals(BakeNumbers.LIMIT_MESSAGE,vm.mutationError.value)
        assertEquals(9,app.activeBakeRepository.all().size+BookPreferences(app).use{it.snapshotPlans().size})
    }
    @Test fun plannedNumberSurvivesMoveStartAndRepeatedStart()=runBlocking {
        val vm=controller();val recipe=app.recipeRepository.getRecipes().first()
        vm.startBaking(recipe,2.0)
        vm.plannedBakes.save(PlannedBake(recipe.id,2,clock.millis()+60_000L),clock)
        val original=BookPreferences(app).use{it.snapshotPlans().single()}
        assertEquals(2,original.number)
        val moved=vm.plannedBakes.snooze(original.id,clock,expected=original)!!
        assertEquals(2,moved.number)
        val id=vm.plannedBakes.start(moved.id,expected=moved)!!
        assertEquals(2,vm.session(id)!!.bakeNumber)
        assertEquals(id,vm.plannedBakes.start(moved.id))
        assertTrue(BookPreferences(app).use{it.snapshotPlans().isEmpty()})
        assertEquals(2,app.activeBakeRepository.all().size)
    }
    @Test fun numberHeldThroughPauseFinaleAndRestartThenReleasedOnWrite()=runBlocking {
        var vm=controller();val recipe=sampleRecipe()
        val id=vm.startBaking(recipe,2.0)!!
        vm.togglePause(id).join();assertEquals(1,vm.session(id)!!.bakeNumber)
        vm.togglePause(id).join();vm.advanceStep(id).join()
        assertTrue(vm.session(id)!!.isCompleted);assertEquals(1,vm.completions.value[id]!!.bakeNumber)
        assertEquals(2,vm.previewBakeNumber())
        close(vm);vm=controller()
        withTimeout(10_000){while(!vm.restoreReady.value){dispatcher.scheduler.runCurrent();delay(1)}}
        assertEquals(1,vm.completions.value[id]!!.bakeNumber)
        assertEquals(2,vm.previewBakeNumber())
        vm.finish(id,ShelfShareDecision.KEEP)!!.join()
        assertEquals(0,app.bakeNumbers.number(id))
        assertEquals(2,vm.previewBakeNumber()) // number1 softly quarantined, not immediately reused.
        assertEquals(1,BakeNumbers.allocate(emptyList(),app.bakeNumbers.freed(),System.currentTimeMillis()+BakeNumbers.COOLDOWN_MILLIS))
    }
    @Test fun abandonDoesNotReleaseBeforeEightSecondsAndUndoKeepsNumber()=runBlocking {
        val vm=controller();val id=vm.startBaking(sampleRecipe(),2.0)!!
        vm.abandonWithUndo(id).join();val token=vm.undo.value!!
        dispatcher.scheduler.advanceTimeBy(7999);dispatcher.scheduler.runCurrent()
        assertEquals(1,app.bakeNumbers.number(id));assertEquals(2,vm.previewBakeNumber())
        vm.undo(token.token).join();assertEquals(1,vm.session(id)!!.bakeNumber)
        vm.abandonWithUndo(id).join()
        dispatcher.scheduler.advanceTimeBy(8000);dispatcher.scheduler.runCurrent()
        withTimeout(10_000){while(vm.session(id)!=null)delay(1)}
        assertEquals(0,app.bakeNumbers.number(id))
    }
    @Test fun cancelPlanReleasesItsNumberAndRestoredLegacyPlansFollowActiveNumbers()=runBlocking {
        val recipe=app.recipeRepository.getRecipes().first()
        val first=controller();first.startBaking(recipe,2.0)
        BookPreferences(app).use{it.putPlan(PlannedBake(recipe.id,2,clock.millis()+60_000L))}
        close(first)
        val vm=controller()
        withTimeout(10_000){while(!vm.restoreReady.value){dispatcher.scheduler.runCurrent();delay(1)}}
        val plan=BookPreferences(app).use{it.snapshotPlans().single()}
        assertEquals(2,plan.number)
        vm.plannedBakes.remove(plan.id)
        assertTrue(BookPreferences(app).use{it.snapshotPlans().isEmpty()})
        assertEquals(3,vm.previewBakeNumber())
    }
}
