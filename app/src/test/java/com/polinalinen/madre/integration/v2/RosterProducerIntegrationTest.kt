package com.polinalinen.madre.integration.v2

import androidx.lifecycle.viewModelScope
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
    private val dispatcher=UnconfinedTestDispatcher()
    private val cipher = RosterFixtureCipher()
    @Before fun setup() = runBlocking {
        Dispatchers.setMain(dispatcher)
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        vm = BakingViewModel(app, FakeBakeHistory(), FakeShelfSync(), FakeBakeSessionLedger(), rosterEnabled={true})
        withTimeout(15000) { vm.restoreReady.first { it } }; Unit
    }
    @After fun cleanup() = runBlocking {
        val owner=vm.viewModelScope.coroutineContext[Job]!!
        androidx.lifecycle.ViewModelStore().apply { put("bake",vm);clear() }
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
    private suspend fun eventually(test: suspend () -> Boolean) { withTimeout(15000) { while (!test()) delay(25) } }
    private fun coordinator(producer: ActualAliceProducer): RosterCoordinator {
        val identity = VoiceIdentityStore(app.getSharedPreferences("fixture_identity",0),cipher)
        identity.write(VoiceIdentity(producer.credential)) // synthetic fixture credential, never a live grant
        return RosterCoordinator(identity,RosterConnectionStore(app.getSharedPreferences("fixture_connection",0),cipher),
            producer.api,app.activeBakeRepository.commitFence,{true}).also { it.accountChanged("fixture-verified"); it.attachController(vm) }
    }
    @Test fun zeroBakeActualPendingApprovalIsNotGrantAndOnlyRedemptionThenExplicitCheckConnects() = runBlocking {
        ActualAliceProducer().use { p ->
            val c=coordinator(p);val job=launch{c.runForeground()}
            try {
                delay(50);assertTrue(vm.persistedRoster("00000000-0000-0000-0000-000000000001",0).bakes.isEmpty())
                c.consentFromExplicitTap();assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
                val first=p.control("inspect");assertEquals(0L,first.integer("legacy"));assertTrue(first.get("selected").isJsonNull)
                assertEquals(0L,first.integer("grants"));assertTrue(first.getAsJsonArray("snapshots").isEmpty)
                p.control("authorize");c.checkFromExplicitTap();assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
                assertEquals(0L,p.control("inspect").integer("grants"))
                p.control("redeem");delay(50);assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
                c.checkFromExplicitTap();eventually{c.state.value.status==RosterStatus.CONNECTED}
                assertTrue(c.state.value.bakes.isEmpty())
                assertTrue(p.control("inspect").getAsJsonArray("snapshots").single().asJsonArray.isEmpty)
                assertEquals(0L,p.control("inspect").integer("legacy"))
                assertEquals(400L,p.control("replay_code").integer("status"))
                eventually{c.state.value.status==RosterStatus.OFFLINE}
                assertTrue(runCatching{p.api.attach(p.credential)}.exceptionOrNull() is ContractFailure)
            }finally{c.stopForeground();job.cancelAndJoin()}
        }
    }
    @Test fun actualProducerReattachKeepsSameConnectionAndMonotonicPersistedRevision() = runBlocking {
        ActualAliceProducer().use { p ->
            val c=coordinator(p);val job=launch{c.runForeground()}
            try {
                delay(50);c.consentFromExplicitTap();p.control("authorize");p.control("redeem");c.checkFromExplicitTap()
                eventually{c.state.value.status==RosterStatus.CONNECTED}
                val store=RosterConnectionStore(app.getSharedPreferences("fixture_connection",0),cipher)
                val before=store.read()!!
                c.consentFromExplicitTap()
                eventually{c.state.value.status==RosterStatus.CONNECTED}
                val after=store.read()!!
                assertEquals(before.connection,after.connection);assertTrue(after.revision>before.revision)
                assertNull(store.readAttempt());assertTrue(c.state.value.bakes.isEmpty())
                assertEquals(0L,p.control("inspect").integer("legacy"))
            }finally{c.stopForeground();job.cancelAndJoin()}
        }
    }
    @Test fun plannedOnlyActualRoomRosterPairsWithoutStartingBakeAndRemainsReadOnly() = runBlocking {
        ActualAliceProducer().use { p ->
            val clock=java.time.Clock.systemUTC()
            val plan=com.polinalinen.madre.navigation.PlannedBake(vm.recipes.value.first().id,2,clock.millis()+3600000)
            vm.plannedBakes.save(plan,clock)
            val c=coordinator(p);val job=launch{c.runForeground()}
            try {
                eventually{c.state.value.bakes.size==1};c.consentFromExplicitTap()
                assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
                p.control("authorize");p.control("redeem");c.checkFromExplicitTap()
                eventually{c.state.value.status==RosterStatus.CONNECTED}
                val planned=c.state.value.bakes.single();assertEquals(RosterBakeState.SCHEDULED,planned.state)
                assertFalse(planned.controlReady);assertTrue(app.activeBakeRepository.all().isEmpty())
                assertTrue(p.control("speech","пауза ${planned.bakeNumber}").string("text").contains("телефоне"))
                p.control("speech","да");assertTrue(p.control("inspect").getAsJsonArray("commands").isEmpty)
                assertEquals(0L,p.control("inspect").integer("legacy"));assertTrue(p.control("inspect").get("selected").isJsonNull)
                assertEquals(400L,p.control("refresh_replay").integer("status"))
                eventually{c.state.value.status==RosterStatus.OFFLINE}
                assertTrue(app.activeBakeRepository.all().isEmpty())
            }finally{c.stopForeground();job.cancelAndJoin()}
        }
    }
    @Test fun actualPendingCancelExpiryAndAccountEpochCannotBeAdoptedAsGrant() = runBlocking {
        ActualAliceProducer().use { p ->
            val c=coordinator(p);val job=launch{c.runForeground()}
            try {
                delay(50);c.consentFromExplicitTap();p.control("authorize")
                c.disconnect();assertEquals(RosterStatus.UNLINKED,c.state.value.status)
                assertEquals(400L,p.control("replay_code").integer("status"))
                assertTrue(runCatching{p.api.attach(p.credential)}.exceptionOrNull() is ContractFailure)
                c.consentFromExplicitTap();assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
                assertEquals(200L,p.control("account_change").integer("status"))
                c.accountChanged("new-verified-account");c.checkFromExplicitTap()
                assertEquals(InstallationPairStatus.INTERRUPTED,c.state.value.pairStatus)
                assertTrue(runCatching{p.api.attach(p.credential)}.exceptionOrNull() is ContractFailure)
                assertEquals(0L,p.control("inspect").integer("grants"))
                c.disconnect();c.consentFromExplicitTap()
                val request=InstallationPairConsent(java.util.UUID.randomUUID().toString())
                // Explicit new synthetic request; expired IDs cannot be re-armed by transport retry.
                p.api.pairConsent(p.credential,request);p.control("expire")
                assertEquals(ContractFailure.Kind.CHALLENGE_CONSUMED,
                    (runCatching{p.api.pairConsent(p.credential,request)}.exceptionOrNull() as ContractFailure).kind)
                assertTrue(runCatching{p.api.attach(p.credential)}.exceptionOrNull() is ContractFailure)
                assertTrue(app.activeBakeRepository.all().isEmpty())
            }finally{c.stopForeground();job.cancelAndJoin()}
        }
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
                assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING, coordinator.state.value.pairStatus)
                assertEquals(0L,producer.control("inspect").integer("grants"))
                producer.control("authorize")
                coordinator.checkFromExplicitTap()
                assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,coordinator.state.value.pairStatus)
                producer.control("redeem")
                coordinator.checkFromExplicitTap()
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
                assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,coordinator.state.value.pairStatus)
                producer.control("authorize");producer.control("redeem");coordinator.checkFromExplicitTap()
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
            p.completeExplicitPair()
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
            p.completeExplicitPair()
            val old=p.api.attach(p.credential)
            p.api.poll(p.credential,vm.persistedRoster(old,1)) // genuine empty roster replacement
            p.api.disconnect(p.credential,old)
            assertTrue(runCatching{p.api.attach(p.credential)}.exceptionOrNull() is ContractFailure)
            p.completeExplicitPair()
            val current=p.api.attach(p.credential);assertNotEquals(old,current)
            p.api.disconnect(p.credential,old)
            assertTrue(p.api.poll(p.credential,vm.persistedRoster(current,1)).commands.isEmpty())
            assertTrue(p.control("inspect").getAsJsonArray("snapshots").single().asJsonArray.isEmpty)
        }
    }
}
