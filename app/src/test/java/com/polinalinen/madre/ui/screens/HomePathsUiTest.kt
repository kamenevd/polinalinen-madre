package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 18, «чистая главная»: чего на первой полосе больше нет.
 *
 * Про ляссе — соседний [PageRibbonUiTest]: там тот же вопрос решается без
 * Room и ViewModel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class HomePathsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val recipes by lazy { frontPageRecipes() }

    private fun awaitTableOfContents() {
        rule.awaitOnPage("оглавление") {
            recipes.isNotEmpty() &&
                rule.onAllNodesWithTag(Home.chapterRowTag(recipes.first().id))
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * «Общая статистика» была подвалом первой полосы, который в лучшем случае
     * говорил, сколько ещё телефонов в семье пишут в эту книгу, а чаще —
     * что общая книга не подключена. Ни то, ни другое не помогает испечь хлеб
     * сегодня, а место занимало под каждым оглавлением.
     */
    @Test
    fun `the front page has no community footer`() {
        rule.showFrontPage()
        awaitTableOfContents()

        // Доехать до самого низа: секция стояла прямо перед колофоном, и
        // «её не видно» без прокрутки не значит «её нет».
        rule.onNodeWithTag(Home.LIST_TAG)
            .performScrollToNode(hasText("— тираж: одна семья · печатается с любовью —"))
        rule.onNodeWithText("— тираж: одна семья · печатается с любовью —").assertIsDisplayed()

        // Заголовок раздела набирается через PageLabel, а тот пишет прописными:
        // искать «Общая статистика» — значит не найти её никогда.
        assertThat(rule.onAllNodesWithText("ОБЩАЯ СТАТИСТИКА").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the masthead is a home bakery, not a dedication on the title`() {
        rule.showFrontPage()
        rule.onNodeWithText("ДОМАШНЯЯ ПЕКАРНЯ").assertIsDisplayed()
        rule.onAllNodesWithText("ДОМАШНЯЯ ПЕКАРНЯ ПОЛИНЫ").assertCountEquals(0)
    }
}
