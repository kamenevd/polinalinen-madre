package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.StarterVoice
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.kit.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class DiaryHeroUiTest {
    @get:Rule val rule = createComposeRule()
    private val feeding = FeedingEntity(id = 12, sourdoughConfigId = 1, timestampMillis = 1_000,
        retainedStarterGrams = 47, flourGrams = 93, waterGrams = 61,
        storageLocation = StorageLocation.FRIDGE, finalHydrationPercent = 64)
    private val now = 25_921_000L

    private fun show(history: List<FeedingEntity> = listOf(feeding), phase: GrowthPhase = GrowthPhase.PEAK,
        fallback: Long? = null, clock: Long = now, fontScale: Float = 1f,
        onFeed: () -> Unit = {}, onGallery: () -> Unit = {}) {
        rule.setContent {
            RazvorotTheme(force = Atmosphere.DAY) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    StarterDiaryScreen(2, phase, profileForInterval(24),
                        listOf(DiaryEntry("вчера", "Семейная запись от руки", false)), history,
                        onBack = {}, onFeed = onFeed, onOpenGallery = onGallery, cancelledBakeCount = 1,
                        nowMillis = clock, lastFeedingMillis = fallback)
                }
            }
        }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
        return results.single()
    }

    @Test fun `real facts typography and editorial voice live above preserved diary and actions`() {
        var feeds = 0; var galleries = 0
        show(onFeed = { feeds++ }, onGallery = { galleries++ })
        rule.onNodeWithTag("diary-phase-PEAK").assertIsDisplayed()
        rule.onNodeWithText("Пик").assertIsDisplayed()
        rule.onNodeWithTag("diary-elapsed").assertTextEquals("7 ч 12 мин")
        rule.onNodeWithTag("diary-feeding-note").assertTextEquals("после кормления · 47/93/61 г · холодильник")
        rule.onNodeWithTag("diary-hydration").assertTextEquals("гидратация 64%")
        val number = layout(rule.onNodeWithTag("diary-elapsed")).layoutInput.style
        assertThat(number.fontFamily).isEqualTo(Manrope)
        assertThat(number.fontSize).isEqualTo(40.sp)
        assertThat(number.fontWeight).isEqualTo(FontWeight.ExtraLight)
        assertThat(number.lineHeight).isEqualTo(48.sp)
        assertThat(number.fontFeatureSettings).isEqualTo(PaperType.number.fontFeatureSettings)
        assertThat(PaperType.number.fontSize).isEqualTo(44.sp)
        assertThat(layout(rule.onNodeWithText("Пик")).layoutInput.style.color).isEqualTo(DayPaper.sage)
        assertThat(layout(rule.onNodeWithTag("diary-hydration")).layoutInput.style.color).isEqualTo(DayPaper.acc)
        listOf(rule.onNodeWithText("Пик"), rule.onNodeWithTag("diary-hydration")).forEach { node ->
            val style = layout(node).layoutInput.style
            assertThat(style.fontFamily).isEqualTo(Manrope)
            assertThat(style.fontSize).isEqualTo(24.sp)
            assertThat(style.fontWeight).isEqualTo(FontWeight.Normal)
            assertThat(style.lineHeight).isEqualTo(32.sp)
        }
        rule.onNodeWithText("Мадре пишет · литературная реплика").assertExists()
        rule.onNodeWithTag("diary-voice").assertTextEquals("«${StarterVoice.lineAt(20)}»")
        val voice = layout(rule.onNodeWithTag("diary-voice")).layoutInput.style
        assertThat(voice.fontFamily).isEqualTo(PaperType.note.fontFamily)
        assertThat(voice.fontStyle).isEqualTo(FontStyle.Italic)
        rule.onNodeWithText("Семейная запись от руки").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Покормить").performScrollTo().performClick()
        rule.onNodeWithText("Фото").performScrollTo().performClick()
        assertThat(feeds).isEqualTo(1); assertThat(galleries).isEqualTo(1)
    }

    @Test fun `empty diary invents no elapsed masses location or hydration`() {
        show(emptyList(), GrowthPhase.EMPTY)
        rule.onNodeWithText("Пока без кормлений").assertIsDisplayed()
        rule.onNodeWithTag("diary-elapsed").assertTextEquals("—")
        rule.onNodeWithTag("diary-feeding-note").assertTextEquals("Кормлений пока нет")
        rule.onNodeWithTag("diary-hydration").assertTextEquals("гидратация —")
    }

    @Test fun `latest insert supplies time masses and place while latest computed hydration may be older`() {
        val legacyInsert = feeding.copy(id = 14, retainedStarterGrams = null, finalHydrationPercent = null,
            hydrationPercent = 99, storageLocation = StorageLocation.KITCHEN)
        val olderComputed = feeding.copy(id = 13, timestampMillis = now + 9_000, finalHydrationPercent = 72)
        show(listOf(legacyInsert, olderComputed, feeding.copy(timestampMillis = now + 99_000)))
        rule.onNodeWithTag("diary-elapsed").assertTextEquals("7 ч 12 мин")
        rule.onNodeWithTag("diary-feeding-note").assertTextEquals("после кормления · —/93/61 г · кухня")
        rule.onNodeWithTag("diary-hydration").assertTextEquals("гидратация 72%")
        rule.onNodeWithContentDescription("Закваска: не указана", substring = true).assertExists()
    }

    @Test fun `legacy hydration is not presented as computed`() {
        show(listOf(feeding.copy(finalHydrationPercent = null, hydrationPercent = 99)))
        rule.onNodeWithTag("diary-hydration").assertTextEquals("гидратация —")
    }

    @Test fun `config timestamp fallback shows only known elapsed without fabricating a row`() {
        show(emptyList(), GrowthPhase.LAG, fallback = 1_000)
        rule.onNodeWithTag("diary-phase-LAG").assertExists()
        rule.onNodeWithTag("diary-elapsed").assertTextEquals("7 ч 12 мин")
        rule.onNodeWithTag("diary-feeding-note").assertTextEquals("после кормления · массы и место не записаны")
        rule.onNodeWithTag("diary-hydration").assertTextEquals("гидратация —")
        rule.onAllNodesWithText("Следующее кормление", substring = true).assertCountEquals(0)
    }

    @Test fun `future record remains a record but cannot claim elapsed time`() {
        show(clock = 999, fallback = 0)
        rule.onNodeWithTag("diary-elapsed").assertTextEquals("—")
        rule.onNodeWithTag("diary-feeding-note").assertTextEquals("Время кормления позже текущего · 47/93/61 г · холодильник")
        rule.onNodeWithTag("diary-hydration").assertTextEquals("гидратация 64%")
    }

    @Test fun `all phase badges follow passed phase not a second domain calculation`() {
        val phase = mutableStateOf(GrowthPhase.EMPTY)
        rule.setContent { RazvorotTheme { DiaryHero(phase.value, listOf(feeding), null, now) } }
        GrowthPhase.entries.forEach { next ->
            rule.runOnIdle { phase.value = next }
            rule.onNodeWithTag("diary-phase-${next.name}").assertIsDisplayed()
            rule.onNodeWithTag("diary-elapsed").assertTextEquals("7 ч 12 мин")
        }
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h640dp-xhdpi")
    fun `narrow page at double font size wraps all hero facts without clipping`() {
        show(clock = 445_501_000, fontScale = 2f)
        rule.onNodeWithTag("diary-elapsed").assertTextEquals("123 ч 45 мин")
        listOf("diary-elapsed", "diary-feeding-note", "diary-hydration", "diary-voice").forEach { tag ->
            val node = rule.onNodeWithTag(tag).performScrollTo()
            val result = layout(node)
            assertWithMessage("$tag: ${result.size}, ${result.lineCount} lines, horizontal=${result.didOverflowWidth}, vertical=${result.didOverflowHeight}")
                .that(result.hasVisualOverflow).isFalse()
            assertThat(result.layoutInput.density.fontScale).isEqualTo(2f)
        }
    }
}