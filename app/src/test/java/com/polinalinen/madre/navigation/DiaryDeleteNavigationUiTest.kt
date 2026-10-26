package com.polinalinen.madre.navigation

import android.graphics.Bitmap
import coil.imageLoader
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.*
import com.polinalinen.madre.sourdough.*
import com.polinalinen.madre.viewmodel.SourdoughViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = MadreApplication::class)
class DiaryDeleteNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var nav: NavHostController
    private lateinit var vm: SourdoughViewModel
    private lateinit var config: SourdoughConfigEntity
    private lateinit var value: FeedingEntity
    private lateinit var owners: MutableStateFlow<SourdoughConfigEntity?>
    private lateinit var jpeg: File
    private lateinit var bytes: ByteArray
    private var elapsed = 0L
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    @Before fun setup() {
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.clearAllTables() }
        BookPreferences(app).use { it.visit(2) }
        config = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        jpeg = File(app.filesDir, "feeding_photos/d2-nav.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle(); bytes = jpeg.readBytes()
        val seed = FeedingEntity(sourdoughConfigId = config.id, timestampMillis = 1_750_000_000_000,
            flourGrams = 113, waterGrams = 59, retainedStarterGrams = 47, storageLocation = StorageLocation.FRIDGE,
            photoPath = "feeding_photos/d2-nav.jpg", notes = "Своя заметка", hydrationPercent = 55,
            starterObservation = "Наблюдение", observedAtMillis = 321, finalHydrationPercent = 67, generatedComment = "Снимок")
        value = runBlocking { seed.copy(id = app.database.feedingDao().insert(seed)).also {
            app.database.sourdoughConfigDao().updateLastFeeding(config.id, it.timestampMillis)
        } }
        config = config.copy(lastFeedingMillis = value.timestampMillis)
        owners = MutableStateFlow(config)
        vm = controller()
        rule.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            })[SourdoughViewModel::class.java]
        }
    }
    private fun controller() = SourdoughViewModel(app, loadConfig = { config }, observeConfig = { owners },
        describeWeather = { null }, afterInsert = { _, _, _, _ -> }, diaryElapsed = { elapsed }, diaryWait = { wake.receive() })
    @After fun cleanup() { rule.activity.viewModelStore.clear(); wake.close(); jpeg.delete() }
    private fun show(): StateRestorationTester {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { nav = rememberNavController(); MadreNavHost(nav) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Дневник", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${value.id}"))
        return restoration
    }
    private fun swipe() { rule.onNodeWithTag("diary-entry-${value.id}").performTouchInput { swipeLeft() }; rule.waitForIdle() }
    private fun stored() = runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.feedingDao().getNow(value.id) }
    private fun tick(time: Long) { rule.runOnIdle { elapsed = time; assertTrue(wake.trySend(Unit).isSuccess) }; rule.waitForIdle() }
    private fun intact() { assertEquals(value, stored()); assertArrayEquals(bytes, jpeg.readBytes()) }
    @Test fun anotherRowsRealJpegCannotCoverUndoAndViewerReturnsAfterUndoAndCompletion() {
        val otherJpeg = File(app.filesDir, "feeding_photos/d2-other.jpg").apply { writeBytes(bytes) }
        try {
            val seed = value.copy(id = 0, timestampMillis = value.timestampMillis - 60_000,
                photoPath = "feeding_photos/d2-other.jpg", notes = "Другая запись")
            val other = runBlocking { seed.copy(id = app.database.feedingDao().insert(seed)) }
            val decoded = java.util.concurrent.CompletableFuture<coil.request.ImageResult>()
            app.imageLoader.enqueue(coil.request.ImageRequest.Builder(app).data(otherJpeg).size(16).listener(
                onSuccess = { _, result -> decoded.complete(result) },
                onError = { _, result -> decoded.complete(result) }).build())
            rule.waitUntil(10_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); decoded.isDone
            }
            assertTrue("actual JPEG must decode, not a missing-file fallback: ${decoded.get()}", decoded.get() is coil.request.SuccessResult)
            assertEquals(16, (decoded.get() as coil.request.SuccessResult).drawable.intrinsicWidth)
            show(); swipe(); tick(4_999); intact()
            rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${other.id}"))
            rule.onNodeWithTag("diary-entry-${other.id}").onChildAt(0).performClick()
            val photo = rule.onNodeWithContentDescription("Фотокарточка кормления")
            photo.performScrollTo().assertIsDisplayed().performClick(); rule.waitForIdle()
            assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
            rule.onNodeWithText("Закрыть").assertDoesNotExist()
            rule.onNodeWithTag("diary-delete-undo").onChildAt(0).assertIsDisplayed().assertIsEnabled().performClick()
            rule.waitForIdle(); assertNull(vm.diaryDeletion.state.value.pending); intact()
            photo.performScrollTo().assertIsDisplayed().performClick()
            rule.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
            rule.onNodeWithText("Закрыть").assertDoesNotExist()
            rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${value.id}"))
            swipe(); tick(9_999)
            rule.waitUntil(10_000) { stored() == null && vm.diaryDeletion.state.value.pending == null }
            rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${other.id}"))
            photo.performScrollTo().assertIsDisplayed().performClick()
            rule.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
            assertEquals(other, runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.feedingDao().getNow(other.id) })
            assertArrayEquals(bytes, otherJpeg.readBytes()); assertArrayEquals(bytes, jpeg.readBytes())
        } finally { otherJpeg.delete() }
    }
    @Test fun exactDeadlineStopsPromisingUndoWhileRealRoomFeedIsHeldAndLeavingStillCancels() {
        show(); swipe(); tick(4_999); intact()
        rule.onNodeWithTag("diary-delete-undo").onChildAt(0).assertIsDisplayed().assertIsEnabled()
        val inserted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val concurrent = com.polinalinen.madre.data.repository.SourdoughRepository(app.database,
            onPostInsert = { inserted.complete(Unit); release.await() })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val job = scope.launch { concurrent.saveFeeding(com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest(
            config.id, 48, 700, 51, 101, 53, StorageLocation.KITCHEN, "Удержанное кормление", null, null)) }
        try {
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { inserted.await() } }
            tick(5_000)
            assertFalse("feed transaction is still held at UI assertion", release.isCompleted)
            assertTrue("real feed is not committed yet", job.isActive)
            rule.onNodeWithTag("diary-delete-undo").assertDoesNotExist()
            rule.onNodeWithText("Запись скрыта. Можно вернуть в течение 5 секунд.").assertDoesNotExist()
            rule.onNodeWithText("Удаляем запись…").assertIsDisplayed()
            assertTrue(vm.diaryDeletion.state.value.pending!!.committing)
            assertFalse(vm.diaryDeletion.undo(vm.diaryDeletion.state.value.pending!!.token))
            // Expired does not mean authorized: leaving must still cancel before Room is acquired.
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
            assertEquals(MadreDestinations.HOME, nav.currentDestination?.route)
            assertNull(vm.diaryDeletion.state.value.pending)
            assertFalse("no UI-owner lock may wait for Room", release.isCompleted)
        } finally {
            release.complete(Unit)
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { job.join() } }
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
        tick(10_000); intact()
        assertEquals(700L, runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.sourdoughConfigDao().getNow(config.id) }!!.lastFeedingMillis)
        assertEquals(2, runBlocking { app.database.feedingDao().observeHistory(config.id).first() }.size)
    }
    @Test fun realGestureRoomUndoAt4999AndRotationKeepSameSnapshotWithoutFilteringAuthoritativeHistory() {
        val restoration = show(); swipe()
        rule.onNodeWithTag("diary-entry-${value.id}").assertDoesNotExist()
        intact(); assertEquals(listOf(value), vm.history.value)
        rule.onNodeWithTag("diary-list").performScrollToIndex(0)
        rule.onNodeWithTag("diary-hydration").assertTextEquals("гидратация 67%")
        rule.onNodeWithTag("diary-feeding-note").assertContentDescriptionContains("Закваска: 47 г. Мука: 113 г. Вода: 59 г. Хранение: холодильник.", substring = true)
        tick(4_999); intact()
        val pending = vm.diaryDeletion.state.value.pending!!
        restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        assertEquals(pending, vm.diaryDeletion.state.value.pending)
        rule.onNodeWithTag("diary-delete-undo").onChildAt(0).assertIsDisplayed().performClick()
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${value.id}"))
        rule.onNodeWithTag("diary-entry-${value.id}").assertIsDisplayed(); intact()
        tick(10_000); intact(); assertNull(vm.diaryDeletion.state.value.pending)
    }
    @Test fun visibleDeleteAt5000ActuallyDeletesRoomAndNullsLastWithoutDeletingJpeg() {
        show()
        rule.onNodeWithTag("diary-entry-${value.id}").onChildAt(0).performClick()
        rule.onNodeWithTag("diary-delete-${value.id}").onChildAt(0).performScrollTo().performClick()
        rule.waitForIdle(); tick(4_999); intact(); tick(5_000)
        rule.waitUntil(10_000) { stored() == null }
        rule.waitUntil(10_000) { vm.diaryDeletion.state.value.pending == null }
        assertNull(runBlocking { app.database.sourdoughConfigDao().observeForUser(config.userId).first() }!!.lastFeedingMillis)
        assertArrayEquals(bytes, jpeg.readBytes()); rule.onNodeWithTag("diary-delete-undo").assertDoesNotExist()
    }
    @Test fun realDiaryExitCancelsAndReentryDoesNotReviveUndoOrDeleteLater() {
        show(); swipe(); val token = vm.diaryDeletion.state.value.pending!!.token
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(MadreDestinations.HOME, nav.currentDestination?.route)
        assertNull(vm.diaryDeletion.state.value.pending); tick(10_000); intact(); assertFalse(vm.diaryDeletion.undo(token))
        rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${value.id}"))
        rule.onNodeWithTag("diary-entry-${value.id}").assertIsDisplayed(); rule.onNodeWithTag("diary-delete-undo").assertDoesNotExist()
    }
    @Test fun actualOwnerABACancelsPendingAndFencesOldUndoAcrossNavHost() {
        show(); val authority = vm.diaryDeletion.state.value.authority; swipe(); val pending = vm.diaryDeletion.state.value.pending!!
        val b = runBlocking {
            val user = app.database.userDao().upsert(UserEntity(name = "Другая книга", isActive = false))
            val id = app.database.sourdoughConfigDao().upsert(SourdoughConfigEntity(userId = user))
            SourdoughConfigEntity(id = id, userId = user)
        }
        rule.runOnIdle { owners.value = b }; rule.waitUntil(10_000) { vm.config.value == b }
        rule.runOnIdle { owners.value = config }; rule.waitUntil(10_000) { vm.config.value == config }
        rule.waitForIdle(); assertNull(vm.diaryDeletion.state.value.pending)
        assertFalse(vm.diaryDeletion.undo(pending.token)); tick(10_000); intact()
        assertFalse(vm.diaryDeletion.queue(value, authority))
    }
    @Test fun enteringFormOrAlbumCancelsRatherThanLeavingAnInaccessibleDestructiveTimer() {
        show(); swipe()
        rule.onNodeWithTag("diary-list").performScrollToNode(hasText("+ Покормить"))
        rule.onNodeWithText("+ Покормить").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(MadreDestinations.FEEDING_FORM, nav.currentDestination?.route)
        assertNull(vm.diaryDeletion.state.value.pending); assertEquals(listOf(value), vm.history.value)
        rule.onNodeWithText("Считаем от прошлой гидратации 67%").assertExists()
        tick(10_000); intact()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${value.id}")); swipe()
        rule.onNodeWithTag("diary-list").performScrollToNode(hasText("Фото 1"))
        rule.onNodeWithText("Фото 1").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
        assertNull(vm.diaryDeletion.state.value.pending); tick(20_000); intact()
    }
    @Test fun replacementVmAfterDeathRestoresRowNotHiddenTimerOrUndo() {
        val restoration = show(); swipe(); val token = vm.diaryDeletion.state.value.pending!!.token
        rule.activityRule.scenario.onActivity { activity ->
            val key = activity.viewModelStore.keys().single { it.endsWith(SourdoughViewModel::class.java.canonicalName!!) }
            vm = controller(); activity.viewModelStore.put(key, vm)
        }
        restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle(); tick(10_000)
        assertNull(vm.diaryDeletion.state.value.pending); assertFalse(vm.diaryDeletion.undo(token)); intact()
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${value.id}"))
        rule.onNodeWithTag("diary-entry-${value.id}").assertIsDisplayed()
    }
    @Test fun existingBakeUndoAndDiaryUndoAreBothAccessibleAndIndependentOfSnackbarQueue() {
        show()
        lateinit var bake: com.polinalinen.madre.viewmodel.BakingViewModel
        rule.activityRule.scenario.onActivity { activity ->
            bake = ViewModelProvider(activity, ViewModelProvider.AndroidViewModelFactory(app))[com.polinalinen.madre.viewmodel.BakingViewModel::class.java]
        }
        var id: Long? = null
        var finished = false
        rule.runOnIdle { bake.viewModelScope.launch {
            id = bake.startBakingWithUndo(com.polinalinen.madre.viewmodel.fakes.sampleRecipe(), 2.0)
            finished = true
        } }
        rule.waitUntil(10_000) {
            rule.onRoot().fetchSemanticsNode()
            rule.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            finished
        }
        assertNotNull("real bake start: ${bake.mutationError.value}", id)
        assertNotNull("real bake start must offer Undo", bake.undo.value)
        val undo = bake.undo.value!!
        rule.waitUntil(10_000) { rule.onAllNodesWithText("ОТМЕНИТЬ").fetchSemanticsNodes().isNotEmpty() }
        swipe(); val pending = vm.diaryDeletion.state.value.pending!!
        val feedingAction = rule.onNodeWithTag("diary-delete-undo").onChildAt(0).assertIsDisplayed()
        val bakeAction = rule.onNodeWithText("ОТМЕНИТЬ").assertIsDisplayed()
        assertTrue("existing bake Undo must not cover Diary Undo", bakeAction.fetchSemanticsNode().boundsInRoot.bottom <=
            rule.onNodeWithTag("diary-delete-status").fetchSemanticsNode().boundsInRoot.top)
        feedingAction.assertIsEnabled(); tick(4_999); intact()
        assertEquals(pending, vm.diaryDeletion.state.value.pending); assertEquals(undo, bake.undo.value)
        bakeAction.performClick()
        rule.waitUntil(10_000) {
            rule.onRoot().fetchSemanticsNode()
            rule.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            bake.session(requireNotNull(id)) == null
        }
        assertNull(runBlocking { app.activeBakeRepository.all().find { it.sessionId == id } })
        assertEquals(pending, vm.diaryDeletion.state.value.pending); intact()
        tick(5_000); rule.waitUntil(10_000) { stored() == null }
        assertArrayEquals(bytes, jpeg.readBytes())
    }
}
