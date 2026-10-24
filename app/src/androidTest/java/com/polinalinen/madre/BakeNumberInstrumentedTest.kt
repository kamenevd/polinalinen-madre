package com.polinalinen.madre

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.viewmodel.BakingViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real CI Android/Room lifecycle only; never run on a family handset. */
class BakeNumberInstrumentedTest {
    @Test fun numberSurvivesPauseFinaleAndControllerRecreationUntilExplicitWrite()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<MadreApplication>()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val store=ViewModelStore()
        lateinit var vm:BakingViewModel
        fun create() { instrumentation.runOnMainSync{vm=BakingViewModel(app);store.put("number-test",vm)} }
        create()
        val recipe=app.recipeRepository.getRecipes().first()
        val id=vm.startBaking(recipe,2.0)!!
        val number=vm.session(id)!!.bakeNumber
        try {
            assertTrue(number in 1..9)
            vm.togglePause(id).join();assertEquals(number,vm.session(id)!!.bakeNumber)
            vm.togglePause(id).join()
            repeat(recipe.timeline.size){vm.advanceStep(id).join();assertEquals(number,vm.session(id)!!.bakeNumber)}
            withTimeout(10_000){vm.completions.value[id]!!.recordId.await()}
            instrumentation.runOnMainSync{store.clear()}
            create()
            withTimeout(10_000){while(!vm.restoreReady.value)delay(10)}
            assertEquals(number,vm.completions.value[id]!!.bakeNumber)
            assertNotEquals(number,vm.previewBakeNumber())
            vm.finish(id,ShelfShareDecision.KEEP)!!.join()
            assertEquals(0,app.bakeNumbers.number(id))
            assertTrue(app.bakeNumbers.freed().getValue(number)<=BakingClock.wallClock())
        } finally {instrumentation.runOnMainSync{store.clear()}}
    }
}
