package com.polinalinen.madre.account.yandex

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import com.yandex.authsdk.YandexAuthToken
import com.yandex.authsdk.internal.strategy.LoginType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual SDK Activity and strategy contract in Robolectric. No WebView/network/login runs. */
@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],application=MadreApplication::class)
class YandexSdkActivityCustodyTest {
    private val syntheticBearer="SYNTHETIC_INNER_SDK_HOP_BEARER_NEVER_SAVED"
    private val client="a".repeat(32)
    @Suppress("UNCHECKED_CAST")
    private val activityType=Class.forName("com.yandex.authsdk.internal.AuthSdkActivity") as Class<ComponentActivity>

    private fun contract()=YandexAuthSdk.create(YandexAuthOptions(
        ApplicationProvider.getApplicationContext<MadreApplication>(),loggingEnabled=false,skipChromeTab=true)).contract
    private fun result()=Intent().putExtra("com.yandex.authsdk.EXTRA_TOKEN",YandexAuthToken(syntheticBearer,3600L,client))
    private fun assertNoBearer(saved:Bundle) {
        val parcel=Parcel.obtain()
        try {
            saved.writeToParcel(parcel,0)
            val bytes=parcel.marshall()
            assertFalse("SDK inner saved state contains UTF8 bearer",bytes.toString(Charsets.UTF_8).contains(syntheticBearer))
            assertFalse("SDK inner saved state contains UTF16 bearer",bytes.toString(Charsets.UTF_16LE).contains(syntheticBearer))
        } finally {parcel.recycle()}
    }

    @Test fun realSdkActivityConsumesLateStoppedResultBeforeInheritedSavedStateCanPersistBearer() {
        val contract=contract()
        val intent=contract.createIntent(ApplicationProvider.getApplicationContext(),YandexAuthLoginOptions(LoginType.WEBVIEW,client))
        val controller=Robolectric.buildActivity(activityType,intent).create().start().resume()
        try {
            val activity=controller.get()
            val launched=requireNotNull(shadowOf(activity).nextStartedActivityForResult)
            assertEquals("com.yandex.authsdk.internal.WebViewLoginActivity",launched.intent.component?.className)
            // Do not launch that child: deliver a synthetic result through the REAL SDK registry.
            controller.pause().stop()
            assertTrue(activity.activityResultRegistry.dispatchResult(launched.requestCode,Activity.RESULT_OK,result()))
            val saved=Bundle();controller.saveInstanceState(saved)
            assertNoBearer(saved)
            assertTrue(contract.parseResult(shadowOf(activity).resultCode,shadowOf(activity).resultIntent) is YandexAuthResult.Success)
        } finally {controller.destroy()}
    }

    @Test fun realSdkInnerRegistryRestoresRequestKeyWithoutRelaunchThenConsumesResultWhileStopped() {
        val contract=contract()
        val intent=contract.createIntent(ApplicationProvider.getApplicationContext(),YandexAuthLoginOptions(LoginType.WEBVIEW,client))
        val first=Robolectric.buildActivity(activityType,intent).create().start().resume()
        val request=requireNotNull(shadowOf(first.get()).nextStartedActivityForResult).requestCode
        val before=Bundle();first.pause().stop().saveInstanceState(before).destroy();assertNoBearer(before)
        val restored=Robolectric.buildActivity(activityType,intent).create(before).start().resume()
        try {
            val activity=restored.get()
            assertNull("Rotation must not trigger another SDK launch",shadowOf(activity).nextStartedActivityForResult)
            restored.pause().stop()
            assertTrue(activity.activityResultRegistry.dispatchResult(request,Activity.RESULT_OK,result()))
            val saved=Bundle();restored.saveInstanceState(saved);assertNoBearer(saved)
            assertTrue(contract.parseResult(shadowOf(activity).resultCode,shadowOf(activity).resultIntent) is YandexAuthResult.Success)
        } finally {restored.destroy()}
    }
}
