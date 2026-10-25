package com.polinalinen.madre.ui.screens

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.model.BookDisplaySettings
import com.polinalinen.madre.ui.kit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual L1 -> retained VM/controller -> real file metadata and Room; only HTTP is synthetic. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class LSettingsAccountRecoveryTest {
    @get:Rule val rule=createComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val disconnect="Отключить аккаунт на этом телефоне"
    private val pendingCopy="На телефоне отключено. Отзыв доступа на сервере ещё не подтверждён."

    @Test fun ambiguousNetworkBindCanBeCancelledThenRevokedAndRetriedAfterColdMetadataReload() =
        recovery(YandexAccountError.NETWORK)

    @Test fun verifiedBindWithFailedProfileWriteStillOffersConfirmedRevocation() =
        recovery(YandexAccountError.STORAGE)

    @Test fun restoredForegroundRetryCanBeRevokedFromTheAlreadyOpenProfilePage() =
        recovery(YandexAccountError.SERVER,restored=true,page="profile")

    @Test fun signedInStillHasExactlySixRootRowsAndOneProfileOnlyLogout() {
        val profile=VerifiedYandexProfile("synthetic_uid_hash","fixture-login","Синтетическое имя",null,true,1000L)
        runBlocking{app.yandexAccountStorage.storeVerified(profile)}
        val store=ViewModelStore()
        lateinit var model:YandexAccountViewModel
        rule.runOnUiThread{model=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[YandexAccountViewModel::class.java]}
        try {
            await{model.state.value is YandexAccountState.SignedIn}
            rule.setContent {
                CompositionLocalProvider(LocalYandexAccountUi provides YandexAccountUi(model){error("SDK must not launch")}) {
                    RazvorotTheme(force=Atmosphere.DAWN) {
                        LSettingsContent("Пользовательское имя",{},{},{},{},"Мадре",{},24,{},true,{},0,0,
                            ReadingPreferences(app,"l1-signed-recovery-contract"),FamilyBookState.SignedOut,
                            BookDisplaySettings(),{},{},null)
                    }
                }
            }
            rule.onAllNodes(SemanticsMatcher("L1 root row") {
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("settings-row-")==true
            }).assertCountEquals(6)
            rule.onNodeWithText(disconnect).assertDoesNotExist()
            rule.onNodeWithText("Выйти из Яндекс ID").assertDoesNotExist()
            rule.onNodeWithText("Яндекс ID · Синтетическое имя").performClick()
            rule.onAllNodesWithText("Выйти из Яндекс ID").assertCountEquals(1)
            rule.onNodeWithText("Выйти из Яндекс ID").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(disconnect).assertDoesNotExist()
        } finally {rule.runOnUiThread{store.clear()}}
    }

    private fun recovery(expected:YandexAccountError,restored:Boolean=false,page:String="root") {
        val id=UUID.randomUUID().toString()
        val file=File(app.cacheDir,"l1-recovery-$id.preferences_pb")
        var ioScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        fun openPrefs()=YandexAccountPrefs(PreferenceDataStoreFactory.create(scope=ioScope,produceFile={file}))
        var prefs=openPrefs()
        val storage=object:YandexAccountStorage by prefs {
            override suspend fun storeVerified(profile:VerifiedYandexProfile) {
                if(expected==YandexAccountError.STORAGE)throw IOException("Synthetic profile write failure")
                prefs.storeVerified(profile)
            }
        }
        val binds=AtomicInteger();val unbinds=AtomicInteger();val sdkLaunches=AtomicInteger()
        var remoteBound=restored
        var acknowledge=false
        // The simulated remote family is independent: account revoke must not delete membership.
        val membership=setOf("synthetic-family:synthetic-member")
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val status=when(request.url.encodedPath) {
                "/alice/madre/voice/phone/yandex-bind" -> {
                    binds.incrementAndGet();remoteBound=true
                    if(expected==YandexAccountError.NETWORK)throw IOException("Synthetic response lost after bind")
                    200
                }
                "/alice/madre/voice/phone/yandex-unbind" -> {
                    unbinds.incrementAndGet()
                    if(acknowledge){remoteBound=false;204}else 503
                }
                else -> error("Unexpected external request: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
                .body("""{"uid_hash":"synthetic_uid_hash","login":"fixture-login","display_name":"Синтетическое имя","avatar_empty":true}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api=HttpsYandexAccountApi({"A".repeat(43)},http,{1000L})
        ReflectionHelpers.setField(app,"yandexTransport\$delegate",lazyOf(api))
        ReflectionHelpers.setField(app,"yandexAccountStorage\$delegate",lazyOf(storage))
        var store=ViewModelStore()
        fun newModel():YandexAccountViewModel {
            lateinit var result:YandexAccountViewModel
            rule.runOnUiThread { result=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[YandexAccountViewModel::class.java] }
            return result
        }
        val profile=VerifiedYandexProfile("synthetic_uid_hash","fixture-login","Синтетическое имя",null,true,1000L)
        val book=app.getSharedPreferences("l1-independent-book-$id",0)
        assertTrue(book.edit().putString("owner_name","Пользовательское имя").putString("family_marker","synthetic-family").commit())
        val photo=File(app.filesDir,"l1-surviving-$id.png")
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        photo.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val photoBytes=photo.readBytes()
        val record=runBlocking {
            val recipe=app.recipeRepository.getRecipes().first()
            app.bakeHistoryRepository.record(recipe.id,recipe.name,2,1_700_000_000_000L)
        }
        val roomBefore=roomRows()
        val bookBefore=book.all.toMap()
        val active=mutableStateOf<YandexAccountViewModel?>(null)
        try {
            runBlocking {
                prefs.markOfferSeen()
                if(restored)prefs.storeAliceBound(profile)
            }
            var model=newModel()
            if(!restored) {
                await{model.state.value is YandexAccountState.SignedOut}
                // Synthetic SDK result enters the real controller; no SDK Activity/login is launched.
                rule.runOnUiThread { model.callbackScope.launch {
                    val attempt=requireNotNull(model.controller.beginSignIn())
                    model.controller.submitSdkToken(attempt,YandexLoginToken.takeSdkResult("SYNTHETIC_TEST_ONLY_BEARER")).join()
                } }
            }
            await{model.state.value==YandexAccountState.Error(expected)}
            assertTrue(runBlocking{prefs.read()}.foregroundRetryRequired)
            assertFalse(model.pendingUnbind.value)
            assertTrue(remoteBound)
            val errorSnapshot=runBlocking{prefs.read()}
            active.value=model
            rule.setContent {
                val current=requireNotNull(active.value)
                CompositionLocalProvider(LocalYandexAccountUi provides YandexAccountUi(current){sdkLaunches.incrementAndGet()}) {
                    RazvorotTheme(force=Atmosphere.DAWN) {
                        LSettingsContent("Пользовательское имя",{},{},{},{},"Мадре",{},24,{},true,{},1,0,
                            ReadingPreferences(app,"l1-recovery-reading-$id"),FamilyBookState.SignedOut,
                            BookDisplaySettings(),{},{},null,initialPage=page)
                    }
                }
            }
            // This exact visible action is missing on immutable 22c65bb (negative-before).
            rule.onNodeWithText(disconnect).performScrollTo().assertIsDisplayed().performClick()
            rule.onNodeWithText("Выйти из Яндекс ID?").assertIsDisplayed()
            rule.onNodeWithText("Остаться").performClick()
            rule.runOnIdle {
                assertEquals(YandexAccountState.Error(expected),model.state.value)
                assertFalse(model.pendingUnbind.value)
                assertEquals(0,unbinds.get());assertEquals(0,sdkLaunches.get())
            }
            assertEquals(errorSnapshot,runBlocking{prefs.read()})
            assertTrue(remoteBound)
            // TapGate uses wall time; advancing only Compose/Looper time cannot open its window.
            Thread.sleep(com.polinalinen.madre.ui.components.TapGate.DEFAULT_WINDOW_MILLIS + 1)
            rule.onNodeWithText(disconnect).performScrollTo().performClick()
            await{rule.onNodeWithText("Выйти из Яндекс ID?").isDisplayed()}
            rule.onNodeWithText("Выйти").assertIsDisplayed().performClick()
            await{model.pendingUnbind.value && unbinds.get()==1}
            await{runBlocking{prefs.read()}.unbindPending}
            rule.onNodeWithText(pendingCopy).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(disconnect).assertDoesNotExist()
            rule.onNodeWithContentDescription("Войти с Яндекс ID").assertDoesNotExist()
            val pending=runBlocking{prefs.read()}
            assertNull(pending.profile);assertFalse(pending.bindingMetadataPresent)
            assertFalse(pending.foregroundRetryRequired);assertTrue(pending.offerSeen)
            assertTrue(remoteBound) // Local SignedOut is NOT a server acknowledgement.
            assertEquals(YandexAccountState.SignedOut,model.state.value)
            // Dispose the VM and DataStore, then read the SAME actual file with new instances.
            rule.runOnUiThread{store.clear()}
            runBlocking{ioScope.cancel();ioScope.coroutineContext.job.join()}
            ioScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            prefs=openPrefs()
            assertTrue(runBlocking{prefs.read()}.unbindPending)
            ReflectionHelpers.setField(app,"yandexAccountStorage\$delegate",lazyOf(prefs))
            store=ViewModelStore();model=newModel()
            rule.runOnIdle{active.value=model}
            await{model.pendingUnbind.value}
            rule.onNodeWithText(pendingCopy).performScrollTo().assertIsDisplayed()
            assertEquals(1,unbinds.get()) // Neither restore nor composition retries implicitly.
            acknowledge=true
            rule.onNodeWithText("Повторить отключение").performScrollTo().performClick()
            await{unbinds.get()==2 && !model.pendingUnbind.value}
            assertFalse(runBlocking{prefs.read()}.unbindPending)
            assertFalse(remoteBound)
            assertEquals(if(restored)0 else 1,binds.get())
            assertEquals(0,sdkLaunches.get())
            assertEquals(membership,setOf("synthetic-family:synthetic-member"))
            assertEquals(roomBefore,roomRows())
            assertTrue(runBlocking{app.bakeHistoryRepository.observeAll().first()}.any{it.id==record})
            assertArrayEquals(photoBytes,photo.readBytes())
            assertEquals(bookBefore,book.all)
        } finally {
            rule.runOnUiThread{store.clear()}
            runBlocking{ioScope.cancel();ioScope.coroutineContext.job.join()}
            http.dispatcher.executorService.shutdown()
        }
    }

    private fun await(condition:()->Boolean) {
        rule.waitUntil(timeoutMillis=10_000) { shadowOf(Looper.getMainLooper()).idle();condition() }
    }

    private fun roomRows():Map<String,List<List<String?>>> = runBlocking(Dispatchers.IO) {
        val db=app.database.openHelper.writableDatabase
        val tables=db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use{cursor->
            buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
        tables.associateWith { name ->
            db.query("SELECT * FROM \"${name.replace("\"","\"\"")}\"").use { cursor ->
                buildList { while(cursor.moveToNext())add((0 until cursor.columnCount).map { column ->
                    if(cursor.isNull(column))null else if(cursor.getType(column)==android.database.Cursor.FIELD_TYPE_BLOB)
                        cursor.getBlob(column).contentToString() else cursor.getString(column)
                }) }.sortedBy{it.toString()}
            }
        }
    }
}
