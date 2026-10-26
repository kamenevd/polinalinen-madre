package com.polinalinen.madre.navigation

import android.graphics.Bitmap
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
import com.polinalinen.madre.viewmodel.SourdoughViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
class AlbumSourceNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var nav: NavHostController

    @Before fun setup() {
        rule.activityRule.scenario.onActivity { assertSame(app, it.application) }
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        BookPreferences(app).use { it.visit(2) }
    }
    @After fun cleanup() { rule.activity.viewModelStore.clear() }

    // Same real Activity/NavHost/Room and delayed-VM bootstrap fixture as F4;
    // all existing F4 test bytes and assertions remain untouched.
    private fun bind(owners: MutableStateFlow<SourdoughConfigEntity?>,
        bootstrap: suspend () -> Unit = {}, replace: Boolean = false): SourdoughViewModel {
        val initial = requireNotNull(owners.value)
        val controller = SourdoughViewModel(app, loadConfig = { bootstrap(); initial },
            observeConfig = { owners }, describeWeather = { null }, afterInsert = { _, _, _, _ -> })
        rule.activityRule.scenario.onActivity { activity ->
            if (replace) {
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
    private fun show(): StateRestorationTester {
        rule.activityRule.scenario.onActivity {
            assertSame(app, ViewModelProvider(it, ViewModelProvider.AndroidViewModelFactory(app))
                [SourdoughViewModel::class.java].getApplication<MadreApplication>())
        }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { nav = rememberNavController(); MadreNavHost(nav) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Дневник", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
        return restoration
    }
    private fun album(count: Int) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Фото $count").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Фото $count").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
    }
    private fun filter(label: String) = rule.onNodeWithContentDescription("Источник фото, $label")
    private fun waitFilter(label: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Источник фото, $label").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }
    private fun feeding(config: SourdoughConfigEntity, path: String?, time: Long = 1_000) =
        FeedingEntity(sourdoughConfigId = config.id, timestampMillis = time, flourGrams = 100,
            waterGrams = 50, storageLocation = StorageLocation.KITCHEN, photoPath = path)
    private fun bake(path: String?, time: Long = 2_000) = BakeRecordEntity(recipeId = "synthetic-album",
        recipeName = "Кормление · Закваска", portions = 1, completedAtMillis = time, photoPath = path)

    @Test fun realProducersKeepSharedJpegMissingReferencesCaptionsOrderAndReactiveEmptyFilters() {
        val config = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        val other = config.copy(id = runBlocking { app.sourdoughRepository.saveConfig(config.copy(id = 0)) })
        val source = File.createTempFile("feeding-bake-", ".jpg", app.filesDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val bytes = source.readBytes()
        val missing = File(app.filesDir, "feeding-bake-missing.jpg").absolutePath
        assertFalse(File(missing).exists())
        val ids = runBlocking {
            val f = app.database.feedingDao().insert(feeding(config, source.absolutePath, 4_000))
            val b = app.database.bakeRecordDao().insert(bake(source.absolutePath, 3_000))
            val m = app.database.feedingDao().insert(feeding(config, missing, 2_000))
            app.database.bakeRecordDao().insert(bake(missing, 1_000))
            for (blank in listOf(null, "", "  ")) {
                app.database.feedingDao().insert(feeding(config, blank))
                app.database.bakeRecordDao().insert(bake(blank))
            }
            app.database.feedingDao().insert(feeding(other, source.absolutePath))
            Triple(f, b, m)
        }
        fun rows() = runBlocking {
            Triple(app.database.feedingDao().observeHistory(config.id).first(),
                app.database.feedingDao().observeHistory(other.id).first(), app.database.bakeRecordDao().observeAll().first())
        }
        val before = rows()
        show(); album(4)
        filter("Все 4").assertIsSelected()
        filter("Закваска 2").assertExists(); filter("Выпечка 2").assertExists()
        val feedBounds = rule.onNodeWithTag("gallery-photo-feeding-${ids.first}").fetchSemanticsNode().boundsInRoot
        val bakeBounds = rule.onNodeWithTag("gallery-photo-bake-${ids.second}").fetchSemanticsNode().boundsInRoot
        assertTrue(feedBounds.top < bakeBounds.top || feedBounds.left < bakeBounds.left)
        filter("Закваска 2").performClick().assertIsSelected()
        rule.onNodeWithTag("gallery-photo-bake-${ids.second}").assertDoesNotExist()
        rule.onNodeWithContentDescription("Кормление · Мадре").assertIsDisplayed()
        rule.onNodeWithTag("gallery-photo-feeding-${ids.first}").performClick()
        rule.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
        rule.onNodeWithTag("gallery-photo-feeding-${ids.third}").performClick()
        rule.onNodeWithText("Закрыть").assertDoesNotExist()
        filter("Выпечка 2").performClick().assertIsSelected()
        rule.onNodeWithTag("gallery-photo-feeding-${ids.first}").assertDoesNotExist()
        rule.onNodeWithContentDescription("Кормление · Закваска").assertIsDisplayed()
        assertEquals(before, rows()); assertArrayEquals(bytes, source.readBytes())
        assertFalse(File(missing).exists())

        // True Room changes, not mutable projected lists: filter stays selected.
        runBlocking {
            before.third.filter { !it.photoPath.isNullOrBlank() }.forEach { app.database.bakeRecordDao().attachPhoto(it.id, "  ") }
        }
        waitFilter("Выпечка 0"); filter("Выпечка 0").assertIsSelected()
        rule.onNodeWithText("В этом разделе пока нет фотографий.").assertIsDisplayed()
        runBlocking { app.database.bakeRecordDao().attachPhoto(ids.second, source.absolutePath) }
        waitFilter("Выпечка 1"); filter("Выпечка 1").assertIsSelected()
        rule.onNodeWithTag("gallery-photo-bake-${ids.second}").assertIsDisplayed()
        runBlocking { app.database.feedingDao().insert(feeding(config, missing, 5_000)) }
        waitFilter("Закваска 3"); filter("Все 4").assertExists()
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun sameEntryRestoreDelayedOwnerAndRealOwnerAbaResetOnlyAlbumSelection() {
        val first = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        val user = runBlocking { app.database.userDao().upsert(UserEntity(name = "Книга B")) }
        val other = SourdoughConfigEntity(userId = user, name = "Другая")
            .let { it.copy(id = runBlocking { app.sourdoughRepository.saveConfig(it) }) }
        val sameUserOtherConfig = first.copy(id = runBlocking { app.sourdoughRepository.saveConfig(first.copy(id = 0)) })
        runBlocking {
            app.database.feedingDao().insert(feeding(first, "bake-missing.jpg"))
            app.database.feedingDao().insert(feeding(other, "feeding-missing.jpg"))
            app.database.bakeRecordDao().insert(bake("feeding-missing.jpg"))
        }
        val owners = MutableStateFlow<SourdoughConfigEntity?>(first)
        var controller = bind(owners)
        val restoration = show(); album(2)
        val entryId = requireNotNull(nav.currentBackStackEntry).id
        filter("Выпечка 1").performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(entryId, nav.currentBackStackEntry?.id)
        filter("Выпечка 1").assertIsSelected()

        val release = CompletableDeferred<Unit>()
        controller = bind(owners, bootstrap = { release.await() }, replace = true)
        restoration.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        assertNull(controller.config.value)
        rule.runOnIdle { release.complete(Unit) }
        waitFilter("Закваска 1")
        assertEquals(entryId, nav.currentBackStackEntry?.id)
        filter("Выпечка 1").assertIsSelected()
        fun change(owner: SourdoughConfigEntity) {
            rule.runOnIdle { owners.value = owner }
            rule.waitUntil(10_000) { rule.onRoot().fetchSemanticsNode(); controller.config.value == owner }
            rule.waitForIdle()
        }
        change(first.copy(name = "Переименована")); filter("Выпечка 1").assertIsSelected()
        change(sameUserOtherConfig); filter("Все 1").assertIsSelected()
        change(first); waitFilter("Все 2"); filter("Все 2").assertIsSelected()
        filter("Выпечка 1").performClick()
        change(other); waitFilter("Все 2"); filter("Все 2").assertIsSelected()
        filter("Выпечка 1").performClick()
        change(first); filter("Все 2").assertIsSelected()
        filter("Выпечка 1").performClick()
        // Keep the actual album entry on stack while hidden: no resurrection in ABA.
        rule.runOnIdle { nav.navigate(MadreDestinations.HOME) }; rule.waitForIdle()
        change(other); change(first)
        rule.runOnIdle { nav.popBackStack() }; rule.waitForIdle()
        assertEquals(entryId, nav.currentBackStackEntry?.id)
        filter("Все 2").assertIsSelected()
        filter("Выпечка 1").performClick()
        back(); album(2)
        assertNotEquals(entryId, nav.currentBackStackEntry?.id)
        filter("Все 2").assertIsSelected()
    }
}
