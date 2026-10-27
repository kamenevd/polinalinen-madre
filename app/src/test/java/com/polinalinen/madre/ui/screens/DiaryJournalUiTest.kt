package com.polinalinen.madre.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import coil.request.SuccessResult
import coil.size.Dimension
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.model.RuShortDate
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.utils.PhotoStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class DiaryJournalUiTest {
    @get:Rule val rule = createComposeRule()
    private val row = FeedingEntity(id = 17, sourdoughConfigId = 1, timestampMillis = 1_750_000_000_000,
        flourGrams = 999, waterGrams = 888, retainedStarterGrams = 777,
        finalHydrationPercent = 83, hydrationPercent = 12, storageLocation = StorageLocation.FRIDGE,
        generatedComment = "Точный комментарий\nвторая строка", starterObservation = "Старое наблюдение",
        notes = "  Длинная точная заметка без потерь\nещё одна строка  ")
    private fun clickRow(value: FeedingEntity = row) = rule.onNodeWithContentDescription(RuShortDate.accessible(value.timestampMillis), substring = true)
        .performScrollTo().performClick()
    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
        return results.single()
    }
    @Test fun `actual row tap restores disclosure and separates another config with unchanged exact facts`() {
        val current = mutableStateOf(row)
        val restore = StateRestorationTester(rule)
        restore.setContent { RazvorotTheme { LazyColumn { item { JournalRow(current.value, 2026) } } } }
        rule.onNodeWithText(row.generatedComment!!).assertDoesNotExist()
        clickRow()
        rule.onNodeWithText(row.generatedComment!!).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Заметка: ${row.notes}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(row.starterObservation!!).assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(row.generatedComment!!).performScrollTo().assertIsDisplayed()
        clickRow()
        rule.onNodeWithText(row.generatedComment!!).assertDoesNotExist()
        clickRow()
        rule.runOnIdle { current.value = row.copy(sourdoughConfigId = 2, generatedComment = null, starterObservation = "Наблюдение другой книги") }
        rule.onNodeWithText(row.generatedComment!!).assertDoesNotExist()
        rule.onNodeWithText("Наблюдение другой книги").assertDoesNotExist()
        clickRow(current.value)
        rule.onNodeWithText("Наблюдение другой книги").performScrollTo().assertIsDisplayed()
        assertThat(current.value.notes).isEqualTo(row.notes)
        assertThat(current.value.id).isEqualTo(row.id)
    }
    private fun restoreWithIdentityChange(replacement: FeedingEntity) {
        var active = row
        var next: FeedingEntity? = null
        var disposals = 0
        val restore = StateRestorationTester(rule)
        restore.setContent {
            DisposableEffect(Unit) {
                onDispose { disposals++; next?.let { active = it } }
            }
            RazvorotTheme { LazyColumn { item { JournalRow(active, 2026) } } }
        }
        clickRow()
        rule.onNodeWithText(row.generatedComment!!).performScrollTo().assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        assertThat(disposals).isEqualTo(1)
        rule.onNodeWithText(row.generatedComment!!).performScrollTo().assertIsDisplayed()
        next = replacement
        restore.emulateSavedInstanceStateRestore()
        assertThat(disposals).isEqualTo(2)
        assertThat(active).isEqualTo(replacement)
        rule.onNodeWithContentDescription(RuShortDate.accessible(active.timestampMillis), substring = true)
            .performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Подробности свёрнуты"))
        rule.onNodeWithText(active.generatedComment!!).assertDoesNotExist()
    }
    @Test fun `saved expanded row is rejected after disposal into another real config with the same id`() =
        restoreWithIdentityChange(row.copy(sourdoughConfigId = 2))

    @Test fun `saved expanded row is rejected after disposal into another id of the same config`() =
        restoreWithIdentityChange(row.copy(id = 18))

    @Test fun `real layout reflows narrow double scale with exact typography and AA for all themes`() {
        val theme = mutableStateOf(Atmosphere.DAY)
        rule.setContent { RazvorotTheme(force = theme.value) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                LazyColumn(Modifier.testTag("journal-fixture")) { item { JournalRow(row.copy(photoPath = "feeding_photos/missing.jpg"), 2026) } }
            }
        } }
        listOf(Atmosphere.DAWN to DawnPaper, Atmosphere.DAY to DayPaper,
            Atmosphere.DUSK to DuskPaper, Atmosphere.NIGHT to NightPaper).forEach { (atmosphere, palette) ->
            rule.runOnIdle { theme.value = atmosphere }
            listOf("date", "masses", "time", "hydration", "note", "missing-photo").forEach { role ->
                val result = layout("journal-$role-17")
                val style = result.layoutInput.style
                assertWithMessage("$atmosphere $role ${result.size} lines=${result.lineCount} width=${result.didOverflowWidth} height=${result.didOverflowHeight}")
                    .that(result.hasVisualOverflow).isFalse()
                val bg = palette.bg
                val ink = style.color.compositeOver(bg).luminance()
                val paper = bg.luminance()
                val contrast = (maxOf(ink, paper) + .05f) / (minOf(ink, paper) + .05f)
                assertWithMessage("$atmosphere $role contrast=$contrast").that(contrast).isAtLeast(if (role == "hydration") 3f else 4.5f)
                assertThat(result.layoutInput.density.fontScale).isEqualTo(2f)
                if (role in listOf("date", "masses", "hydration")) {
                    assertThat(style.fontFamily).isEqualTo(Manrope)
                    assertThat(style.fontSize).isEqualTo(if (role == "masses") 15.sp else 24.sp)
                    assertThat(style.fontWeight).isEqualTo(when (role) { "date" -> FontWeight.Light; "masses" -> FontWeight.SemiBold; else -> FontWeight.Normal })
                }
                if (role == "hydration") assertThat(style.color).isEqualTo(palette.acc)
            }
            assertThat(layout("journal-masses-17").layoutInput.text.text).isEqualTo("777 · 999 · 888 г")
            assertThat(layout("journal-hydration-17").layoutInput.text.text).isEqualTo("83%")
        }
    }
    @Test fun `actual JPEG relative path bounded decode thumbnail disclosure and original viewer preserve bytes`() {
        val app = ApplicationProvider.getApplicationContext<MadreApplication>()
        val file = File(app.filesDir, "feeding_photos/journal-real.jpg").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)).isTrue() }
            bitmap.recycle()
        }
        val bytes = file.readBytes()
        val relative = "feeding_photos/journal-real.jpg"
        val image = row.copy(photoPath = relative)
        val request = journalThumbnailRequest(app, relative, 40)
        assertThat(request.data).isEqualTo(PhotoStore.resolve(app, relative))
        val size = runBlocking { request.sizeResolver.size() }
        assertThat(size.width).isEqualTo(Dimension.Pixels(40)); assertThat(size.height).isEqualTo(Dimension.Pixels(40))
        val decoded = java.util.concurrent.CompletableFuture<coil.request.ImageResult>()
        app.imageLoader.enqueue(request.newBuilder().listener(
            onSuccess = { _, result -> decoded.complete(result) },
            onError = { _, result -> decoded.complete(result) },
        ).build())
        // Coil delivers on Android Main; do not block its paused Robolectric looper with execute/runBlocking.
        rule.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); decoded.isDone }
        val result = decoded.get()
        assertWithMessage(result.toString()).that(result).isInstanceOf(SuccessResult::class.java)
        assertThat(result.drawable!!.intrinsicWidth).isAtMost(40)
        assertThat(result.drawable!!.intrinsicHeight).isAtMost(40)
        rule.setContent { RazvorotTheme { LazyColumn { item { JournalRow(image, 2026) } } } }
        val bounds = rule.onNodeWithTag("journal-thumbnail-17", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(bounds.width).isEqualTo(40f); assertThat(bounds.height).isEqualTo(40f)
        rule.onNodeWithContentDescription("Фотокарточка кормления").assertDoesNotExist()
        clickRow(image)
        rule.onNodeWithContentDescription("Фотокарточка кормления").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
        rule.onNodeWithText("Закрыть").assertDoesNotExist()
        clickRow(image)
        rule.onNodeWithContentDescription("Фотокарточка кормления").assertDoesNotExist()
        assertThat(file.readBytes()).isEqualTo(bytes)
    }
}
