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
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class AlbumMonthNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MadreApplication>()
    @Test fun persistedFeedingAndBakeTimestampsDriveMonthsWithoutChangingRowsOrJpeg() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            rule.activityRule.scenario.onActivity { assertSame(app, it.application) }
            runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
            runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
            BookPreferences(app).use { it.visit(2) }
            val config = runBlocking { app.sourdoughRepository.getOrCreateDefaultConfig() }
            val jpeg = File(app.filesDir, "feeding_photos/month-room.jpg").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888)
            jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
            val bytes = jpeg.readBytes()
            fun feeding(time: String, path: String) = FeedingEntity(sourdoughConfigId = config.id,
                timestampMillis = Instant.parse(time).toEpochMilli(), flourGrams = 100, waterGrams = 50,
                storageLocation = StorageLocation.KITCHEN, photoPath = path)
            val ids = runBlocking {
                val first = app.database.feedingDao().insert(feeding("2026-03-01T00:00:00Z", "feeding_photos/month-room.jpg"))
                val missing = app.database.feedingDao().insert(feeding("2025-03-01T00:00:00Z", "missing-month.jpg"))
                val bake = app.database.bakeRecordDao().insert(BakeRecordEntity(recipeId = "month-test",
                    recipeName = "Реальная выпечка", portions = 1,
                    completedAtMillis = Instant.parse("2026-03-01T08:00:00Z").toEpochMilli(),
                    photoPath = "feeding_photos/month-room.jpg"))
                Triple(first, missing, bake)
            }
            fun rows() = runBlocking {
                app.database.feedingDao().observeHistory(config.id).first() to app.database.bakeRecordDao().observeAll().first()
            }
            val before = rows()
            lateinit var nav: NavHostController
            rule.setContent { nav = rememberNavController(); MadreNavHost(nav) }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Дневник", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Дневник", ignoreCase = true).performScrollTo().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Фото 3").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Фото 3").performScrollTo().performClick()
            rule.waitForIdle(); assertEquals(MadreDestinations.PHOTO_GALLERY, nav.currentDestination?.route)
            fun grid() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            fun index(key: String) = grid().fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)
            fun filter(label: String) = rule.onNodeWithContentDescription("Источник фото, $label")
            assertEquals(1, index("gallery-month:2026-03"))
            assertEquals(3, index("gallery-month:2026-02"))
            assertEquals(5, index("gallery-month:2025-02"))
            rule.onNodeWithTag("gallery-month-2026-03").assertTextEquals("МАРТ 2026")
            filter("Закваска 2").performClick().assertIsSelected()
            assertEquals(-1, index("gallery-month:2026-03"))
            rule.onNodeWithTag("gallery-month-2026-02").assertTextEquals("ФЕВРАЛЬ 2026")
            rule.onNodeWithTag("gallery-photo-feeding-${ids.first}").performClick()
            rule.onNode(hasContentDescription("Кормление · Мадре") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            rule.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
            grid().performScrollToIndex(index("gallery-record:feeding-${ids.second}:0"))
            rule.onNodeWithTag("gallery-month-2025-02").assertTextEquals("ФЕВРАЛЬ 2025")
            rule.onNodeWithTag("gallery-photo-feeding-${ids.second}").performClick()
            rule.onNodeWithText("Закрыть").assertDoesNotExist()
            grid().performScrollToIndex(0)
            filter("Выпечка 1").performClick().assertIsSelected()
            rule.onNodeWithTag("gallery-month-2026-03").assertTextEquals("МАРТ 2026")
            assertEquals(-1, index("gallery-month:2026-02"))
            rule.onNodeWithTag("gallery-photo-bake-${ids.third}").performClick()
            rule.onNode(hasContentDescription("Реальная выпечка") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            rule.onNodeWithText("Закрыть").performClick()
            assertEquals(before, rows()); assertArrayEquals(bytes, jpeg.readBytes())
        } finally {
            rule.activity.viewModelStore.clear()
            TimeZone.setDefault(zone)
        }
    }
}
