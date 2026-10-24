package com.polinalinen.madre.account.yandex

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.TokenCipher
import com.polinalinen.madre.voice.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],application=MadreApplication::class)
class YandexInstallationAccessTest {
    private val prefs get()=ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("synthetic-yandex-installation",Context.MODE_PRIVATE)
    private val cipher=object:TokenCipher {
        override fun encrypt(plain:ByteArray)=plain.reversedArray()
        override fun decrypt(sealed:ByteArray)=sealed.reversedArray()
    }
    @Before fun setup(){prefs.edit().clear().commit()}
    private class Api:VoiceApi {
        val calls=mutableListOf<String>()
        override suspend fun post(path:String,credential:String,payload:Any):JsonObject {
            calls+=path
            assertTrue(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
            return JsonObject()
        }
    }

    @Test fun explicitBindEnrollsOnlyAnInstallationWithoutSelectingOrApprovingABake()=runBlocking {
        val api=Api();val store=VoiceIdentityStore(prefs,cipher);val access=YandexInstallationAccess(store,api)
        val credential=access.forBind()
        assertEquals(listOf("enroll"),api.calls)
        assertEquals(credential,store.read()?.credential)
        assertNull(store.read()?.bakeKey);assertNull(store.read()?.generation)
        assertEquals(credential,access.forUnbind());assertEquals(listOf("enroll"),api.calls)
        assertFalse(prefs.getString("identity","")!!.contains(credential))
    }

    @Test fun missingUnbindIdentityNeverCreatesOrEnrollsAReplacement()=runBlocking {
        val api=Api();val access=YandexInstallationAccess(VoiceIdentityStore(prefs,cipher),api)
        val failed=runCatching{access.forUnbind()}.exceptionOrNull()
        assertTrue(failed is YandexAccountFailure)
        assertFalse(prefs.contains("identity"));assertTrue(api.calls.isEmpty())
    }

    @Test fun corruptedExistingIdentityIsNotPermissionToEnrollAnotherInstallation()=runBlocking {
        val original="synthetic-invalid-ciphertext"
        prefs.edit().putString("identity",original).commit()
        val api=Api();val access=YandexInstallationAccess(VoiceIdentityStore(prefs,cipher),api)
        assertTrue(runCatching{access.forBind()}.isFailure)
        assertEquals(original,prefs.getString("identity",null));assertTrue(api.calls.isEmpty())
    }

    @Test fun simultaneousAccountAndBakeProvisioningShareOneActualPersistedCredential()=runBlocking {
        val store=VoiceIdentityStore(prefs,cipher)
        val credentials=coroutineScope{List(8){async(Dispatchers.IO){store.readOrCreate().credential}}.awaitAll()}
        assertEquals(1,credentials.toSet().size)
        assertEquals(credentials.first(),store.read()?.credential)
    }
}
