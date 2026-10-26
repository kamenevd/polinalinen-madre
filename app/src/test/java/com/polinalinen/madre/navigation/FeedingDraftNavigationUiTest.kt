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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.data.db.entities.UserEntity
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveResult
import com.polinalinen.madre.utils.PhotoStore
import com.polinalinen.madre.viewmodel.FeedingSaveState
import com.polinalinen.madre.viewmodel.SourdoughViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = MadreApplication::class)
class FeedingDraftNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var nav: NavHostController
    private val requested = MutableStateFlow(false)
    private var picked: Uri? = null
    private var launches = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            launches++
            check(picked != null)
            dispatchResult(requestCode, picked)
        }
    }
    @Before fun setup() {
        rule.activityRule.scenario.onActivity { assertSame(app, it.application) }
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        // Every method owns fresh synthetic Room rows, including those from a
        // previous method's successful save; never make defaults depend on order.
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.clearAllTables() }
        BookPreferences(app).use { it.visit(2) }
    }
    @After fun cleanup() { rule.activity.viewModelStore.clear() }
    private fun bindController(owners: MutableStateFlow<SourdoughConfigEntity?>? = null,
        save: suspend (SourdoughFeedingSaveRequest) -> SourdoughFeedingSaveResult = app.sourdoughRepository::saveFeeding,
        bootstrap: suspend () -> Unit = {},
        replaceStoredController: Boolean = false,
    ): SourdoughViewModel {
        val initial = owners?.value ?: runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        val controller = SourdoughViewModel(app, loadConfig = { bootstrap(); requireNotNull(initial) },
            observeConfig = { owners ?: app.sourdoughRepository.observeConfig(it) },
            describeWeather = { null }, saveFeeding = save, afterInsert = { _, _, _, _ -> })
        rule.activityRule.scenario.onActivity { activity ->
            if (replaceStoredController) {
                // Replace only this VM. Clearing the entire Activity store before
                // onSaveInstanceState destroys Navigation's own closed-entry state.
                val key = activity.viewModelStore.keys().single { it.endsWith(SourdoughViewModel::class.java.canonicalName!!) }
                activity.viewModelStore.put(key, controller)
            }
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = controller as T
            })[SourdoughViewModel::class.java]
        }
        return controller
    }
    private fun show(diary: Boolean = true): StateRestorationTester {
        rule.activityRule.scenario.onActivity { activity ->
            val controller = ViewModelProvider(activity, ViewModelProvider.AndroidViewModelFactory(app))[SourdoughViewModel::class.java]
            assertSame(app, controller.getApplication<MadreApplication>())
        }
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { nav = rememberNavController(); MadreNavHost(nav, pendingFeeding = requested) }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Дневник", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        if (diary) openDiary()
        return restoration
    }
    private fun openDiary() {
        rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
    }
    private fun openForm() {
        rule.onNodeWithText(if (nav.currentDestination?.route == MadreDestinations.STARTER_DETAIL)
            "+ Покормить" else "Покормить", ignoreCase = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.FEEDING_FORM, nav.currentDestination?.route)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Новая запись").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back(systemBack: Boolean = true) {
        if (systemBack) rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        else {
            rule.onNodeWithTag("paper-list").performScrollToIndex(0)
            rule.onNodeWithContentDescription("Назад").performScrollTo().performClick()
        }
        rule.waitForIdle()
    }
    private fun closeAndReopen(systemBack: Boolean) {
        back(systemBack)
        println("F4 actual close route=${nav.currentDestination?.route}; previous=${nav.previousBackStackEntry?.destination?.route}")
        // The original graph wrongly removes Diary on entry. Still exercise a complete
        // real re-entry to demonstrate actual data loss, not just a stack assertion.
        if (nav.currentDestination?.route == MadreDestinations.HOME) openDiary()
        openForm()
    }
    private fun note() = rule.onNode(hasSetTextAction() and !hasContentDescription("Добавленная мука, точный вес в граммах")
        and !hasContentDescription("Оставленная закваска, точный вес в граммах"))
    private fun assertNote(text: String) = note().assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    private fun chooseCold() {
        rule.onNodeWithTag("paper-list").performScrollToIndex(0)
        rule.onNodeWithContentDescription("1 : 5 : 5 · в холод").performScrollTo().performClick().assertIsSelected()
    }
    private fun save() = rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
    private fun verifyReopen(systemBack: Boolean) {
        show(); openForm()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Мой незаписанный текст")
        rule.onNodeWithContentDescription("Добавленная мука: 100 г").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах").apply {
            performTextClearance(); performTextInput("137")
        }
        closeAndReopen(systemBack)
        rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах").assertTextEquals("137")
        rule.onNode(hasSetTextAction() and !hasContentDescription("Добавленная мука, точный вес в граммах"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Мой незаписанный текст")))
        assertEquals(MadreDestinations.STARTER_DETAIL, nav.previousBackStackEntry?.destination?.route)
    }
    @Test fun headerCloseAndRealReopenRetainPendingInputAndNote() = verifyReopen(false)
    @Test fun systemBackAndRealReopenRetainPendingInputAndNote() = verifyReopen(true)

    @Test fun diaryCountTracksTheActualFeedAndBakeAlbumWithoutChangingRowsOrPhotoBytes() {
        val config = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        val source = File.createTempFile("diary-album-", ".jpg", app.filesDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val bytes = source.readBytes()
        val missing = File(app.filesDir, "diary-album-explicitly-missing.jpg")
        assertFalse(missing.exists())
        val feeding = FeedingEntity(sourdoughConfigId = config.id, timestampMillis = 1_000,
            flourGrams = 100, waterGrams = 50, storageLocation = StorageLocation.KITCHEN,
            photoPath = source.absolutePath)
        runBlocking {
            app.database.feedingDao().insert(feeding)
            app.database.bakeRecordDao().insert(BakeRecordEntity(recipeId = "synthetic-album",
                recipeName = "Пробная выпечка", portions = 1, completedAtMillis = 2_000,
                photoPath = source.absolutePath))
        }
        show()
        fun count(n: Int) {
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Фото $n").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Фото $n").performScrollTo().assertIsEnabled().assertIsDisplayed()
        }
        fun album(n: Int) {
            count(n)
            rule.onNodeWithText("Фото $n").performClick()
            rule.waitForIdle()
            assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
        }
        count(2) // One feeding AND one bake; the same readable path is two entries.
        runBlocking {
            app.database.feedingDao().insert(feeding.copy(timestampMillis = 3_000))
            app.database.bakeRecordDao().insert(BakeRecordEntity(recipeId = "synthetic-missing",
                recipeName = "Нет файла", portions = 1, completedAtMillis = 4_000, photoPath = missing.absolutePath))
            app.database.feedingDao().insert(feeding.copy(photoPath = "  "))
            app.database.feedingDao().insert(feeding.copy(photoPath = null))
            app.database.bakeRecordDao().insert(BakeRecordEntity(recipeId = "synthetic-blank",
                recipeName = "Без фото", portions = 1, photoPath = ""))
        }
        count(4) // Live Room emissions, duplicates retained, blanks excluded, missing file counted.
        val rowsBefore = runBlocking {
            app.database.feedingDao().observeHistory(config.id).first() to app.database.bakeRecordDao().observeAll().first()
        }
        val stored = rowsBefore.first.first { it.photoPath == source.absolutePath }
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-${stored.id}"))
        rule.onNodeWithTag("diary-entry-${stored.id}").onChildAt(0).performClick()
        rule.onNodeWithContentDescription("Фотокарточка кормления").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("diary-entry-${stored.id}").onChildAt(0).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Фотокарточка кормления").assertDoesNotExist()
        album(4)
        rule.onAllNodesWithContentDescription("Кормление · Мадре").assertCountEquals(2)
        rule.onNodeWithContentDescription("Пробная выпечка").assertIsDisplayed()
        rule.onNodeWithText("файл\nне найден").assertIsDisplayed()
        back()
        count(4)
        openForm() // The other action still opens the real feeding route, not the gallery.
        back()
        count(4)
        val rowsAfter = runBlocking {
            app.database.feedingDao().observeHistory(config.id).first() to app.database.bakeRecordDao().observeAll().first()
        }
        assertEquals(rowsBefore, rowsAfter)
        assertArrayEquals(bytes, source.readBytes())
        assertFalse(missing.exists())
    }

    @Test fun zeroCountOpensTheActualEmptyGallery() {
        show()
        rule.onNodeWithText("Фото 0").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
        rule.onNodeWithText("Фотографии появятся здесь после кормления\nили готовой выпечки.").assertIsDisplayed()
        back()
        rule.onNodeWithText("Фото 0").performScrollTo().assertIsEnabled()
    }

    private fun diaryTab(label: String) = rule.onNodeWithContentDescription("Раздел дневника, $label")
    private fun selectDiaryTab(label: String) {
        rule.onNodeWithTag("diary-list").performScrollToNode(hasContentDescription("Раздел дневника, $label"))
        diaryTab(label).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
    }
    private fun assertDiaryTab(label: String) {
        rule.onNodeWithTag("diary-list").performScrollToNode(hasContentDescription("Раздел дневника, $label"))
        diaryTab(label).performScrollTo().assertIsDisplayed().assertIsSelected()
    }

    @Test fun selectedAdviceAndExactDraftSurviveRealFormGalleryBackAndDelayedBootstrapRestore() {
        val owners = MutableStateFlow<SourdoughConfigEntity?>(runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() })
        bindController(owners)
        val restoration = show()
        selectDiaryTab("Советы")
        val diary = nav.currentBackStackEntry
        openForm()
        note().performScrollTo().performTextInput("  Черновик рядом с советами  ")
        rule.onNodeWithContentDescription("Добавленная мука: 100 г").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах").apply {
            performTextClearance(); performTextInput("137")
        }
        back(); assertSame(diary, nav.currentBackStackEntry); assertDiaryTab("Советы")
        rule.onNodeWithText("Фото 0").performScrollTo().performClick()
        rule.waitForIdle(); assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
        back(); assertSame(diary, nav.currentBackStackEntry); assertDiaryTab("Советы")
        openForm(); assertNote("  Черновик рядом с советами  ")
        rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах").assertTextEquals("137")
        back(false); assertDiaryTab("Советы")

        // Recreate the real VM with a deliberately unknown bootstrap owner, not
        // just a same-name screen or a rotation with already available config.
        val release = CompletableDeferred<Unit>()
        val restored = bindController(owners, bootstrap = { release.await() }, replaceStoredController = true)
        restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        assertNull(restored.config.value)
        diaryTab("Советы").assertDoesNotExist()
        rule.runOnIdle { release.complete(Unit) }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); restored.config.value != null }
        assertDiaryTab("Советы")
        openForm(); assertNote("  Черновик рядом с советами  ")
        rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах").assertTextEquals("137")
        assertTrue(runBlocking { app.database.feedingDao().observeHistory(requireNotNull(owners.value).id).first() }.isEmpty())
    }

    @Test fun actualBookAndConfigChangesResetTabEvenAcrossOffscreenOwnerRoundTrip() {
        val (first, other) = realOwners()
        val second = first.copy(id = runBlocking { app.sourdoughRepository.saveConfig(first.copy(id = 0)) })
        assertNotEquals(first.id, second.id); assertNotEquals(first.userId, other.userId)
        val owners = MutableStateFlow<SourdoughConfigEntity?>(first)
        val controller = bindController(owners)
        val restoration = show()
        selectDiaryTab("Советы")
        // Editing a name is NOT an identity change.
        changeOwner(owners, controller, first.copy(name = "Переименована")); assertDiaryTab("Советы")
        changeOwner(owners, controller, second); assertDiaryTab("Журнал")
        selectDiaryTab("Советы")
        changeOwner(owners, controller, first); assertDiaryTab("Журнал")
        selectDiaryTab("Советы")
        openForm(); note().performScrollTo().performTextInput("Не отдавать другой книге")
        changeOwner(owners, controller, other); assertNote("")
        changeOwner(owners, controller, first); assertNote("")
        back(); assertDiaryTab("Журнал")
        selectDiaryTab("Советы")
        rule.onNodeWithText("Фото 0").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
        changeOwner(owners, controller, other)
        changeOwner(owners, controller, first)
        back(); assertDiaryTab("Журнал")
        restoration.emulateSavedInstanceStateRestore(); assertDiaryTab("Журнал")
    }

    @Test fun realGalleryPresetLateHistoryAndClosedRotationKeepTheWholeDraftUntilSuccessfulSave() {
        val saves = mutableListOf<SourdoughFeedingSaveRequest>()
        val controller = bindController(save = { request -> saves += request; app.sourdoughRepository.saveFeeding(request) })
        val restoration = show(); val diaryEntry = nav.currentBackStackEntry
        openForm(); val formEntry = nav.currentBackStackEntry
        chooseCold()
        val source = File.createTempFile("f4-gallery-", ".jpg", app.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val photoDir = PhotoStore.resolve(app, "feeding_photos")
        val beforePhotos = photoDir.listFiles().orEmpty().map { it.name }.toSet()
        picked = Uri.fromFile(source)
        rule.onNodeWithText("вклеить фотокарточку").performScrollTo().performClick()
        rule.onNodeWithText("Галерея").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Фотокарточка, как она ляжет в книгу").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Без оформления").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("заменить фотокарточку").fetchSemanticsNodes().isNotEmpty() }
        val photos = photoDir.listFiles().orEmpty().filter { it.isFile && it.name !in beforePhotos }
        assertEquals(1, photos.size)
        val bytes = photos.single().readBytes()
        val typed = "  Свой текст\nбез потерь  "
        note().performScrollTo().performTextInput(typed)
        back(false); assertSame(diaryEntry, nav.currentBackStackEntry)
        openForm(); assertEquals(formEntry?.id, nav.currentBackStackEntry?.id)
        rule.onNodeWithContentDescription("1 : 5 : 5 · в холод").assertIsSelected()
        rule.onNodeWithContentDescription("Где стоит, Холодильник").assertIsSelected()
        assertNote(typed)
        rule.onNodeWithText("заменить фотокарточку").assertExists()
        rule.onNodeWithContentDescription("Оставленная закваска: 50 г").performScrollTo().performClick()
        fun editor() = rule.onNodeWithContentDescription("Оставленная закваска, точный вес в граммах")
        editor().performTextClearance(); editor().performTextInput("61")
        back(); assertSame(diaryEntry, nav.currentBackStackEntry)
        val config = requireNotNull(controller.config.value)
        runBlocking { app.sourdoughRepository.saveFeeding(SourdoughFeedingSaveRequest(config.id, 24,
            System.currentTimeMillis(), 43, 113, 59, StorageLocation.KITCHEN, "Поздняя история", null, null)) }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.history.value.isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        openForm(); editor().assertTextEquals("61"); assertNote(typed)
        rule.onNodeWithContentDescription("Добавленная мука: 250 г").assertExists()
        rule.onNodeWithContentDescription("Добавленная вода: 250 г").assertExists()
        rule.onNodeWithContentDescription("Где стоит, Холодильник").assertIsSelected()
        rule.onNodeWithText("заменить фотокарточку").assertExists()
        rule.onNodeWithText("записать вес").performScrollTo().performClick()
        save()
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); nav.currentDestination?.route == MadreDestinations.STARTER_DETAIL }
        val saved = saves.single()
        assertEquals(listOf(61, 250, 250), listOf(saved.retainedStarterGrams, saved.flourGrams, saved.waterGrams))
        assertEquals(StorageLocation.FRIDGE, saved.storageLocation); assertEquals(typed.trim(), saved.note)
        assertArrayEquals(bytes, PhotoStore.resolve(app, requireNotNull(saved.photoPath)).readBytes())
        assertEquals(1, launches)
        assertEquals(saved.photoPath, runBlocking { app.sourdoughRepository.lastFeeding(config.id) }?.photoPath)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Фото 1").fetchSemanticsNodes().isNotEmpty() }
        openForm(); assertNotEquals(formEntry?.id, nav.currentBackStackEntry?.id)
        assertNote(""); rule.onNodeWithText("вклеить фотокарточку").assertExists()
        editor().assertDoesNotExist()
    }

    @Test fun savingBlocksRealBackAndFailureRetainsTheDraftAcrossCloseButSuccessClearsIt() {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val controller = bindController(save = { request ->
            attempts++
            if (attempts == 1) { release.await(); error("Синтетическая ошибка памяти") }
            app.sourdoughRepository.saveFeeding(request)
        })
        show(); openForm(); val entry = nav.currentBackStackEntry
        note().performScrollTo().performTextInput("После ошибки остаётся")
        save(); rule.waitForIdle(); assertTrue(controller.saveState.value is FeedingSaveState.Saving)
        back(); assertSame(entry, nav.currentBackStackEntry)
        back(false); assertSame(entry, nav.currentBackStackEntry)
        rule.runOnIdle { release.complete(Unit) }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.saveState.value is FeedingSaveState.Error }
        closeAndReopen(true); assertNote("После ошибки остаётся")
        assertTrue(controller.saveState.value is FeedingSaveState.Idle)
        save()
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); nav.currentDestination?.route == MadreDestinations.STARTER_DETAIL }
        assertEquals(2, attempts)
        openForm(); assertNote("")
        assertEquals("После ошибки остаётся", runBlocking { app.sourdoughRepository.lastFeeding(requireNotNull(controller.config.value).id) }?.notes)
    }

    @Test fun leavingDiaryClearsTheSavedEntryAndDirectHomeNotificationEntryNeverDuplicatesOrRetainsIt() {
        show(); val firstDiary = nav.currentBackStackEntry
        openForm(); note().performScrollTo().performTextInput("До выхода")
        back(); assertSame(firstDiary, nav.currentBackStackEntry)
        back(); assertEquals(MadreDestinations.HOME, nav.currentDestination?.route)
        openDiary(); assertNotEquals(firstDiary?.id, nav.currentBackStackEntry?.id)
        openForm(); assertNote("")
        back(); back()
        openForm(); assertEquals(MadreDestinations.HOME, nav.previousBackStackEntry?.destination?.route)
        note().performScrollTo().performTextInput("Прямой вход")
        val direct = nav.currentBackStackEntry
        rule.runOnIdle { requested.value = true }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); !requested.value }
        assertSame(direct, nav.currentBackStackEntry); assertNote("Прямой вход")
        back(); assertEquals(MadreDestinations.HOME, nav.currentDestination?.route)
        rule.runOnIdle { requested.value = true }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); !requested.value && nav.currentDestination?.route == MadreDestinations.FEEDING_FORM }
        assertEquals(MadreDestinations.HOME, nav.previousBackStackEntry?.destination?.route)
        assertNote("")
        back(); assertEquals(MadreDestinations.HOME, nav.currentDestination?.route)
        assertNull(nav.previousBackStackEntry)
    }

    private fun realOwners(): Pair<SourdoughConfigEntity, SourdoughConfigEntity> = runBlocking {
        val first = app.sourdoughRepository.getOrCreateDefaultConfig()
        val user = app.database.userDao().upsert(UserEntity(name = "Книга B"))
        val second = SourdoughConfigEntity(userId = user, name = "Закваска B")
        first to second.copy(id = app.sourdoughRepository.saveConfig(second))
    }
    private fun changeOwner(owners: MutableStateFlow<SourdoughConfigEntity?>,
        controller: SourdoughViewModel, owner: SourdoughConfigEntity) {
        rule.runOnIdle { owners.value = owner }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.config.value == owner }
        rule.waitForIdle()
    }
    private fun obsoleteSave(returnToA: Boolean, fail: Boolean) {
        val (first, second) = realOwners()
        val owners = MutableStateFlow<SourdoughConfigEntity?>(first)
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<SourdoughFeedingSaveRequest>()
        val controller = bindController(owners, save = { request ->
            requests += request
            if (requests.size == 1) {
                release.await()
                if (fail) error("Ошибка старой формы A")
            }
            app.sourdoughRepository.saveFeeding(request)
        })
        show(); openForm(); val entry = nav.currentBackStackEntry
        note().performScrollTo().performTextInput("Отправленная A")
        save()
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); requests.size == 1 }
        changeOwner(owners, controller, second); assertNote("")
        if (returnToA) { changeOwner(owners, controller, first); assertNote("") }
        val newNote = if (returnToA) "Новая A, не отправленная A" else "Черновик B"
        note().performScrollTo().performTextInput(newNote)
        assertTrue(controller.saveState.value is FeedingSaveState.Saving)
        rule.onNodeWithText("Вписать в дневник").performScrollTo().assertIsNotEnabled().performClick()
        back(); assertSame(entry, nav.currentBackStackEntry)
        back(false); assertSame(entry, nav.currentBackStackEntry)
        assertEquals(1, requests.size)
        rule.runOnIdle { release.complete(Unit) }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.saveState.value !is FeedingSaveState.Saving }
        rule.waitForIdle()
        assertEquals("Old A outcome must not pop the new form", MadreDestinations.FEEDING_FORM, nav.currentDestination?.route)
        assertNote(newNote)
        assertTrue("Obsolete terminal outcome is consumed", controller.saveState.value is FeedingSaveState.Idle)
        rule.onNodeWithText("Ошибка сохранения: Ошибка старой формы A").assertDoesNotExist()
        rule.onNodeWithText("Вписать в дневник").performScrollTo().assertIsEnabled()
        closeAndReopen(true); assertNote(newNote)
        save()
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); nav.currentDestination?.route == MadreDestinations.STARTER_DETAIL }
        assertEquals(2, requests.size)
        val current = if (returnToA) first else second
        assertEquals(first.id, requests.first().configId)
        assertEquals(current.id, requests.last().configId)
        assertEquals(newNote, runBlocking { app.sourdoughRepository.lastFeeding(current.id) }?.notes)
        openForm(); assertNote("")
    }
    @Test fun oldSuccessCannotPopOrClearNewOwnerDraft() = obsoleteSave(returnToA = false, fail = false)
    @Test fun oldSuccessCannotPopOrClearNewGenerationOfTheSameOwner() = obsoleteSave(returnToA = true, fail = false)
    @Test fun oldErrorIsConsumedWithoutLeakingIntoNewOwnerDraft() = obsoleteSave(returnToA = false, fail = true)
    @Test fun oldErrorIsConsumedWithoutLeakingIntoNewGenerationOfTheSameOwner() = obsoleteSave(returnToA = true, fail = true)

    // Delay the actual Room executor, not history values or a production model seam.
    // Existing A rows remain in StateFlow until Room is allowed to query B.
    private fun delayedRoomHistory(manual: Boolean = false, restore: Boolean = false) {
        val (first, second) = realOwners()
        fun insert(config: SourdoughConfigEntity, s: Int, f: Int, w: Int) = runBlocking {
            app.sourdoughRepository.saveFeeding(SourdoughFeedingSaveRequest(config.id, 24,
                System.currentTimeMillis(), s, f, w, StorageLocation.KITCHEN, "История ${config.id}", null, null)).feeding
        }
        val a = insert(first, 41, 131, 61)
        val b = insert(second, 73, 173, 83)
        val owners = MutableStateFlow<SourdoughConfigEntity?>(first)
        val controller = bindController(owners)
        val restoration = show(); openForm()
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.history.value.firstOrNull()?.id == a.id }
        rule.onNodeWithContentDescription("Добавленная мука: 131 г").assertExists()
        val executor = androidx.arch.core.executor.DefaultTaskExecutor()
        val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) { pending.add(runnable) }
            override fun postToMainThread(runnable: Runnable) = executor.postToMainThread(runnable)
            override fun isMainThread() = executor.isMainThread
        })
        try {
            changeOwner(owners, controller, second)
            assertEquals("Real old Room history is still pending replacement", a.id, controller.history.value.single().id)
            rule.onNodeWithContentDescription("Добавленная мука: 100 г").assertExists()
            rule.onNodeWithContentDescription("Оставленная закваска: 50 г").assertExists()
            rule.onNodeWithContentDescription("Добавленная вода: 50 г").assertExists()
            rule.onNodeWithText("Станет в банке 200 г").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Считаем от стартовой гидратации 50% — посчитанных кормлений в дневнике ещё нет").assertExists()
            rule.onNodeWithTag("paper-list").performScrollToIndex(0)
            rule.onNodeWithContentDescription("Как в прошлый раз").performScrollTo().assertIsNotEnabled()
            if (manual) {
                rule.onNodeWithContentDescription("Добавленная мука: 100 г").performScrollTo().performClick()
                rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах").apply {
                    performTextClearance(); performTextInput("157")
                }
            }
            if (restore) restoration.emulateSavedInstanceStateRestore()
        } finally {
            androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
            while (true) executor.executeOnDiskIO(pending.poll() ?: break)
        }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.history.value.firstOrNull()?.id == b.id }
        rule.waitForIdle()
        rule.onNodeWithText("Считаем от прошлой гидратации ${b.finalHydrationPercent}%").performScrollTo().assertIsDisplayed()
        when {
            manual -> {
                rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах").assertTextEquals("157")
                rule.onNodeWithContentDescription("Оставленная закваска: 50 г").assertExists()
            }
            restore -> rule.onNodeWithContentDescription("Добавленная мука: 100 г").assertExists()
            else -> {
                rule.onNodeWithContentDescription("Оставленная закваска: 73 г").assertExists()
                rule.onNodeWithContentDescription("Добавленная мука: 173 г").assertExists()
                rule.onNodeWithContentDescription("Добавленная вода: 83 г").assertExists()
                rule.onNodeWithText("Станет в банке 329 г").assertExists()
                rule.onNodeWithTag("paper-list").performScrollToIndex(0)
                rule.onNodeWithContentDescription("Как в прошлый раз").performScrollTo().assertIsSelected()
            }
        }
        // A -> B -> A starts a new draft rather than reviving either old one.
        note().performScrollTo().performTextInput("Черновик B после истории")
        changeOwner(owners, controller, first); assertNote("")
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.history.value.firstOrNull()?.id == a.id }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Добавленная мука: 131 г").assertExists()
        changeOwner(owners, controller, second); assertNote("")
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.history.value.firstOrNull()?.id == b.id }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Добавленная мука: 173 г").assertExists()
    }
    @Test fun delayedRealRoomHistoryNeverInitializesBFromAAndLateBStillInitializesPristineForm() = delayedRoomHistory()
    @Test fun delayedRealRoomHistoryPreservesManualHistoryFence() = delayedRoomHistory(manual = true)
    @Test fun delayedRealRoomHistoryPreservesRestoredHistoryFence() = delayedRoomHistory(restore = true)

    @Test fun realConfigAndBookOwnerChangesClearActiveAndClosedDraftsWithoutRevivingOnReturn() {
        val first = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        val second = runBlocking {
            val id = app.sourdoughRepository.saveConfig(first.copy(id = 0, name = "Другой конфиг"))
            first.copy(id = id, name = "Другой конфиг")
        }
        val other = runBlocking {
            val user = app.database.userDao().upsert(UserEntity(name = "Другая книга"))
            val id = app.sourdoughRepository.saveConfig(SourdoughConfigEntity(userId = user, name = "Другая закваска"))
            SourdoughConfigEntity(id = id, userId = user, name = "Другая закваска")
        }
        assertNotEquals(first.id, second.id); assertNotEquals(first.userId, other.userId)
        val owners = MutableStateFlow<SourdoughConfigEntity?>(first)
        val controller = bindController(owners)
        val restoration = show(); openForm()
        fun change(owner: SourdoughConfigEntity) {
            rule.runOnIdle { owners.value = owner }
            rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.config.value == owner }
            rule.waitForIdle()
            assertNote("")
        }
        note().performScrollTo().performTextInput("Первый конфиг")
        change(second); note().performScrollTo().performTextInput("Второй конфиг")
        change(first); note().performScrollTo().performTextInput("Первая книга")
        change(other); note().performScrollTo().performTextInput("Другая книга")
        change(first); note().performScrollTo().performTextInput("Закрытый черновик")
        back()
        rule.runOnIdle { owners.value = other }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.config.value == other }
        rule.runOnIdle { owners.value = first }
        rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.config.value == first }
        restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        openForm(); assertNote("")
    }
}
