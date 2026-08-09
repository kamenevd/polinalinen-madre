package com.polinalinen.madre.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.theme.MadreTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 18, «язык кнопок»: на первой полосе не должно остаться нажимаемого
 * места, которое не назвало себя кнопкой.
 *
 * До этого цикла тут жили пять разных способов сделать нажатие: `.clickable`
 * прямо на строке оглавления, на строке от Мадре, на десятикегельной надписи
 * «впустить погоду за окном» высотой в ноготь и на двух ляссе. Ни одно из них
 * не объявляло [Role.Button] и не давало TalkBack ни слова о том, что
 * случится, — а «впустить погоду» промахивалось пальцем через раз.
 *
 * Проверка идёт по НЕслитому дереву: нажатие живёт на самом модификаторе, а в
 * слитом узле оно смешалось бы с текстом потомков, и мишень померялась бы не
 * та.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class HomeControlsUiTest {

    @get:Rule
    val rule = createComposeRule()

    /** Те же рецепты, что читает книга, — из тех же assets. */
    private val recipes by lazy {
        runBlocking {
            RecipeRepository(ApplicationProvider.getApplicationContext()).getRecipes()
        }
    }

    private fun showHome() {
        rule.setContent {
            MadreTheme {
                HomeScreen(
                    madreHeadline = "я проголодалась!",
                    starterName = "Мадре",
                    // HUNGRY — чтобы на полосе оказалось и mood-ляссе: без
                    // фазы «голодная» его в композиции просто нет.
                    phase = GrowthPhase.HUNGRY,
                    favoriteIds = emptySet(),
                    onToggleFavorite = {},
                    onOpenRecipe = {},
                    onOpenStarter = {},
                    onOpenTimer = {},
                    onOpenFeeding = {},
                    onOpenSettings = {},
                    onOpenShelf = {},
                )
            }
        }
        // Оглавление приезжает из assets асинхронно: до того, как рецепты
        // прочитаны, половины нажимаемых мест на полосе ещё нет.
        rule.waitUntil(TIMEOUT_MS) {
            recipes.isNotEmpty() &&
                rule.onAllNodesWithTag(Home.chapterRowTag(recipes.first().id))
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickableNodes(): List<SemanticsNode> =
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()

    /** Чем узел назовёт себя в отчёте о падении — иначе искать его негде. */
    private fun describe(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        return listOfNotNull(tag, text, description).firstOrNull { it.isNotBlank() }
            ?: "узел #${node.id}"
    }

    @Test
    fun `everything tappable on the front page says it is a button`() {
        showHome()
        val nodes = clickableNodes()
        assertThat(nodes).isNotEmpty()
        nodes.forEach { node ->
            assertWithMessage("${describe(node)}: роль")
                .that(node.config.getOrNull(SemanticsProperties.Role))
                .isEqualTo(Role.Button)
        }
    }

    @Test
    fun `everything tappable on the front page says what it will do`() {
        showHome()
        clickableNodes().forEach { node ->
            val label = node.config.getOrNull(SemanticsActions.OnClick)?.label
            assertWithMessage("${describe(node)}: подпись действия").that(label).isNotNull()
            assertWithMessage("${describe(node)}: подпись действия").that(label!!.isBlank()).isFalse()
        }
    }

    /**
     * Мишень меряется в тех же 48dp, что и у [com.polinalinen.madre.ui.components.BookButton]:
     * правило книги одно на все нажатия, а не только на кнопки с рамкой.
     */
    @Test
    fun `nothing tappable on the front page is smaller than a finger`() {
        showHome()
        val density = rule.density.density
        clickableNodes().forEach { node ->
            // Размер раскладки, а не boundsInRoot: последний обрезан окном
            // прокрутки, и строка главы, наполовину уехавшая за нижний край,
            // «не проходила» бы по мишени, будучи ростом с палец.
            val heightDp = node.size.height / density
            assertWithMessage("${describe(node)}: высота мишени, dp").that(heightDp).isAtLeast(48f)
        }
    }

    private companion object {
        /** Чтение assets на холодном старте — секунды, а не миллисекунды. */
        const val TIMEOUT_MS = 15_000L
    }
}
