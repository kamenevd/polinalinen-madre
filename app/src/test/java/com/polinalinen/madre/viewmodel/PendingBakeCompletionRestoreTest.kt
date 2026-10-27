package com.polinalinen.madre.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.utils.PrefsBakeSessionLedger
import com.polinalinen.madre.viewmodel.fakes.FakeShelfSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real private ledger and Room records, not a family database or a mocked completion. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=MadreApplication::class)
class PendingBakeCompletionRestoreTest {
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val dispatcher=UnconfinedTestDispatcher()
    private var controller:BakingViewModel?=null
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        runCatching{WorkManager.initialize(app,Configuration.Builder().build())}
    }
    @After fun cleanup() {
        val job=controller?.viewModelScope?.coroutineContext?.get(Job)
        controller?.let{ViewModelStore().apply{put("bake",it);clear()}}
        runBlocking{withTimeout(10_000){while(job?.isCompleted==false){dispatcher.scheduler.runCurrent();delay(1)}}}
        Dispatchers.resetMain()
    }
    @Test fun coldHomeRestoresUndecidedFinaleAndNewStartCannotOverwriteItsPointer()=runBlocking {
        val recipe=app.recipeRepository.getRecipes().first()
        val history=app.bakeHistoryRepository
        val before=history.observeAll().first().size
        val recordedAt=1_700_086_680_000L
        val recordId=history.record(recipe.id,recipe.name,2,recordedAt)
        val ledger=PrefsBakeSessionLedger(app.getSharedPreferences("madre_prefs",0))
        assertTrue(ledger.remember(41L,recordId))
        val sync=FakeShelfSync()
        val vm=BakingViewModel(app,history,sync,ledger)
        controller=vm
        withTimeout(10_000){while(!vm.restoreReady.value){dispatcher.scheduler.runCurrent();delay(1)}}
        val completion=vm.completions.value[41L]!!
        assertEquals(recordId,completion.recordId.await())
        assertEquals(recordedAt,completion.completedAtMillis)
        assertEquals(recipe.id,completion.recipeId)
        assertNull(vm.session(41L))
        assertTrue(app.activeBakeRepository.all().isEmpty())
        val newSession=vm.startBaking(recipe,2.0)!!
        assertTrue(newSession>41L)
        assertEquals(recordId,ledger.recordIdFor(41L))
        assertSame(completion,vm.completions.value[41L])
        vm.finish(41L,ShelfShareDecision.KEEP)!!.join()
        assertFalse(vm.completions.value.containsKey(41L))
        assertNull(ledger.recordIdFor(41L))
        assertEquals(before+1,history.observeAll().first().size)
        assertNotNull(vm.session(newSession))
        assertTrue(sync.calls.isEmpty())
    }
}
