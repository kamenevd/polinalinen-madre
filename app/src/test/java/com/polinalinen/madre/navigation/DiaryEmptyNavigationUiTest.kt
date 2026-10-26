package com.polinalinen.madre.navigation

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.*
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest
import com.polinalinen.madre.utils.PhotoStore
import com.polinalinen.madre.viewmodel.SourdoughViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.room.withTransaction
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = MadreApplication::class)
class DiaryEmptyNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var vm: SourdoughViewModel
    private lateinit var nav: NavHostController
    private lateinit var config: SourdoughConfigEntity
    private val name = "Бабушкина закваска Мария"
    private val copy get() = "Дневник начнётся с первого кормления. $name ждёт."
    private var elapsed = 0L
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private val saves = mutableListOf<SourdoughFeedingSaveRequest>()
    private var launches = 0
    private lateinit var source: File
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            launches++; dispatchResult(requestCode, Uri.fromFile(source))
        }
    }
    @Before fun setup() {
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.clearAllTables() }
        BookPreferences(app).use { it.visit(2) }
        config = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig().copy(name = name).also {
            app.sourdoughRepository.saveConfig(it)
        } }
        vm = SourdoughViewModel(app, loadConfig = { config }, describeWeather = { null },
            saveFeeding = { request -> saves += request; app.sourdoughRepository.saveFeeding(request) },
            afterInsert = { _, _, _, _ -> }, diaryElapsed = { elapsed }, diaryWait = { wake.receive() })
        rule.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            })[SourdoughViewModel::class.java]
        }
        source = File.createTempFile("diary-first-", ".jpg", app.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(120, 65, 30))
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
    }
    @After fun cleanup() { rule.activity.viewModelStore.clear(); wake.close(); source.delete() }
    private fun rows() = runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.feedingDao().observeHistory(config.id).first() }
    private fun pump() {
        rule.onRoot().fetchSemanticsNode()
        rule.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
    }
    private fun tick(time: Long) {
        rule.runOnIdle { elapsed = time; assertTrue(wake.trySend(Unit).isSuccess) }; rule.waitForIdle()
    }
    private fun tab(label: String) {
        rule.onNodeWithTag("diary-list").performScrollToNode(hasContentDescription("Раздел дневника, $label"))
        rule.onNodeWithContentDescription("Раздел дневника, $label").performScrollTo().performClick().assertIsSelected()
    }
    private fun empty() {
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-empty"))
        rule.onNodeWithText(copy).assertExists()
        rule.onNodeWithTag("diary-empty-feed").onChildAt(0).performScrollTo().assertIsDisplayed().assertIsEnabled()
    }
    private fun noFalseEmpty() {
        rule.onNodeWithTag("diary-list").performScrollToIndex(2)
        assertEquals("no offscreen empty item either", -1,
            rule.onNodeWithTag("diary-list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.IndexForKey]("diary-empty"))
        rule.onNodeWithTag("diary-empty").assertDoesNotExist()
        rule.onNodeWithText(copy).assertDoesNotExist()
    }
    @Test fun firstRealFeedFromEmptySavesOneRoomRowAndExactJpegThenPendingUndoAndFinalDeleteRespectEmptyTruth() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { nav = rememberNavController(); MadreNavHost(nav) }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Дневник", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
        val diary = nav.currentBackStackEntry
        assertTrue(rows().isEmpty()); empty()
        tab("Советы")
        rule.onNodeWithText("В буклете Levito Madre", substring = true).assertExists()
        rule.onNodeWithTag("diary-empty").assertDoesNotExist()
        tab("Журнал"); empty()
        rule.onNodeWithTag("diary-empty-feed").onChildAt(0).performClick(); rule.waitForIdle()
        assertEquals(MadreDestinations.FEEDING_FORM, nav.currentDestination?.route)
        assertSame(diary, nav.previousBackStackEntry)
        rule.onNodeWithText("Новая запись").assertExists()
        val beforePhotos = PhotoStore.resolve(app, "feeding_photos").listFiles().orEmpty().map { it.name }.toSet()
        rule.onNodeWithText("вклеить фотокарточку").performScrollTo().performClick()
        rule.onNodeWithText("Галерея").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Фотокарточка, как она ляжет в книгу").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Без оформления").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("заменить фотокарточку").fetchSemanticsNodes().isNotEmpty() }
        val photos = PhotoStore.resolve(app, "feeding_photos").listFiles().orEmpty().filter { it.isFile && it.name !in beforePhotos }
        assertEquals(1, photos.size)
        val jpeg = photos.single(); val bytes = jpeg.readBytes()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Первая своя запись")
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        rule.waitUntil(10_000) { pump(); nav.currentDestination?.route == MadreDestinations.STARTER_DETAIL && vm.history.value.size == 1 }
        assertSame(diary, nav.currentBackStackEntry)
        assertEquals(1, saves.size); assertEquals(1, launches)
        val row = rows().single()
        assertEquals("Первая своя запись", row.notes)
        assertEquals(listOf(50, 100, 50), listOf(row.retainedStarterGrams, row.flourGrams, row.waterGrams))
        assertEquals(saves.single().photoPath, row.photoPath)
        assertEquals(jpeg.canonicalFile, PhotoStore.resolve(app, requireNotNull(row.photoPath)).canonicalFile)
        assertArrayEquals(bytes, jpeg.readBytes())
        val decoded = java.util.concurrent.CompletableFuture<coil.request.ImageResult>()
        app.imageLoader.enqueue(coil.request.ImageRequest.Builder(app).data(jpeg).size(32).listener(
            onSuccess = { _, result -> decoded.complete(result) }, onError = { _, result -> decoded.complete(result) }).build())
        rule.waitUntil(10_000) { pump(); decoded.isDone }
        assertTrue("actual JPEG decode: ${decoded.get()}", decoded.get() is coil.request.SuccessResult)
        assertEquals(32, (decoded.get() as coil.request.SuccessResult).drawable.intrinsicWidth)
        fun intact() { assertEquals(listOf(row), rows()); assertEquals(listOf(row), vm.history.value); assertArrayEquals(bytes, jpeg.readBytes()) }
        fun swipe() {
            rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${row.id}"))
            rule.onNodeWithTag("diary-entry-${row.id}").performTouchInput { swipeLeft() }; rule.waitForIdle()
        }
        noFalseEmpty(); swipe(); noFalseEmpty(); intact()
        rule.onNodeWithTag("diary-entry-${row.id}").assertDoesNotExist()
        tick(4_999); noFalseEmpty(); intact()
        restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        noFalseEmpty(); intact()
        rule.onNodeWithTag("diary-delete-undo").onChildAt(0).assertIsDisplayed().assertIsEnabled().performClick()
        rule.waitForIdle(); assertNull(vm.diaryDeletion.state.value.pending); intact()
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${row.id}"))
        rule.onNodeWithTag("diary-entry-${row.id}").assertIsDisplayed()
        tick(5_000); swipe(); noFalseEmpty(); intact()
        tick(9_999); noFalseEmpty(); intact()
        // Hold a real Room transaction with only the saved row still present.
        // At expiry the UI must stop Undo but must not promise a first feeding
        // while DELETE is waiting for Room. No fake history or fake delete result.
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val held = scope.launch { app.database.withTransaction {
            assertEquals(row, app.database.feedingDao().getNow(row.id))
            entered.complete(Unit); release.await()
        } }
        try {
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { entered.await() } }
            tick(10_000)
            rule.waitUntil(10_000) { pump(); vm.diaryDeletion.state.value.pending?.committing == true }
            noFalseEmpty(); assertEquals(listOf(row), vm.history.value)
            rule.onNodeWithTag("diary-delete-undo").assertDoesNotExist()
            rule.onNodeWithText("Удаляем запись…").assertIsDisplayed()
            assertArrayEquals(bytes, jpeg.readBytes()); assertTrue(held.isActive)
        } finally {
            release.complete(Unit)
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { held.join() } }
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
        rule.waitUntil(10_000) { pump(); rows().isEmpty() && vm.history.value.isEmpty() && vm.diaryDeletion.state.value.pending == null }
        empty(); rule.onNodeWithTag("diary-delete-undo").assertDoesNotExist()
        assertNull(runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.sourdoughConfigDao().getNow(config.id) }!!.lastFeedingMillis)
        assertArrayEquals(bytes, jpeg.readBytes()); assertEquals(1, saves.size)
        tab("Советы"); rule.onNodeWithTag("diary-empty").assertDoesNotExist()
        rule.onNodeWithText("В буклете Levito Madre", substring = true).assertExists()
    }
}
