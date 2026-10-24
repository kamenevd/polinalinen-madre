package com.polinalinen.madre

import com.polinalinen.madre.ui.kit.*
import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.screens.Home
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.razvorot.starterCycleLevel
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cycle 15: настоящая книга на настоящем Android — открыть главу и вернуться.
 *
 * Регрессия на краш из Cycle 14 (#15, hotfix/recipe-crash-regex):
 * PatternSyntaxException при открытии рецепта. Ни один unit-тест его не поймал
 * и поймать не мог — Robolectric подставляет обычный java.util.regex, а падал
 * ICU-движок настоящего Android на конкретной строке из recipes.json. Такое
 * ловится только на устройстве и только открытием реальных глав.
 *
 * Поэтому [every_chapter_in_the_book_opens_without_crashing] открывает ВСЕ
 * рецепты, а не первый: краш зависел от текста конкретной главы, и «первая
 * открылась» о книге ничего не говорит.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    /**
     * Разрешение выдаётся до запуска активности: MainActivity спрашивает про
     * уведомления при первом старте, а системный диалог поверх книги съел бы
     * все тапы теста.
     */
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    /** Те же рецепты, что читает книга, — из тех же assets. */
    private val recipes: List<Recipe> by lazy {
        runBlocking {
            RecipeRepository(InstrumentationRegistry.getInstrumentation().targetContext).getRecipes()
        }
    }

    /** Заголовок разворота главы — его нет ни на одном другом экране. */
    private fun chapterLabel(index: Int) = "Рецепт № %02d".format(index).uppercase()

    /**
     * Оглавление приезжает из assets асинхронно: до того, как рецепты
     * прочитаны, тапать не по чему.
     */
    private fun awaitTableOfContents() {
        // A fresh QA install may show the optional offer. Decline it, never launch SDK/auth.
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText("ПРОДОЛЖИТЬ БЕЗ ВХОДА").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty()
        }
        if(rule.onAllNodesWithText("ПРОДОЛЖИТЬ БЕЗ ВХОДА").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("ПРОДОЛЖИТЬ БЕЗ ВХОДА").performClick()
        // Startup legitimately restores the previous tab. Navigate without deleting its data/preferences.
        rule.waitUntil(TIMEOUT_MS){rule.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("nav-0").performClick()
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithTag(Home.LIST_TAG).fetchSemanticsNodes().isNotEmpty() &&
                recipes.isNotEmpty() &&
                rule.onAllNodesWithTag(Home.chapterRowTag(recipes.first().id))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("book-cover").assertDoesNotExist()
        rule.onNodeWithTag("chapter-fan").assertDoesNotExist()
    }

    private fun openChapter(recipe: Recipe) {
        val tag = Home.chapterRowTag(recipe.id)
        // Первая полоса длинная: до строки главы надо доехать, иначе её нет
        // в композиции и тапать не по чему.
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /**
     * Системная «назад», а не кнопка на странице: NavController обрабатывает
     * её своим путём, и сломаться может именно он.
     */
    private fun pressSystemBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun opening_the_first_chapter_and_coming_back() {
        awaitTableOfContents()
        val first = recipes.first()

        openChapter(first)
        // Разворот главы — по заголовку «РЕЦЕПТ № 01», а не по названию:
        // название видно и в оглавлении, и по нему не отличить, ушли мы
        // куда-нибудь или остались стоять.
        rule.onNodeWithTag("paper-turn").assertIsDisplayed()
        rule.onNodeWithContentDescription(first.name).assertIsDisplayed()
        // The visible reader chrome is now a canonical locale-aware CAPS
        // overline. Keep an exact visible-text assertion, not ignoreCase/exists.
        rule.onNodeWithText("ГЛАВА 01 ИЗ 11").assertIsDisplayed()

        pressSystemBack()
        rule.onNodeWithTag(Home.LIST_TAG).assertIsDisplayed()
        rule.onNodeWithTag("paper-turn").assertDoesNotExist()
    }

    /**
     * Каждая глава книги открывается и закрывается. Именно здесь ловится
     * краш вида «упало на одном рецепте из двадцати».
     */
    @Test
    fun every_chapter_in_the_book_opens_without_crashing() {
        awaitTableOfContents()
        assertThat(recipes).isNotEmpty()

        recipes.forEachIndexed { index, recipe ->
            openChapter(recipe)
            rule.onNodeWithTag("paper-turn").assertIsDisplayed()
            rule.onNodeWithContentDescription(recipe.name).assertIsDisplayed()
            pressSystemBack()
            rule.onNodeWithTag(Home.LIST_TAG).assertIsDisplayed()
        }
    }

    @Test fun actualStarterReadsRoomProfileAndOpensFeedingForm() {
        awaitTableOfContents()
        // CI emulator QA database only; this is MainActivity, not a debug scene.
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MadreApplication
        val repository=app.sourdoughRepository
        val old=runBlocking{repository.getOrCreateDefaultConfig()}
        val inserted=mutableListOf<FeedingEntity>()
        try {
            runBlocking { app.database.sourdoughConfigDao().update(old.copy(intervalHours=72,remindersEnabled=false)) }
            rule.waitUntil(TIMEOUT_MS){rule.onAllNodesWithTag("nav-2").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithTag("nav-2").performClick()
            val profile=profileForInterval(72)
            // Avoid the 12h=52.5% rounding boundary: real wall time advances
            // between insert and draw. These interior samples keep exact assertions.
            for((hours,phase) in listOf(10 to "GROWING",26 to "PEAK",80 to "HUNGRY")) {
                val saved=runBlocking { repository.saveFeeding(SourdoughFeedingSaveRequest(old.id,72,System.currentTimeMillis()-hours*3_600_000L,30,60,30,StorageLocation.KITCHEN,"CI regression only",null,null)) }
                inserted+=saved.feeding
                rule.waitUntil(TIMEOUT_MS){rule.onAllNodesWithTag("starter-phase-$phase").fetchSemanticsNodes().isNotEmpty()}
                rule.onNodeWithTag("starter-jar").assertIsDisplayed()
                val expected=(starterCycleLevel(hours.toFloat(),profile.peakHours,profile.cycleHours)*100).roundToInt()
                rule.onNodeWithTag("starter-jar").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Уровень по циклу: $expected%"))
                rule.onNodeWithTag("nav-2").assertIsSelected()
            }
            rule.onNodeWithText("Покормить").performScrollTo().performClick()
            rule.onNodeWithText("Новая запись").assertIsDisplayed()
            rule.onNodeWithTag("starter-jar").assertDoesNotExist()
            pressSystemBack()
            rule.waitUntil(TIMEOUT_MS){rule.onNodeWithTag("starter-jar").isDisplayed()}
            rule.onNodeWithTag("nav-2").assertIsSelected()
            rule.onNodeWithTag("starter-jar").assertIsDisplayed()
        } finally {
            runBlocking {
                inserted.forEach{app.database.feedingDao().delete(it)}
                app.database.sourdoughConfigDao().update(old)
            }
        }
    }

    private companion object {
        /** Чтение assets на холодном старте — секунды, а не миллисекунды. */
        const val TIMEOUT_MS = 15_000L
    }
}
