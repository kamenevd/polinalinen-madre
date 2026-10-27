package com.polinalinen.madre.navigation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.*
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = MadreApplication::class)
class AlbumMissingNoticeNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    private lateinit var nav: NavHostController
    private val factoryCache = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java
        .getDeclaredField("_instance").apply { isAccessible = true }
    private var previousFactory: Any? = null
    @Before fun setup() {
        previousFactory = factoryCache.get(null)
        rule.activityRule.scenario.onActivity { assertSame(app, it.application) }
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        BookPreferences(app).use { it.visit(2) }
    }
    @After fun cleanup() {
        rule.activity.viewModelStore.clear()
        // This synthetic Application must not become the next legacy fixture's default factory.
        factoryCache.set(null, previousFactory)
        assertSame(previousFactory, factoryCache.get(null))
    }
    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }
    private fun album() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Фото 4").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Фото 4").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Источник фото, Все 4").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun filter(label: String) = rule.onNodeWithContentDescription("Источник фото, $label")
    private fun notice() = rule.onNodeWithText("Файл не найден")

    @Test fun actualRoomReferencesStayIntactAndTapRechecksFileBeforeViewerAndBack() {
        val config = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
        val relative = "feeding_photos/notice-live.jpg"
        val jpeg = PhotoStore.resolve(app, relative).apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
        val bytes = jpeg.readBytes()
        val removed = File(app.filesDir, "notice-removed.jpg").apply { writeBytes(bytes) }
        // Existing directory is not a readable file; no permission changes needed.
        val unreadable = File(app.filesDir, "notice-directory").apply { mkdirs() }
        val missing = "feeding_photos/notice-missing.jpg"
        assertFalse(PhotoStore.resolve(app, missing).exists())
        val october = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        val september = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli()
        val ids = runBlocking {
            fun row(path: String, time: Long) = FeedingEntity(sourdoughConfigId = config.id,
                timestampMillis = time, flourGrams = 100, waterGrams = 50,
                storageLocation = StorageLocation.KITCHEN, photoPath = path)
            listOf(app.database.feedingDao().insert(row(missing, october + 2)),
                app.database.feedingDao().insert(row(removed.absolutePath, october + 1)),
                app.database.feedingDao().insert(row(unreadable.absolutePath, september))) to
                app.database.bakeRecordDao().insert(BakeRecordEntity(recipeId = "notice-test", recipeName = "Настоящий хлеб",
                    portions = 1, completedAtMillis = october, photoPath = relative))
        }
        fun rows() = runBlocking {
            app.database.feedingDao().observeHistory(config.id).first() to app.database.bakeRecordDao().observeAll().first()
        }
        val before = rows()
        // Avoid AndroidViewModelFactory's cross-Application singleton in Robolectric.
        rule.activityRule.scenario.onActivity { activity ->
            val provider = androidx.lifecycle.ViewModelProvider(activity, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))
            assertSame(app, provider[com.polinalinen.madre.viewmodel.SourdoughViewModel::class.java].getApplication<MadreApplication>())
            provider[com.polinalinen.madre.viewmodel.BakingViewModel::class.java]
            provider[com.polinalinen.madre.viewmodel.ShelfViewModel::class.java]
            provider[com.polinalinen.madre.viewmodel.FamilyBookViewModel::class.java]
        }
        rule.setContent { nav = rememberNavController(); MadreNavHost(nav) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Дневник", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick()
        album()
        val grid = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        fun index(key: String) = grid.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)
        val keys = listOf("gallery-month:2026-10", "gallery-month:2026-09") +
            ids.first.map { "gallery-record:feeding-$it:0" } + "gallery-record:bake-${ids.second}:0"
        val indices = keys.associateWith { index(it) }
        assertTrue(indices.values.all { it >= 1 })
        filter("Все 4").assertIsSelected(); filter("Закваска 3").assertExists(); filter("Выпечка 1").assertExists()
        rule.onNodeWithTag("gallery-photo-feeding-${ids.first[0]}").performClick()
        notice().assertIsDisplayed(); rule.onNodeWithText("Закрыть").assertDoesNotExist()
        repeat(8) { rule.onNodeWithTag("gallery-photo-feeding-${ids.first[0]}").performClick() }
        // This file existed when its real tile was composed; only tap-time authority is valid.
        rule.onNodeWithTag("gallery-photo-feeding-${ids.first[1]}").assertExists()
        assertTrue(removed.delete())
        rule.onNodeWithTag("gallery-photo-feeding-${ids.first[1]}").performClick()
        notice().assertIsDisplayed(); rule.onNodeWithText("Закрыть").assertDoesNotExist()
        filter("Выпечка 1").performClick().assertIsSelected()
        rule.onNodeWithTag("gallery-photo-bake-${ids.second}").performClick()
        rule.onNodeWithText("Закрыть").assertIsDisplayed()
        rule.onNode(hasContentDescription("Настоящий хлеб") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithText("Закрыть").performClick()
        rule.onNodeWithText("Закрыть").assertDoesNotExist()
        assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
        notice().assertDoesNotExist()
        filter("Все 4").performClick()
        assertEquals(indices, keys.associateWith { index(it) })
        grid.performScrollToIndex(index("gallery-record:feeding-${ids.first[2]}:0"))
        rule.onNodeWithTag("gallery-photo-feeding-${ids.first[2]}").performClick()
        notice().assertIsDisplayed(); rule.onNodeWithText("Закрыть").assertDoesNotExist()
        assertEquals(before, rows()); assertArrayEquals(bytes, jpeg.readBytes())
        assertTrue(unreadable.isDirectory); assertFalse(removed.exists())
        back()
        assertEquals(MadreDestinations.STARTER_DETAIL, nav.currentDestination?.route)
        album()
        notice().assertDoesNotExist()
        rule.mainClock.advanceTimeBy(12_000)
        notice().assertDoesNotExist()
        filter("Все 4").assertIsSelected()
        assertEquals(before, rows()); assertArrayEquals(bytes, jpeg.readBytes())
    }
}
