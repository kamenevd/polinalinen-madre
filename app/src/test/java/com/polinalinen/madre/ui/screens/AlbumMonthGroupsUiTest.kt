package com.polinalinen.madre.ui.screens

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.utils.PhotoStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CompletableFuture

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class AlbumMonthGroupsUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var originalZone: TimeZone
    @Before fun utc() { originalZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restoreZone() { TimeZone.setDefault(originalZone) }
    private fun epoch(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun grid() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private fun month(value: String) = rule.onNodeWithTag("gallery-month-$value")
    private fun filter(value: String) = rule.onNodeWithContentDescription("Источник фото, $value")
    private fun index(key: String) = grid().fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)
    private fun photo(id: String, iso: String, source: GalleryPhotoSource? = null) =
        GalleryPhoto(id, "missing/shared.jpg", epoch(iso), "Фото $id", source)
    private fun pinned(value: String) {
        val bounds = month(value).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = grid().fetchSemanticsNode().boundsInRoot
        assertEquals("$value is pinned to native viewport", viewport.top, bounds.top, 0.5f)
        assertEquals("full content span", viewport.width - 44f, bounds.width, 0.5f)
        println("pinned=$value header=$bounds viewport=$viewport")
    }

    @Test fun nativeStickyHeaderPinsDuringTouchScrollAndIsReplacedAcrossMonthsAndFilters() {
        val photos = listOf("2026-10", "2025-10", "2025-09").flatMapIndexed { group, date ->
            (0 until 30).map { n -> photo("$group-$n", "${date}-15T12:00:00Z",
                if (group == 0) GalleryPhotoSource.BAKING else GalleryPhotoSource.STARTER) }
        }
        rule.setContent { RazvorotTheme(force = Atmosphere.DAY) { PhotoGalleryScreen(photos, {}) } }
        month("2026-10").assertTextEquals("ОКТЯБРЬ 2026")
        assertEquals(1, index("gallery-month:2026-10"))
        assertEquals(32, index("gallery-month:2025-10"))
        assertEquals(63, index("gallery-month:2025-09"))
        // Same-timestamp producer order is unchanged, including both adaptive columns.
        val a = rule.onNodeWithTag("gallery-photo-0-0").fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag("gallery-photo-0-1").fetchSemanticsNode().boundsInRoot
        val c = rule.onNodeWithTag("gallery-photo-0-2").fetchSemanticsNode().boundsInRoot
        assertEquals(a.top, b.top, 0f); assertTrue(a.left < b.left); assertTrue(c.top > a.top)
        grid().performScrollToIndex(8)
        pinned("2026-10")
        val before = rule.onNodeWithTag("gallery-photo-0-6").fetchSemanticsNode().positionInRoot.y
        grid().performTouchInput { swipeUp(startY = height * .75f, endY = height * .45f, durationMillis = 600) }
        pinned("2026-10")
        val after = rule.onAllNodesWithTag("gallery-photo-0-6").fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
        assertTrue("real touch scroll moves/removes records, not an overlay simulation", after == null || after < before)
        // Approach the next month continuously: native sticky placement pushes the old
        // header above the viewport before the incoming header replaces it.
        grid().performScrollToIndex(30)
        pinned("2026-10")
        val oldHeight = month("2026-10").fetchSemanticsNode().boundsInRoot.height
        val incomingTop = month("2025-10").fetchSemanticsNode().boundsInRoot.top
        grid().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, incomingTop - oldHeight / 2)) }
        val outgoing = month("2026-10").fetchSemanticsNode().positionInRoot.y
        val incoming = month("2025-10").fetchSemanticsNode().positionInRoot.y
        println("native push-off outgoing=$outgoing incoming=$incoming height=$oldHeight")
        assertTrue("native old header pushed above viewport", outgoing < 0f)
        assertTrue("incoming month has not yet pinned", incoming > 0f && incoming < oldHeight)
        grid().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, oldHeight)) }
        pinned("2025-10")
        month("2025-10").assertTextEquals("ОКТЯБРЬ 2025")
        month("2026-10").assertDoesNotExist()
        grid().performScrollToIndex(70)
        pinned("2025-09"); month("2025-09").assertTextEquals("СЕНТЯБРЬ 2025")
        month("2025-10").assertDoesNotExist()
        grid().performScrollToIndex(0)
        filter("Закваска 60").performClick().assertIsSelected()
        assertEquals(-1, index("gallery-month:2026-10"))
        assertEquals(1, index("gallery-month:2025-10"))
        grid().performScrollToIndex(8); pinned("2025-10")
        grid().performScrollToIndex(39); pinned("2025-09")
        grid().performScrollToIndex(0)
        filter("Выпечка 30").performClick().assertIsSelected()
        assertEquals(-1, index("gallery-month:2025-10"))
        assertEquals(-1, index("gallery-month:2025-09"))
        grid().performScrollToIndex(8); pinned("2026-10")
        grid().performScrollToIndex(0); filter("Все 90").performClick()
        assertEquals(63, index("gallery-month:2025-09"))
    }

    @Test fun trueLocalCalendarBoundariesYearsNegativeAndFutureEpochsKeepTheirActualLabels() {
        val photos = mutableStateOf(listOf(photo("boundary", "2026-03-01T00:00:00Z")))
        rule.setContent { RazvorotTheme(force = Atmosphere.DAY) { PhotoGalleryScreen(photos.value, {}) } }
        for ((zone, expected, label) in listOf(
            Triple("America/Los_Angeles", "2026-02", "ФЕВРАЛЬ 2026"),
            Triple("Pacific/Kiritimati", "2026-03", "МАРТ 2026"),
            Triple("UTC", "2026-03", "МАРТ 2026"))) {
            rule.runOnIdle {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                photos.value = listOf(photos.value.single().copy(caption = zone))
            }
            month(expected).assertIsDisplayed().assertTextEquals(label)
            assertEquals(1, index("gallery-month:$expected"))
        }
        rule.runOnIdle {
            photos.value = listOf(
                photo("new-year", "2026-01-01T00:00:00Z"),
                photo("last-year", "2025-12-31T23:59:59Z"),
                GalleryPhoto("negative", "missing", -1, "До эпохи"),
                photo("future", "2050-01-01T00:00:00Z"),
                GalleryPhoto("min", "missing", Long.MIN_VALUE, "Минимум"),
                GalleryPhoto("max", "missing", Long.MAX_VALUE, "Максимум"))
        }
        for ((key, record) in listOf("2026-01" to "new-year", "2025-12" to "last-year", "1969-12" to "negative", "2050-01" to "future")) {
            val at = index("gallery-month:$key")
            assertTrue("actual month key $key", at >= 1)
            grid().performScrollToIndex(at)
            month(key).assertTextEquals(when (key) {
                "2026-01" -> "ЯНВАРЬ 2026"
                "2025-12" -> "ДЕКАБРЬ 2025"
                "1969-12" -> "ДЕКАБРЬ 1969"
                else -> "ЯНВАРЬ 2050"
            })
            assertEquals(at + 1, index("gallery-record:$record:0"))
        }
        assertTrue(index("gallery-record:max:0") < index("gallery-record:future:0"))
        assertTrue(index("gallery-record:min:0") > index("gallery-record:negative:0"))
    }

    @Test fun actualOverlinesHaveNoIntrinsicOverflowAt320dpBothScalesAndEveryAtmosphere() {
        val theme = mutableStateOf(Atmosphere.DAY)
        val scale = mutableStateOf(1f)
        val photos = listOf(photo("long-label", "2026-09-15T12:00:00Z"))
        rule.setContent {
            RazvorotTheme(force = theme.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    PhotoGalleryScreen(photos, {})
                }
            }
        }
        for (atmosphere in Atmosphere.entries.filter { it != Atmosphere.AUTO }) for (font in listOf(1f, 2f)) {
            rule.runOnIdle { theme.value = atmosphere; scale.value = font }
            grid().performScrollToIndex(1)
            val layouts = mutableListOf<TextLayoutResult>()
            month("2026-09").assertIsDisplayed().assertTextEquals("СЕНТЯБРЬ 2026")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            val diagnostic = "$atmosphere scale=$font size=${layout.size} lines=${layout.lineCount} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}"
            println(diagnostic)
            assertFalse(diagnostic, layout.hasVisualOverflow)
            assertEquals(PaperType.overline.fontSize, layout.layoutInput.style.fontSize)
            assertEquals(PaperType.overline.fontWeight, layout.layoutInput.style.fontWeight)
            assertEquals(PaperType.overline.fontFamily, layout.layoutInput.style.fontFamily)
            assertEquals(PaperType.overline.letterSpacing, layout.layoutInput.style.letterSpacing)
            assertEquals(font, layout.layoutInput.density.fontScale, 0f)
            assertEquals("opaque native header surface", 1f, paperAt(atmosphere).bg.alpha, 0f)
        }
    }

    @Test fun sixHundredReferencesAreLazyReachableDuplicateIdsPathsMissingFilesAndRealJpegViewerSurvive() {
        val app = ApplicationProvider.getApplicationContext<MadreApplication>()
        val relative = "feeding_photos/month-fixture.jpg"
        val jpeg = File(app.filesDir, relative).apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
        val bytes = jpeg.readBytes()
        val decoded = CompletableFuture<coil.request.ImageResult>()
        app.imageLoader.enqueue(coil.request.ImageRequest.Builder(app).data(PhotoStore.resolve(app, relative)).size(32).listener(
            onSuccess = { _, result -> decoded.complete(result) }, onError = { _, result -> decoded.complete(result) }).build())
        rule.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); decoded.isDone }
        assertTrue("real JPEG decode ${decoded.get()}", decoded.get() is coil.request.SuccessResult)
        assertEquals(32, (decoded.get() as coil.request.SuccessResult).drawable.intrinsicWidth)
        assertEquals(16, (decoded.get() as coil.request.SuccessResult).drawable.intrinsicHeight)
        val photos = (0 until 600).map { n -> GalleryPhoto(
            if (n < 2) "gallery-month:2026-10" else "ref-$n",
            if (n < 2) relative else "missing/shared.jpg",
            epoch(if (n < 300) "2026-10-15T12:00:00Z" else "2025-10-15T12:00:00Z"),
            "Настоящая ссылка $n", if (n % 2 == 0) GalleryPhotoSource.STARTER else GalleryPhotoSource.BAKING) }
        rule.setContent { RazvorotTheme(force = Atmosphere.DAY) { PhotoGalleryScreen(photos, {}) } }
        filter("Все 600").assertIsSelected()
        assertEquals(2, index("gallery-record:gallery-month:2026-10:0"))
        assertEquals(3, index("gallery-record:gallery-month:2026-10:1"))
        assertEquals(603 - 1, index("gallery-record:ref-599:0"))
        assertTrue("not all 600 references composed", rule.onAllNodes(hasTestTag("gallery-photo-ref-599")).fetchSemanticsNodes().isEmpty())
        rule.onAllNodesWithTag("gallery-photo-gallery-month:2026-10")[0].performClick()
        rule.onNodeWithText("Закрыть").assertIsDisplayed()
        rule.onNode(hasContentDescription("Настоящая ссылка 0") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithText("Закрыть").performClick()
        grid().performScrollToIndex(index("gallery-record:ref-599:0"))
        pinned("2025-10")
        rule.onNodeWithTag("gallery-photo-ref-599").assertIsDisplayed().performClick()
        rule.onNodeWithText("Закрыть").assertDoesNotExist()
        assertTrue(rule.onAllNodesWithText("файл\nне найден").fetchSemanticsNodes().isNotEmpty())
        grid().performScrollToIndex(0)
        filter("Закваска 300").performClick().assertIsSelected()
        assertEquals(-1, index("gallery-record:ref-599:0"))
        grid().performScrollToIndex(index("gallery-record:ref-598:0"))
        rule.onNodeWithTag("gallery-photo-ref-598").assertIsDisplayed()
        assertArrayEquals(bytes, jpeg.readBytes()); assertEquals(jpeg.canonicalFile, PhotoStore.resolve(app, relative).canonicalFile)
    }
}
