package com.polinalinen.madre.integration.v2

import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.TokenCipher
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.voice.*
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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal class RosterFixtureCipher : TokenCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    override fun encrypt(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let {
        it.init(Cipher.ENCRYPT_MODE,key); it.iv + it.doFinal(plain)
    }
    override fun decrypt(sealed: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let {
        it.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,sealed.copyOfRange(0,12))); it.doFinal(sealed.copyOfRange(12,sealed.size))
    }
}

/** Actual Android coordinator/HTTP/Room executor paired to unchanged isolated Alice producer.
 * Only external Yandex is substituted; synthetic local pairing uses real producer handlers.
 * This is JVM integration, NOT family phone/Station/SDK/live-account acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RosterProducerIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var vm: BakingViewModel
    private val cipher = RosterFixtureCipher()
    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        vm = BakingViewModel(app, FakeBakeHistory(), FakeShelfSync(), FakeBakeSessionLedger(), rosterEnabled={true})
        withTimeout(15000) { vm.restoreReady.first { it } }; Unit
    }
    @After fun cleanup() {
        androidx.lifecycle.ViewModelStore().apply { put("bake",vm);clear() }
        Dispatchers.resetMain()
    }
    private suspend fun eventually(test: suspend () -> Boolean) { withTimeout(15000) { while (!test()) delay(25) } }
    private fun coordinator(producer: ActualAliceProducer): RosterCoordinator {
        val identity = VoiceIdentityStore(app.getSharedPreferences("fixture_identity",0),cipher)
        identity.write(VoiceIdentity(producer.credential)) // synthetic fixture credential, never a live grant
        return RosterCoordinator(identity,RosterConnectionStore(app.getSharedPreferences("fixture_connection",0),cipher),
            producer.api,app.activeBakeRepository.commitFence,{true}).also { it.attachController(vm) }
    }
    @Test fun actualProducerCommandIsAcknowledgedOnlyAfterPhoneRoomCommit() = runBlocking {
        ActualAliceProducer().use { producer ->
            assertEquals(409L,producer.unpairedStatus) // exact new/empty-installation handshake gap
            val id = vm.startBaking(vm.recipes.value.first(),2.0)!!
            val coordinator = coordinator(producer)
            val job = launch { coordinator.runForeground() }
            try {
                eventually { coordinator.state.value.bakes.size == 1 }
                assertEquals(RosterStatus.UNLINKED,coordinator.state.value.status)
                assertEquals(0,producer.control("inspect").getAsJsonArray("snapshots").size())
                coordinator.consentFromExplicitTap()
                eventually { coordinator.state.value.status == RosterStatus.CONNECTED }
                val number=vm.session(id)!!.bakeNumber
                assertTrue(producer.control("speech","пауза $number").string("text").contains("Скажите"))
                producer.control("speech","да")
                eventually { vm.session(id)?.isPaused == true }
                eventually { producer.control("inspect").getAsJsonArray("commands").single().asJsonObject.get("ack")?.isJsonNull == false }
                val command = producer.control("inspect").getAsJsonArray("commands").single().asJsonObject
                val ack = command.getAsJsonObject("ack")
                assertEquals("applied",ack.string("outcome")); assertEquals(1L,ack.integer("resulting_revision"))
                assertEquals(1L,app.activeBakeRepository.all().single().revision)
                assertEquals(1L,vm.session(id)!!.revision)
                // Late/repeated ack cannot cause another state write; inspect exact persisted revision.
                coordinator.stopForeground(); job.cancelAndJoin()
                assertEquals(RosterStatus.OFFLINE,coordinator.state.value.status)
                assertEquals(1L,app.activeBakeRepository.all().single().revision)
                coordinator.disconnect()
                assertEquals(RosterStatus.UNLINKED,coordinator.state.value.status)
                assertTrue(runCatching{producer.api.attach(producer.credential)}.exceptionOrNull() is ContractFailure)
            } finally { coordinator.stopForeground();job.cancelAndJoin() }
        }
    }
    @Test fun realSqlFailureNeverBecomesAnAppliedAckToActualProducer() = runBlocking {
        ActualAliceProducer().use { producer ->
            val id=vm.startBaking(vm.recipes.value.first(),1.0)!!
            val coordinator=coordinator(producer);val job=launch{coordinator.runForeground()}
            try {
                eventually{coordinator.state.value.bakes.size==1};coordinator.consentFromExplicitTap()
                eventually{coordinator.state.value.status==RosterStatus.CONNECTED}
                app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_no_receipt BEFORE INSERT ON voice_command_receipts BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
                producer.control("speech","пауза ${vm.session(id)!!.bakeNumber}");producer.control("speech","да")
                eventually { producer.control("inspect").getAsJsonArray("commands").single().asJsonObject.string("state") == "dispatched" }
                delay(1000)
                val result=producer.control("inspect").getAsJsonArray("commands").single().asJsonObject
                assertTrue(result.get("ack").isJsonNull);assertFalse(vm.session(id)!!.isPaused)
                assertEquals(0L,app.activeBakeRepository.all().single().revision)
            } finally {
                coordinator.stopForeground();job.cancelAndJoin()
                app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fixture_no_receipt")
            }
        }
    }
    @Test fun actualProducerAcceptsPersistedPlanStartFinaleRevisionsAndFullReplacement() = runBlocking {
        ActualAliceProducer().use {p ->
            val active=vm.startBaking(vm.recipes.value.first(),2.0)!!
            val recipe=vm.recipes.value[1];val clock=java.time.Clock.systemUTC()
            val plan=com.polinalinen.madre.navigation.PlannedBake(recipe.id,2,clock.millis()+3600000)
            vm.plannedBakes.save(plan,clock)
            val connection=p.api.attach(p.credential)
            val initial=vm.persistedRoster(connection,1)
            assertEquals(2,initial.bakes.size);p.api.poll(p.credential,initial)
            val planned=initial.bakes.single{it.state==RosterBakeState.SCHEDULED}
            assertTrue(p.control("speech","пауза ${planned.bakeNumber}").string("text").contains("телефоне"))
            assertEquals(0,p.control("inspect").getAsJsonArray("commands").size())
            val started=vm.plannedBakes.start(plan.id)!!
            val running=vm.persistedRoster(connection,2);p.api.poll(p.credential,running)
            val child=running.bakes.single{it.bakeKey==planned.bakeKey}
            assertTrue(child.revision>planned.revision);assertEquals(RosterBakeState.RUNNING,child.state)
            while(!vm.session(started)!!.isCompleted)vm.advanceStep(started).join()
            val finale=vm.persistedRoster(connection,3);p.api.poll(p.credential,finale)
            assertEquals(RosterBakeState.FINALE,finale.bakes.single{it.bakeKey==planned.bakeKey}.state)
            vm.cancelSession(active).join()
            p.api.poll(p.credential,vm.persistedRoster(connection,4))
            val kept=p.control("inspect").getAsJsonArray("snapshots").single().asJsonArray
            assertEquals(1,kept.size());assertEquals(planned.bakeKey,kept.single().asJsonObject.string("bake_key"))
        }
    }
    @Test fun actualDisconnectRequiresNewPairAndStaleDisconnectCannotRevokeReattach() = runBlocking {
        ActualAliceProducer().use { p ->
            val old=p.api.attach(p.credential)
            p.api.poll(p.credential,vm.persistedRoster(old,1)) // genuine empty roster replacement
            p.api.disconnect(p.credential,old)
            assertTrue(runCatching{p.api.attach(p.credential)}.exceptionOrNull() is ContractFailure)
            assertTrue(p.control("repair_pair").boolean("paired"))
            val current=p.api.attach(p.credential);assertNotEquals(old,current)
            p.api.disconnect(p.credential,old)
            assertTrue(p.api.poll(p.credential,vm.persistedRoster(current,1)).commands.isEmpty())
            assertTrue(p.control("inspect").getAsJsonArray("snapshots").single().asJsonArray.isEmpty)
        }
    }
}
