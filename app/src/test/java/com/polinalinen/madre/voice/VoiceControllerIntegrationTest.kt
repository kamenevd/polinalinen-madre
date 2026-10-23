package com.polinalinen.madre.voice

import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.viewmodel.fakes.*
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
@Config(sdk = [35], application = MadreApplication::class)
class VoiceControllerIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private val history = FakeBakeHistory()
    private lateinit var vm: BakingViewModel
    private val generation = UUID.randomUUID().toString()
    private val connection = UUID.randomUUID().toString()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        vm = BakingViewModel(app, history, FakeShelfSync(), FakeBakeSessionLedger())
    }
    @After fun cleanup() {
        androidx.lifecycle.ViewModelStore().apply { put("bake", vm); clear() }
        Dispatchers.resetMain()
    }
    private fun command(id: Long, operation: VoiceOperation): VoiceCommand {
        val s = vm.session(id)!!
        val now = BakingClock.wallClock()
        return VoiceCommand(UUID.randomUUID().toString(), operation, s.bakeKey, vm.voiceRecipeRevision(),
            generation, connection, s.revision, s.currentStepIndex, s.isPaused, now, now + 4_000)
    }
    private suspend fun apply(c: VoiceCommand) = vm.applyVoiceCommand(c, BakingClock.elapsed()) {
        VoiceAuthority(true, true, c.bakeKey, generation, connection, vm.voiceRecipeRevision(), c.issuedAt)
    }
    @Test fun lastStepVoiceStoresPhoneRequiredWithoutHistoryOrCompletion() = runBlocking {
        val id = vm.startBaking(sampleRecipe(), 3.0)!!
        val c = command(id, VoiceOperation.ADVANCE)
        val r = apply(c)!!
        assertEquals("PHONE_REQUIRED", r.outcome)
        assertFalse(vm.session(id)!!.isCompleted)
        assertTrue(history.recordCalls.isEmpty()); assertTrue(vm.completions.value.isEmpty())
        assertEquals(r, app.activeBakeRepository.receipt(c.id))
    }
    @Test fun duplicateCancelHasSingleDurableEffectAndCannotCreateHistory() = runBlocking {
        val id = vm.startBaking(sampleRecipe(), 2.0)!!
        val c = command(id, VoiceOperation.CANCEL)
        val before = app.getSharedPreferences("madre_prefs",0).getInt(com.polinalinen.madre.ui.components.CoffeeRing.prefsKey("rye"),0)
        val first = apply(c)!!; val repeated = apply(c)!!
        assertEquals("APPLIED", first.outcome); assertEquals(first.copy(effectApplied = true), repeated); assertNull(vm.session(id))
        assertEquals(1, vm.cancelledCount.value); assertTrue(history.recordCalls.isEmpty())
        assertEquals(before + 1, app.getSharedPreferences("madre_prefs",0).getInt(com.polinalinen.madre.ui.components.CoffeeRing.prefsKey("rye"),0))
        assertNull(apply(c.copy(operation = VoiceOperation.ADVANCE)))
    }
    @Test fun concurrentLocalPauseAndVoicePauseHaveOneRevisionWinner() = runBlocking {
        val id = vm.startBaking(sampleRecipe(), 1.0)!!
        val c = command(id, VoiceOperation.PAUSE)
        // Local transition acquires gate, suspends for actual Room IO, voice then sees its revision.
        val local = vm.togglePause(id)
        val voice = async { apply(c) }
        local.join(); val r = voice.await()!!
        assertEquals("STALE",r.outcome); assertEquals(1L,vm.session(id)!!.revision)
        assertTrue(vm.session(id)!!.isPaused); assertTrue(history.recordCalls.isEmpty())
    }
    private suspend fun recreateController() {
        androidx.lifecycle.ViewModelStore().apply { put("bake", vm); clear() }
        vm = BakingViewModel(app, history, FakeShelfSync(), FakeBakeSessionLedger())
        // Wait for its real Room restore/id-seeding path without selecting another bake.
        vm.startBaking(sampleRecipe(), 1.0)
    }
    @Test fun invalidRestoredStepSurvivesRejectedVoiceLocalPauseAndAnotherRestore() = runBlocking {
        vm.startBaking(sampleRecipe(), 1.0) // await real recipe asset loading
        val id = vm.startBaking(vm.recipes.value.first(), 3.0)!!
        val original = app.activeBakeRepository.all().first { it.sessionId == id }.copy(
            stepIndex = 999, stepTitle = "Утраченный шаг", stepDurationMinutes = 123, isWaitStep = true)
        app.activeBakeRepository.remember(original)
        recreateController()
        assertFalse(vm.session(id)!!.restoredStepValid)
        assertEquals("REJECTED", apply(command(id, VoiceOperation.PAUSE))!!.outcome)
        assertEquals(original, app.activeBakeRepository.all().first { it.sessionId == id })
        vm.togglePause(id).join()
        val paused = app.activeBakeRepository.all().first { it.sessionId == id }
        assertEquals(original.stepIndex, paused.stepIndex)
        assertEquals(original.stepTitle, paused.stepTitle)
        assertEquals(original.stepDurationMinutes, paused.stepDurationMinutes)
        assertEquals(original.isWaitStep, paused.isWaitStep)
        assertNotNull(paused.pausedAtWallClock)
        recreateController()
        val restored = vm.session(id)!!
        assertFalse(restored.restoredStepValid)
        val snapshot = restored.voiceSnapshot(BakingClock.elapsed(), BakingClock.wallClock(), vm.voiceRecipeRevision())
        assertNull(snapshot["portions"]); assertNull(snapshot["quantities"])
        assertEquals(false, snapshot["control_ready"])
        assertEquals("REJECTED", apply(command(id, VoiceOperation.RESUME))!!.outcome)
        assertEquals(paused, app.activeBakeRepository.all().first { it.sessionId == id })
    }
    @Test fun revokedAtCommitFenceCannotWriteOrAcknowledge() = runBlocking {
        val id = vm.startBaking(sampleRecipe(), 1.0)!!
        val c = command(id, VoiceOperation.PAUSE)
        var reads = 0
        val receipt = vm.applyVoiceCommand(c, BakingClock.elapsed()) {
            reads++
            VoiceAuthority(reads == 1, true, c.bakeKey,generation,connection,vm.voiceRecipeRevision(),c.issuedAt)
        }
        assertNull(receipt); assertFalse(vm.session(id)!!.isPaused)
        assertNull(app.activeBakeRepository.receipt(c.id)); assertEquals(0L,vm.session(id)!!.revision)
    }
}
