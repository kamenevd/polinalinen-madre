package com.polinalinen.madre.integration.v2

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RosterCoordinatorTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val cipher=RosterFixtureCipher()
    private val identity=VoiceIdentity("A".repeat(43))
    private val identities by lazy {VoiceIdentityStore(app.getSharedPreferences("identity",0),cipher).also{it.write(identity)}}
    private val store by lazy {RosterConnectionStore(app.getSharedPreferences("roster",0),cipher)}
    private class Api: RosterV2Api {
        var attaches=0;var polls=0;var acks=0
        override suspend fun pairConsent(credential:String,request:InstallationPairConsent):PendingPairConsent = error("Unexpected consent")
        override suspend fun cancelPair(credential:String) {}
        val connection=UUID.randomUUID().toString()
        var attachBlock:(suspend()->Unit)?=null
        var pollBlock:(suspend()->Unit)?=null
        override suspend fun attach(credential:String):String {attaches++;attachBlock?.invoke();return connection}
        override suspend fun poll(installationCredential:String,roster:BakeRoster):RosterPollReply {polls++;pollBlock?.invoke();return RosterPollReply(System.currentTimeMillis(),emptyList())}
        override suspend fun disconnect(credential:String,connection:String) {}
        override suspend fun acknowledge(credential:String,command:RosterCommand,receipt:com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity){acks++}
    }
    private val controller=object:RosterController {
        override suspend fun persistedRoster(connection:String,revision:Long)=BakeRoster(connection,revision,emptyList())
        override suspend fun executeRoster(command:RosterCommand,serverTime:Long,receivedElapsed:Long,authority:()->Boolean):com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity? = error("No command expected")
    }
    private suspend fun waitFor(check:()->Boolean) {withTimeout(5000){while(!check())delay(10)}}
    @Test fun restoreAndLegacyIdentityDoNotCreateConsentOrAttach()=runBlocking {
        val api=Api();val c=RosterCoordinator(identities,store,api,Any()){true};c.accountChanged("fixture-verified");c.attachController(controller)
        val job=launch{c.runForeground()}
        try {delay(100);assertEquals(0,api.attaches);assertEquals(0,api.polls);assertFalse(c.state.value.consent)
            c.consentFromExplicitTap();waitFor{c.state.value.status==RosterStatus.CONNECTED}
            assertEquals(1,api.attaches);assertTrue(store.read()!!.consent)
            val raw=app.getSharedPreferences("roster",0).all.toString()
            assertFalse(raw.contains(api.connection));assertFalse(raw.contains(identity.credential))
            assertEquals(store.read(),RosterConnectionStore(app.getSharedPreferences("roster",0),cipher).read())
        } finally {c.stopForeground();job.cancelAndJoin()}
    }
    @Test fun stoppedForegroundWinsAgainstLateAttachAndCannotPersistConnected()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val api=Api().apply{attachBlock={entered.complete(Unit);release.await()}}
        val c=RosterCoordinator(identities,store,api,Any()){true};c.accountChanged("fixture-verified");c.attachController(controller)
        val job=launch{c.runForeground()};val tap=launch{delay(20);c.consentFromExplicitTap()}
        try {entered.await();c.stopForeground();release.complete(Unit);tap.join()
            assertFalse(store.read()!!.consent);assertNull(store.read()!!.connection);assertEquals(0,api.polls)
            assertNotEquals(RosterStatus.CONNECTED,c.state.value.status)
        }finally{job.cancelAndJoin();tap.cancelAndJoin()}
    }
    @Test fun logoutAndControllerDetachFenceLatePollAndNoAck()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val api=Api().apply{pollBlock={entered.complete(Unit);release.await()}}
        val c=RosterCoordinator(identities,store,api,Any()){true};c.accountChanged("fixture-verified");c.attachController(controller)
        val job=launch{c.runForeground()}
        try {delay(20);c.consentFromExplicitTap();entered.await()
            c.revokeLocally();identities.revoke();c.detachController(controller);release.complete(Unit)
            delay(100);assertEquals(0,api.acks);assertFalse(c.state.value.consent)
            assertTrue(store.read()!!.revokePending);assertFalse(store.read()!!.consent)
        }finally{c.stopForeground();job.cancelAndJoin()}
    }
    @Test fun disabledGateDoesNotEvenReadCredentialOrInvokeTransport()=runBlocking {
        val api=Api();val c=RosterCoordinator(identities,store,api,Any()){false}
        c.accountChanged("fixture-verified");c.attachController(controller);c.load();c.consentFromExplicitTap();c.runForeground();c.disconnect()
        assertEquals(0,api.attaches);assertEquals(0,api.polls);assertEquals(0,api.acks);assertNull(store.read())
    }
}
