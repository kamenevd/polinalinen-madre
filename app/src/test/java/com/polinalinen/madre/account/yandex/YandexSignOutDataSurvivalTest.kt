package com.polinalinen.madre.account.yandex

import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Real synthetic Room rows/private image/preferences; never a user's database or live backend. */
@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],application=MadreApplication::class)
class YandexSignOutDataSurvivalTest {
    @Test fun signOutKeepsEveryRoomTablePrivatePhotoAndNonAccountPreferences()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<MadreApplication>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store=PreferenceDataStoreFactory.create(scope=scope,
            produceFile={File(app.cacheDir,"synthetic-account-${UUID.randomUUID()}.preferences_pb")})
        val storage=YandexAccountPrefs(store)
        val profile=VerifiedYandexProfile("synthetic_uid_hash","fixture-login","Синтетическое имя",null,true,1000L)
        var unbinds=0
        val api=object:YandexAccountApi {
            override suspend fun bind(token:YandexLoginToken)=profile
            override suspend fun unbind(){unbinds++}
        }
        val c=YandexAccountController(scope,storage,api)
        try {
            val recipe=app.recipeRepository.getRecipes().first()
            val recordId=app.bakeHistoryRepository.record(recipe.id,recipe.name,2,1_700_000_000_000L)
            val photo=File(app.filesDir,"synthetic-account-surviving-photo.png")
            val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            photo.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val bytes=photo.readBytes()
            val book=app.getSharedPreferences("synthetic-independent-book",0)
            assertTrue(book.edit().putString("owner_name","Пользовательское имя").putString("family_marker","synthetic-family").commit())
            storage.markOfferSeen();storage.storeVerified(profile)
            suspend fun counts():Map<String,Long> = withContext(Dispatchers.IO) {
                val db=app.database.openHelper.writableDatabase
                val tables=db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use{cursor->
                    buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
                tables.associateWith{name->db.query("SELECT COUNT(*) FROM \"${name.replace("\"","\"\"")}\"").use{q->q.moveToFirst();q.getLong(0)}}
            }
            val before=counts()
            // Initialization may have read the empty store before seeding: sign-out must still clear it.
            c.signOut()
            assertEquals(1,unbinds)
            assertEquals(before,counts())
            assertTrue(app.bakeHistoryRepository.observeAll().first().any{it.id==recordId})
            assertArrayEquals(bytes,photo.readBytes())
            assertEquals("Пользовательское имя",book.getString("owner_name",null))
            assertEquals("synthetic-family",book.getString("family_marker",null))
            assertNull(storage.read().profile)
            assertTrue(storage.read().offerSeen)
            assertFalse(storage.read().unbindPending)
        } finally {c.close();scope.cancel();scope.coroutineContext.job.join()}
    }
}
