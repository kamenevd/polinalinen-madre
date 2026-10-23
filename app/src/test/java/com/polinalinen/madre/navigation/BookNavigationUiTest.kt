package com.polinalinen.madre.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.data.repository.activeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.razvorot.RazvorotReader
import com.polinalinen.madre.ui.screens.Home
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BookNavigationUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val recipes by lazy { runBlocking{RecipeRepository(app).getRecipes()} }
    @Before fun setup() { runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())} }
    @After fun clearControllers() { rule.activity.viewModelStore.clear() }

    @Test fun realGraphOpensFifthChapterAndRestoresItsPageAndTocSearch() {
        lateinit var nav:NavHostController
        val restoration=StateRestorationTester(rule)
        restoration.setContent { nav=rememberNavController();MadreNavHost(nav) }
        rule.waitUntil(10_000){rule.onAllNodesWithTag(Home.chapterRowTag("ciabatta")).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("book-cover").assertDoesNotExist();rule.onNodeWithTag("chapter-fan").assertDoesNotExist()
        rule.onNodeWithTag("toc-search").performTextReplacement("Чиабатта")
        rule.onNodeWithTag(Home.chapterRowTag("ciabatta")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.CHAPTER,nav.currentDestination?.route)
        assertEquals("ciabatta",nav.currentBackStackEntry?.arguments?.getString("recipeId"))
        assertEquals(-1,nav.currentBackStackEntry?.arguments?.getInt("page"))
        rule.onNodeWithTag("paper-turn").assertIsDisplayed()
        rule.onNode(SemanticsMatcher("page2 action") { it.config.getOrNull(SemanticsActions.OnClick)?.label=="Страница 2" }).performClick()
        rule.waitForIdle()
        val page=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(page.startsWith("Страница 2 из "))
        assertTrue("N2: no truncated text or extra chapters: $page",page.substringAfter(" из ").toInt() in 3..5)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(page,rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithContentDescription("В оглавление").performClick()
        rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        rule.onNodeWithTag("toc-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Чиабатта")))
        assertEquals("ciabatta",BookPreferences(app).use{it.bookmark?.recipeId})
    }
    @Test fun chapterPlusUpdatesActualIngredientWithoutTurningThePage() {
        val recipe=recipes.single{it.id=="home_bread"}
        val ingredient=recipe.ingredients.values.flatten().first{it.scalable&&it.refType==null&&it.amount>0}
        val prefs=ReadingPreferences(app,"nav-ui-${UUID.randomUUID()}").apply{choosePortions(2f)}
        var factor by mutableStateOf(2.0)
        rule.setContent { RazvorotTheme(prefs,force=Atmosphere.DAWN) {
            RazvorotReader(recipes,recipe.id,prefs,{},{},{},initialPage=1,scaleFactor=factor,onScaleFactor={factor=it})
        } }
        rule.waitForIdle()
        val before=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        rule.onNodeWithText("+").performClick();rule.waitForIdle()
        assertEquals(3.0,factor,.00001)
        assertEquals(before,rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val expected=RecipeScaler.scaledDisplayText(ingredient,3.0)
        rule.onNodeWithTag("paper-turn").assert(SemanticsMatcher("real scaled ingredient") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any{line->line.contains(expected)}==true
        })
    }
    @Test fun notificationReturnsToOvenAndRestorationKeepsTheActualRoomBake() {
        val recipe=runBlocking{app.recipeRepository.getRecipes()}.first()
        val fixture=BakingSession.start(9001L,recipe,2.0).copy(recipeRevision=app.recipeRepository.revision)
        runBlocking{app.activeBakeRepository.commit(fixture.id,null,fixture.activeSnapshot())}
        val requested=MutableStateFlow<Long?>(fixture.id)
        lateinit var nav:NavHostController
        val restoration=StateRestorationTester(rule)
        fun awaitActualBake() {
            rule.waitForIdle()
            try {
                rule.waitUntil(10_000) {
                    // Fetch semantics first: a short-circuited route check never synchronizes the host looper.
                    val ready=rule.onAllNodesWithText("Дальше").fetchSemanticsNodes().isNotEmpty()
                    ready && nav.currentDestination?.route==MadreDestinations.BAKE
                }
            } catch(failure:Throwable) {
                println("Synthetic navigation fixture: route=${nav.currentDestination?.route}, pending=${requested.value}, rows=${runBlocking{app.activeBakeRepository.all()}.map{it.sessionId to it.recipeId}}")
                throw failure
            }
        }
        restoration.setContent {nav=rememberNavController();MadreNavHost(nav,pendingSessionId=requested)}
        awaitActualBake()
        assertEquals(fixture.id.toString(),nav.currentBackStackEntry?.arguments?.getString("sessionId"))
        assertNull(requested.value)
        rule.runOnIdle{rule.activity.viewModelStore.clear()}
        restoration.emulateSavedInstanceStateRestore()
        awaitActualBake()
        assertEquals(fixture.id.toString(),nav.currentBackStackEntry?.arguments?.getString("sessionId"))
        assertEquals(fixture.bakeKey,runBlocking{app.activeBakeRepository.all()}.single{it.sessionId==fixture.id}.bakeKey)
        rule.runOnIdle{assertTrue(nav.popBackStack())}
        rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
        assertEquals(1,nav.currentBackStackEntry?.savedStateHandle?.get<Int>("home_section"))
        restoration.emulateSavedInstanceStateRestore();rule.waitForIdle()
        assertEquals(MadreDestinations.HOME,nav.currentDestination?.route)
    }
    @Test fun fontScale200UsesTheSameChapterScrollRendererAndNotOtherChapters() {
        val recipe=recipes.single{it.id=="ciabatta"}
        val prefs=ReadingPreferences(app,"nav-large-${UUID.randomUUID()}")
        rule.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                RazvorotTheme(prefs,force=Atmosphere.NIGHT) {
                    RazvorotReader(recipes,recipe.id,prefs,{},{},{},scaleFactor=2.0)
                }
            }
        }
        rule.onNodeWithTag("reader-scroll").assertIsDisplayed()
        rule.onNodeWithTag("paper-turn").assertDoesNotExist()
        recipes.filter{it.id!=recipe.id}.forEach{rule.onAllNodesWithText(it.name).assertCountEquals(0)}
        rule.onNodeWithTag("reader-scroll").performScrollToNode(hasText("редактировать от руки"))
        rule.onNodeWithText("редактировать от руки").assertIsDisplayed()
    }
}
