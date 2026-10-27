package com.polinalinen.madre.integration.v2

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.voice.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Synthetic transport faults exercise the foreground state machine, not server authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class InstallationPairCoordinatorTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val cipher=RosterFixtureCipher()
    private val identities by lazy { VoiceIdentityStore(app.getSharedPreferences("pair_id",0),cipher).also { it.write(VoiceIdentity("Q".repeat(43))) } }
    private val store by lazy { RosterConnectionStore(app.getSharedPreferences("pair_conn",0),cipher) }
    private class Api: RosterV2Api {
        var paired=false;var attaches=0;var polls=0;var cancels=0
        var failDelivery=false;var ttl=300_000L
        var consentFailure:ContractFailure.Kind?=null
        var pollFailure=false
        var gate:(suspend()->Unit)?=null
        val requests=mutableListOf<String>()
        val revisions=mutableListOf<Long>()
        val connection=UUID.randomUUID().toString()
        var deadline:Long?=null
        override suspend fun attach(credential:String):String {
            attaches++; if(!paired)throw ContractFailure(ContractFailure.Kind.PAIR_REQUIRED)
            gate?.invoke();return connection
        }
        override suspend fun pairConsent(credential:String,request:InstallationPairConsent):PendingPairConsent {
            requests+=request.requestId
            consentFailure?.let{throw ContractFailure(it)}
            if(deadline==null)deadline=System.currentTimeMillis()+ttl
            gate?.invoke()
            if(failDelivery){failDelivery=false;throw ContractFailure(ContractFailure.Kind.NETWORK)}
            return PendingPairConsent(request.requestId,deadline!!)
        }
        override suspend fun cancelPair(credential:String) {cancels++;paired=false}
        override suspend fun disconnect(credential:String,connection:String) {cancels++;paired=false}
        override suspend fun poll(installationCredential:String,roster:BakeRoster):RosterPollReply {
            polls++;revisions+=roster.revision;if(pollFailure)throw ContractFailure(ContractFailure.Kind.NETWORK)
            return RosterPollReply(System.currentTimeMillis(),emptyList())
        }
        override suspend fun acknowledge(credential:String,command:RosterCommand,receipt:com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity)=Unit
    }
    private val controller=object:RosterController {
        override suspend fun persistedRoster(connection:String,revision:Long)=BakeRoster(connection,revision,emptyList())
        override suspend fun executeRoster(command:RosterCommand,serverTime:Long,receivedElapsed:Long,authority:()->Boolean):com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity?=error("No command")
    }
    private fun coordinator(api:Api)=RosterCoordinator(identities,store,api,Any()){true}.also{it.attachController(controller)}
    private suspend fun eventually(test:()->Boolean){withTimeout(5000){while(!test())delay(10)}}
    @Test fun consentPrecedesSdkAndSdkCompletionNeverCreatesOrRetriesConsent()=runBlocking {
        val api=Api();val c=coordinator(api);val job=launch{c.runForeground()}
        try {
            delay(30);c.consentFromExplicitTap()
            assertEquals(InstallationPairStatus.NEEDS_IDENTITY,c.state.value.pairStatus)
            val id=store.readAttempt()!!.requestId
            assertTrue(uuid(id));assertEquals(0,api.attaches);assertTrue(api.requests.isEmpty())
            c.accountChanged("verified-sdk-account");delay(50)
            assertEquals(0,api.attaches);assertTrue(api.requests.isEmpty())
            c.continuePairFromExplicitTap()
            assertEquals(listOf(id),api.requests);assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
            assertFalse(c.state.value.consent);assertEquals(0,api.polls)
            c.checkFromExplicitTap();assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
            api.paired=true;delay(50);assertEquals(0,api.polls)
            c.checkFromExplicitTap();eventually{c.state.value.status==RosterStatus.CONNECTED}
            assertTrue(store.read()!!.consent);assertNull(store.readAttempt())
        }finally{c.stopForeground();job.cancelAndJoin()}
    }
    @Test fun uncertainDeliveryRetryUsesSameIdAndOriginalDeadlineNoSilentRenewal()=runBlocking {
        val api=Api().apply{failDelivery=true};val c=coordinator(api);c.accountChanged("sdk")
        val job=launch{c.runForeground()}
        try{
            delay(30);c.consentFromExplicitTap();assertEquals(RosterStatus.ERROR,c.state.value.status)
            val first=store.readAttempt()!!;val serverDeadline=api.deadline
            delay(50);assertEquals(1,api.requests.size)
            c.consentFromExplicitTap();assertEquals(1,api.requests.size) // no implicit replacement
            c.continuePairFromExplicitTap()
            assertEquals(listOf(first.requestId,first.requestId),api.requests)
            assertEquals(serverDeadline,api.deadline);assertTrue(store.readAttempt()!!.deadline<=first.deadline)
            assertEquals(InstallationPairStatus.AUTHORIZATION_PENDING,c.state.value.pairStatus)
        }finally{c.stopForeground();job.cancelAndJoin()}
    }
    @Test fun expiryIsTerminalAndOnlyCancelThenNewConsentCanMintNewId()=runBlocking {
        val api=Api().apply{ttl=150};val c=coordinator(api);c.accountChanged("sdk")
        val job=launch{c.runForeground()}
        try{
            delay(30);c.consentFromExplicitTap();val id=store.readAttempt()!!.requestId
            eventually{c.state.value.pairStatus==InstallationPairStatus.EXPIRED}
            c.continuePairFromExplicitTap();c.consentFromExplicitTap();assertEquals(listOf(id),api.requests)
            c.disconnect();assertEquals(1,api.cancels);assertNull(store.readAttempt())
            api.deadline=null;api.ttl=300_000;c.consentFromExplicitTap()
            assertNotEquals(id,store.readAttempt()!!.requestId)
        }finally{c.stopForeground();job.cancelAndJoin()}
    }
    @Test fun processDeathNeverResendsPendingOrAdoptsItsCallback()=runBlocking {
        val api=Api();val first=coordinator(api);first.accountChanged("sdk");var job=launch{first.runForeground()}
        delay(30);first.consentFromExplicitTap();val pending=store.readAttempt()!!
        first.stopForeground();job.cancelAndJoin()
        val cold=coordinator(api);cold.accountChanged("sdk");job=launch{cold.runForeground()}
        try{
            delay(50);assertEquals(InstallationPairStatus.INTERRUPTED,cold.state.value.pairStatus)
            api.paired=true;cold.checkFromExplicitTap();cold.continuePairFromExplicitTap();cold.consentFromExplicitTap()
            assertEquals(1,api.requests.size);assertEquals(1,api.attaches);assertEquals(0,api.polls)
            assertEquals(pending.requestId,store.readAttempt()!!.requestId)
            cold.disconnect();assertEquals(1,api.cancels);assertFalse(api.paired)
        }finally{cold.stopForeground();job.cancelAndJoin()}
    }
    @Test fun cancelFencesInFlightPairConsentAndStillRevokesServerWithoutConnection()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val api=Api().apply{gate={entered.complete(Unit);release.await()}}
        val c=coordinator(api);c.accountChanged("sdk");val job=launch{c.runForeground()}
        val tap=launch{delay(30);c.consentFromExplicitTap()}
        try{
            entered.await();c.revokeLocally();val cancel=launch{c.disconnect()};release.complete(Unit)
            tap.join();cancel.join();assertEquals(1,api.cancels)
            assertEquals(RosterStatus.UNLINKED,c.state.value.status);assertFalse(store.read()!!.consent);assertNull(store.readAttempt());assertEquals(0,api.polls)
        }finally{c.stopForeground();job.cancelAndJoin();tap.cancelAndJoin()}
    }
    @Test fun stablePermissionColdRestoreNeedsExplicitCheckAndRejectsChangedAccountBeforeTransport()=runBlocking {
        val api=Api().apply{paired=true}
        val first=coordinator(api);first.accountChanged("sdk-old")
        var job=launch{first.runForeground()}
        delay(30);first.consentFromExplicitTap();eventually{first.state.value.status==RosterStatus.CONNECTED}
        first.stopForeground();job.cancelAndJoin()
        val cold=coordinator(api);cold.accountChanged("sdk-old");job=launch{cold.runForeground()}
        try {
            delay(60);val previousPolls=api.polls;val previousAttach=api.attaches
            assertEquals(RosterStatus.OFFLINE,cold.state.value.status)
            delay(550);assertEquals(previousPolls,api.polls);assertEquals(previousAttach,api.attaches)
            cold.checkFromExplicitTap();eventually{cold.state.value.status==RosterStatus.CONNECTED}
            assertTrue(api.requests.isEmpty()) // stable grant, never a second authorization challenge
            cold.accountChanged("sdk-new");val fencedAttach=api.attaches;val fencedPolls=api.polls
            cold.checkFromExplicitTap();cold.retry();delay(550)
            assertEquals(fencedAttach,api.attaches);assertEquals(fencedPolls,api.polls)
            assertNotEquals(RosterStatus.CONNECTED,cold.state.value.status)
        }finally{cold.stopForeground();job.cancelAndJoin()}
    }
    @Test fun repeatedExplicitConsentPreservesRevisionWhenProducerReturnsSameConfirmedConnection()=runBlocking {
        val api=Api().apply{paired=true};val c=coordinator(api);c.accountChanged("sdk")
        val job=launch{c.runForeground()}
        try {
            delay(30);c.consentFromExplicitTap();eventually{c.state.value.status==RosterStatus.CONNECTED}
            val polls=api.polls
            c.consentFromExplicitTap();eventually{api.polls>polls && c.state.value.status==RosterStatus.CONNECTED}
            assertTrue(api.requests.isEmpty())
            assertTrue("No roster revision reset for the same authoritative connection",api.revisions.zipWithNext().all{(a,b)->b>a})
        }finally{c.stopForeground();job.cancelAndJoin()}
    }
    @Test fun confirmedAttachIsNotConnectedUntilSuccessfulValidatedPoll()=runBlocking {
        val api=Api().apply{paired=true;pollFailure=true}
        val c=coordinator(api);c.accountChanged("sdk");val job=launch{c.runForeground()}
        try {
            delay(30);c.consentFromExplicitTap()
            assertEquals(RosterStatus.ATTACHED,c.state.value.status)
            eventually{api.polls>0};assertEquals(RosterStatus.OFFLINE,c.state.value.status)
            assertTrue(api.requests.isEmpty())
            api.pollFailure=false;c.checkFromExplicitTap()
            eventually{c.state.value.status==RosterStatus.CONNECTED}
        }finally{c.stopForeground();job.cancelAndJoin()}
    }
    @Test fun consumedChallengeAndBindingFailuresNeverRearmTheSameRequest()=runBlocking {
        for(kind in listOf(ContractFailure.Kind.CHALLENGE_CONSUMED,ContractFailure.Kind.BINDING_CHANGED,
            ContractFailure.Kind.BINDING_REQUIRED,ContractFailure.Kind.UNAUTHORIZED)) {
            val api=Api().apply{consentFailure=kind};val c=coordinator(api);c.accountChanged("sdk")
            val job=launch{c.runForeground()}
            try {
                delay(30);c.consentFromExplicitTap()
                assertEquals(InstallationPairStatus.INTERRUPTED,c.state.value.pairStatus)
                val request=store.readAttempt()!!.requestId
                c.continuePairFromExplicitTap();c.consentFromExplicitTap();c.retry();delay(550)
                assertEquals(listOf(request),api.requests);assertEquals(0,api.polls)
                c.disconnect();assertNull(store.readAttempt())
            }finally{c.stopForeground();job.cancelAndJoin()}
        }
    }
    @Test fun accountReplacementFencesLateConfirmedAttachAndNeverPolls()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val api=Api();val c=coordinator(api);c.accountChanged("sdk-old");val job=launch{c.runForeground()}
        try{
            delay(30);c.consentFromExplicitTap();api.paired=true;api.gate={entered.complete(Unit);release.await()}
            val check=launch{c.checkFromExplicitTap()};entered.await();c.accountChanged(null);c.accountChanged("sdk-new")
            release.complete(Unit);check.join()
            assertEquals(InstallationPairStatus.INTERRUPTED,c.state.value.pairStatus);assertFalse(c.state.value.consent)
            assertEquals(0,api.polls);assertNull(store.read()!!.connection)
        }finally{c.stopForeground();job.cancelAndJoin()}
    }
}
