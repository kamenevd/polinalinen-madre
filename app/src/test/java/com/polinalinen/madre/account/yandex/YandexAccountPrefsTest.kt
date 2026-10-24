package com.polinalinen.madre.account.yandex

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YandexAccountPrefsTest {
    @get:Rule val temporary=TemporaryFolder()
    private val profile=VerifiedYandexProfile("synthetic_uid_hash","fixture-login","Синтетическое имя","12345/test",false,1000L)

    @Test fun metadataSurvivesActualFileReopeningButNeverContainsTheRamToken()=runTest {
        val file=File(temporary.root,"account.preferences_pb")
        val token=YandexLoginToken.takeSdkResult("SYNTHETIC_TEST_ONLY_NOT_FOR_DISK")
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            var store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            var prefs=YandexAccountPrefs(store)
            prefs.markOfferSeen();prefs.storeVerified(profile);prefs.requireForegroundRetry()
            assertEquals(profile,prefs.read().profile)
            scope.cancel();scope.coroutineContext.job.join()
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            prefs=YandexAccountPrefs(store)
            val restored=prefs.read()
            assertEquals(profile,restored.profile)
            assertTrue(restored.offerSeen)
            assertTrue(restored.foregroundRetryRequired)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(token.forVerificationRequest()))
            assertTrue(setOf("yandex_uid_hash","yandex_login","yandex_display_name","yandex_avatar_id",
                "yandex_avatar_empty","yandex_bound_at","yandex_offer_seen","yandex_foreground_retry_required",
                "yandex_unbind_pending").containsAll(store.data.first().asMap().keys.map{it.name}))
        } finally {token.close();scope.cancel();scope.coroutineContext.job.join()}
    }

    @Test fun logoutPreservesTheOfferAndUnrelatedPreferencesAndRetainsOnlyAnUnbindMarker()=runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val file=File(temporary.root,"logout.preferences_pb")
            val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            val prefs=YandexAccountPrefs(store)
            val unrelated=stringPreferencesKey("unrelated_book_marker")
            store.edit{it[unrelated]="must remain";it[stringPreferencesKey("yandex_unknown_old_key")]="synthetic forbidden value"}
            prefs.markOfferSeen();prefs.storeVerified(profile);prefs.clearAccount(unbindPending=true)
            val snapshot=prefs.read()
            assertNull(snapshot.profile)
            assertTrue(snapshot.offerSeen)
            assertTrue(snapshot.unbindPending)
            assertFalse(snapshot.foregroundRetryRequired)
            assertEquals("must remain",store.data.first()[unrelated])
            assertFalse(store.data.first().asMap().keys.any{it.name=="yandex_unknown_old_key"})
            prefs.acknowledgeUnbind();assertFalse(prefs.read().unbindPending)
        } finally {scope.cancel();scope.coroutineContext.job.join()}
    }

    @Test fun sdkErrorLocalDisconnectDoesNotLeaveAPermanentMarkerAfterActualColdFileReopen()=runTest {
        val file=File(temporary.root,"fresh-sdk-error.preferences_pb")
        var owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var unbinds=0
        val api=object:YandexAccountApi {
            override suspend fun bind(token:YandexLoginToken)=error("No bearer submitted")
            override suspend fun unbind(){unbinds++;throw YandexAccountFailure(YandexAccountError.SERVER)}
        }
        var controller:YandexAccountController?=null
        try {
            var prefs=YandexAccountPrefs(PreferenceDataStoreFactory.create(scope=owner,produceFile={file}))
            prefs.markOfferSeen()
            val first=YandexAccountController(owner,prefs,api);controller=first
            first.failSignIn(requireNotNull(first.beginSignIn()),YandexAccountError.YANDEX)
            first.signOut();assertFalse(prefs.read().unbindPending);assertEquals(0,unbinds)
            first.close();owner.cancel();owner.coroutineContext.job.join()
            owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            prefs=YandexAccountPrefs(PreferenceDataStoreFactory.create(scope=owner,produceFile={file}))
            val cold=YandexAccountController(owner,prefs,api);controller=cold
            assertNotNull(cold.beginSignIn());assertFalse(prefs.read().unbindPending)
            assertTrue(prefs.read().offerSeen);assertEquals(0,unbinds)
        } finally {controller?.close();owner.cancel();owner.coroutineContext.job.join()}
    }

    @Test fun malformedMetadataIsNeverPromotedToSignedIn()=runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={File(temporary.root,"invalid.preferences_pb")})
            val prefs=YandexAccountPrefs(store)
            store.edit {p->
                p[stringPreferencesKey("yandex_uid_hash")]="synthetic_uid_hash"
                p[stringPreferencesKey("yandex_login")]="fixture-login"
                p[longPreferencesKey("yandex_bound_at")]=-1
                p[booleanPreferencesKey("yandex_offer_seen")]=true
            }
            assertNull(prefs.read().profile)
            assertTrue(prefs.read().offerSeen)
            assertTrue(prefs.read().bindingMetadataPresent)
            var unbinds=0
            val api=object:YandexAccountApi {
                override suspend fun bind(token:YandexLoginToken)=error("No login requested")
                override suspend fun unbind(){unbinds++;throw YandexAccountFailure(YandexAccountError.SERVER)}
            }
            val controller=YandexAccountController(scope,prefs,api)
            try {
                controller.signOut()
                assertEquals(1,unbinds);assertTrue(prefs.read().unbindPending)
                assertNull(controller.beginSignIn())
            } finally {controller.close()}
        } finally {scope.cancel();scope.coroutineContext.job.join()}
    }
}
