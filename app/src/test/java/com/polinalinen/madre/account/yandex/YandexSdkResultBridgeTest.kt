package com.polinalinen.madre.account.yandex

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthToken
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Synthetic SDK values and actual AndroidX registry save/restore; no login/Activity is launched. */
@RunWith(AndroidJUnit4::class)
@Config(sdk=[35])
@OptIn(ExperimentalCoroutinesApi::class)
class YandexSdkResultBridgeTest {
    private val clientId="a".repeat(32)
    private val syntheticBearer="SYNTHETIC_SDK_BEARER_MUST_NOT_ENTER_BUNDLE"
    private val profile=VerifiedYandexProfile("synthetic_uid_hash","fixture-login","Синтетическое имя",null,true,1000L)
    private class Registry:ActivityResultRegistry() {
        var requestCode=0
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){this.requestCode=requestCode}
    }
    private fun contract()=object:ActivityResultContract<YandexAuthLoginOptions,YandexAuthResult>() {
        override fun createIntent(context:Context,input:YandexAuthLoginOptions)=Intent("synthetic.auth.not.launched")
        override fun parseResult(resultCode:Int,intent:Intent?):YandexAuthResult =
            if(resultCode!=Activity.RESULT_OK)YandexAuthResult.Cancelled else YandexAuthResult.Success(
                YandexAuthToken(requireNotNull(intent?.getStringExtra("synthetic.bearer")),3600L,
                    intent?.getStringExtra("synthetic.client") ?: clientId))
    }
    private class Storage:YandexAccountStorage {
        var value=YandexAccountSnapshot()
        override suspend fun read()=value
        override suspend fun markOfferSeen(){value=value.copy(offerSeen=true)}
        override suspend fun storeVerified(profile:VerifiedYandexProfile){value=value.copy(profile=profile,foregroundRetryRequired=false)}
        override suspend fun requireForegroundRetry(){value=value.copy(foregroundRetryRequired=true)}
        override suspend fun clearAccount(unbindPending:Boolean){value=value.copy(profile=null,unbindPending=unbindPending)}
        override suspend fun acknowledgeUnbind(){value=value.copy(unbindPending=false)}
    }
    private class Api(val profile:VerifiedYandexProfile):YandexAccountApi {
        var binds=0
        override suspend fun bind(token:YandexLoginToken):VerifiedYandexProfile{binds++;return profile}
        override suspend fun unbind(){}
    }
    private fun result(client:String=clientId)=Intent().putExtra("synthetic.bearer",syntheticBearer).putExtra("synthetic.client",client)
    private fun assertNoBearer(bundle:Bundle) {
        val parcel=Parcel.obtain()
        try {
            bundle.writeToParcel(parcel,0)
            val bytes=parcel.marshall()
            assertFalse(bytes.toString(Charsets.UTF_8).contains(syntheticBearer))
            assertFalse(bytes.toString(Charsets.UTF_16LE).contains(syntheticBearer))
        } finally {parcel.recycle()}
    }

    @Test fun rawSdkIntentIsConsumedBeforeRegistryCanSaveIt()=runTest {
        val storage=Storage();val api=Api(profile);val c=YandexAccountController(backgroundScope,storage,api)
        val registry=Registry();val bridge=YandexSdkResultBridge(registry,contract(),clientId,c,backgroundScope)
        bridge.launch()
        assertTrue(registry.dispatchResult(registry.requestCode,Activity.RESULT_OK,result()))
        val saved=Bundle();registry.onSaveInstanceState(saved)
        saved.putStringArrayList("synthetic.request_keys",bridge.requestKeysForSavedState())
        assertNoBearer(saved)
        runCurrent();assertEquals(1,api.binds);assertEquals(YandexAccountState.SignedIn(profile),c.state.value)
        bridge.close();c.close()
    }

    @Test fun restoredOldProcessRequestIsAnActiveTombstoneNotANewLogin()=runTest {
        val oldApi=Api(profile);val oldController=YandexAccountController(backgroundScope,Storage(),oldApi)
        val oldRegistry=Registry();val oldBridge=YandexSdkResultBridge(oldRegistry,contract(),clientId,oldController,backgroundScope)
        oldBridge.launch()
        val request=oldRegistry.requestCode;val keys=oldBridge.requestKeysForSavedState()
        val registryState=Bundle();oldRegistry.onSaveInstanceState(registryState)
        oldBridge.close();oldController.close()
        val api=Api(profile);val fresh=YandexAccountController(backgroundScope,Storage(),api)
        assertNotEquals(oldController.requestOwnerId,fresh.requestOwnerId)
        val registry=Registry();registry.onRestoreInstanceState(registryState)
        val bridge=YandexSdkResultBridge(registry,contract(),clientId,fresh,backgroundScope,keys)
        assertTrue(registry.dispatchResult(request,Activity.RESULT_OK,result()))
        runCurrent();assertEquals(0,api.binds);assertEquals(YandexAccountState.SignedOut,fresh.state.value)
        val saved=Bundle();registry.onSaveInstanceState(saved);assertNoBearer(saved)
        bridge.close();fresh.close()
    }

    @Test fun rotationUsesTheRetainedOwnerButDoesNotSaveItsBearer()=runTest {
        val api=Api(profile);val c=YandexAccountController(backgroundScope,Storage(),api)
        val first=Registry();val oldBridge=YandexSdkResultBridge(first,contract(),clientId,c,backgroundScope)
        oldBridge.launch()
        val request=first.requestCode;val keys=oldBridge.requestKeysForSavedState()
        val registryState=Bundle();first.onSaveInstanceState(registryState);oldBridge.close()
        val second=Registry();second.onRestoreInstanceState(registryState)
        val bridge=YandexSdkResultBridge(second,contract(),clientId,c,backgroundScope,keys)
        assertTrue(second.dispatchResult(request,Activity.RESULT_OK,result()))
        runCurrent();assertEquals(1,api.binds);assertEquals(YandexAccountState.SignedIn(profile),c.state.value)
        val saved=Bundle();second.onSaveInstanceState(saved);assertNoBearer(saved)
        bridge.close();c.close()
    }

    @Test fun wrongSdkClientCannotReachServerAndCancellationIsSilent()=runTest {
        val api=Api(profile);val c=YandexAccountController(backgroundScope,Storage(),api)
        val registry=Registry();val bridge=YandexSdkResultBridge(registry,contract(),clientId,c,backgroundScope)
        bridge.launch();registry.dispatchResult(registry.requestCode,Activity.RESULT_OK,result("b".repeat(32)))
        runCurrent();assertEquals(0,api.binds)
        assertEquals(YandexAccountState.Error(YandexAccountError.FOREIGN_CLIENT),c.state.value)
        bridge.launch();registry.dispatchResult(registry.requestCode,Activity.RESULT_CANCELED,null)
        runCurrent();assertEquals(YandexAccountState.SignedOut,c.state.value)
        val saved=Bundle();registry.onSaveInstanceState(saved);assertNoBearer(saved)
        bridge.close();c.close()
    }
}
