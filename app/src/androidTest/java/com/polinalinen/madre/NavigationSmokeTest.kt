package com.polinalinen.madre

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.screens.Home
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
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithTag(Home.LIST_TAG).fetchSemanticsNodes().isNotEmpty() &&
                recipes.isNotEmpty() &&
                rule.onAllNodesWithTag(Home.chapterRowTag(recipes.first().id))
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openChapter(recipe: Recipe) {
        val tag = Home.chapterRowTag(recipe.id)
        // Первая полоса длинная: до строки главы надо доехать, иначе её нет
        // в композиции и тапать не по чему.
        rule.onNodeWithTag(Home.LIST_TAG).performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
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
        rule.onNodeWithText(chapterLabel(1)).assertIsDisplayed()
        rule.onNodeWithText(first.name).assertIsDisplayed()

        pressSystemBack()
        rule.onNodeWithTag(Home.LIST_TAG).assertIsDisplayed()
        rule.onNodeWithText(chapterLabel(1)).assertDoesNotExist()
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
            rule.onNodeWithText(chapterLabel(index + 1)).assertIsDisplayed()
            pressSystemBack()
            rule.onNodeWithTag(Home.LIST_TAG).assertIsDisplayed()
        }
    }

    private companion object {
        /** Чтение assets на холодном старте — секунды, а не миллисекунды. */
        const val TIMEOUT_MS = 15_000L
    }
}
