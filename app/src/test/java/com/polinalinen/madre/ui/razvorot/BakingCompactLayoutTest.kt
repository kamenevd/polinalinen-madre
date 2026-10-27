package com.polinalinen.madre.ui.razvorot

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.*
import com.polinalinen.madre.ui.kit.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BakingCompactLayoutTest {
    @get:Rule val rule=createComposeRule()
    private val recipes by lazy { runBlocking { RecipeRepository(ApplicationProvider.getApplicationContext()).getRecipes() } }
    private fun bake(recipe:Recipe=recipes.first(),step:Int=4)=BakingSession(7,recipe,step,0L,0L,scaleFactor=2.0,bakeNumber=2)
    private fun expected(session:BakingSession)=RecipeScaler.scaledStepText(session.currentStep.description,2.0,RecipeScaler.scalableBindings(session.recipe))
    private fun assertTextFits() {
        val layouts=mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("bake-instructions").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertFalse(layouts.single().hasVisualOverflow)
        val text=rule.onNodeWithTag("bake-instructions").fetchSemanticsNode().boundsInRoot
        val reading=rule.onNodeWithTag("bake-reading").fetchSemanticsNode().boundsInRoot
        assertTrue("Instruction bounds $text exceed reading region $reading",text.top>=reading.top && text.bottom<=reading.bottom)
    }
    private fun fixedLayout() {
        val session=mutableStateOf(bake())
        val other=bake().copy(id=8,bakeNumber=1);val plan=com.polinalinen.madre.navigation.PlannedBake("pizza",2,1000L,number=3)
        var switched=0L;var opened:String?=null
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) { BakingPaper(session.value,702,{},{},{},{},onOpenAlice={},onPreviousStep={},
            sessions=listOf(other,session.value),onSwitchSession={switched=it},plans=listOf(plan),onOpenPlan={opened=it.id}) } }
        val cases=recipes.map { recipe -> bake(recipe,recipe.timeline.indices.maxBy { expected(bake(recipe,it)).length }) }+bake()+bake(step=2)
        cases.forEach { current ->
            rule.runOnIdle { session.value=current }
            rule.onNodeWithTag("bake-instructions").assertTextEquals(expected(current)).assertIsDisplayed()
            assertTextFits()
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
            val actions=rule.onNodeWithTag("bake-main-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val content=rule.onNodeWithTag("bake-content").fetchSemanticsNode().boundsInRoot
            assertTrue(content.bottom<=actions.top)
            rule.onNodeWithTag("bake-previous-step").assertIsDisplayed()
            rule.onNodeWithText("Алиса",substring=true).assertIsDisplayed()
        }
        rule.onNode(hasClickAction() and hasContentDescription("Выпечка номер один")).performScrollTo().performClick()
        rule.onNode(hasClickAction() and hasContentDescription("Запланированная выпечка номер три")).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(8L,switched);assertEquals(plan.id,opened) }
    }
    @Test fun longestRealInstructionsAndScaledTemplateFit412()=fixedLayout()
    @Test @Config(qualifiers="ru-w360dp-h780dp-mdpi") fun longestRealInstructionsAndScaledTemplateFit360()=fixedLayout()

    @Test @Config(qualifiers="ru-w360dp-h780dp-mdpi") fun controlsKeepCallbacksAndInvalidRestoreGuard() {
        val session=mutableStateOf(bake());val adjustments=mutableListOf<Int>()
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) { BakingPaper(session.value,702,{},{},{},{},onAdjustMinutes={adjustments.add(it)}) } }
        listOf("−5 мин","+5 мин","+15 мин").forEach { rule.onNodeWithContentDescription(it).performClick() }
        rule.runOnIdle { assertEquals(listOf(-5,5,15),adjustments);session.value=session.value.copy(restoredStepValid=false) }
        rule.onNodeWithContentDescription("+5 мин").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(3,adjustments.size) }
        rule.onNodeWithContentDescription("Открыть большой таймер").performClick()
        rule.onNode(SemanticsMatcher("Close timer action") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label=="Свернуть таймер" }).assertExists().performClick()
    }
    @Test @Config(qualifiers="ru-w360dp-h780dp-mdpi") fun largeTypeKeepsFullTextInReadingRegionAndUnknownPortionsConfirmationReachable() {
        val session=mutableStateOf(bake(recipes.single{it.id=="garlic_buns"},0));var confirmed=0
        rule.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) { RazvorotTheme(force=Atmosphere.DAWN) {
                BakingPaper(session.value,702,{},{},{},{},onConfirmPortions={confirmed=it},onOpenAlice={})
            } }
        }
        rule.onNodeWithTag("bake-instructions").assertTextEquals(expected(session.value))
        val scroll=rule.onNodeWithTag("bake-reading").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.maxValue()>0f)
        rule.onNodeWithTag("bake-previous-step").assertIsDisplayed()
        rule.runOnIdle { session.value=bake().copy(scaleFactor=null) }
        rule.onNodeWithText("Подтвердить порции").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Сколько порций было?",substring=true).performScrollTo().performClick()
        rule.onNodeWithText("Подтвердить порции").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1,confirmed) }
    }
}

/** Exercise the exact CI fixture's display recreation without writing local screenshots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BakingCompactCaptureReadinessTest {
    @get:Rule val rule=androidx.compose.ui.test.junit4.createAndroidComposeRule<RazvorotSceneActivity>()
    @Test fun existingCaptureCanRecreateBothFullViewports() {
        for((width,height) in listOf(360 to 780,412 to 915)) {
            org.robolectric.RuntimeEnvironment.setQualifiers("ru-w${width}dp-h${height}dp-mdpi")
            rule.activityRule.scenario.recreate()
            rule.activityRule.scenario.onActivity { it.atmosphere=Atmosphere.DAWN;it.scene="bake-compact" }
            rule.waitForIdle()
            val root=rule.onNodeWithTag("bake-paper").fetchSemanticsNode().boundsInRoot
            assertEquals(width.toFloat(),root.width,.5f);assertEquals(height.toFloat(),root.height,.5f)
            val text=rule.onNodeWithTag("bake-instructions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val reading=rule.onNodeWithTag("bake-reading").fetchSemanticsNode().boundsInRoot
            assertTrue(text.bottom<=reading.bottom)
            rule.onNodeWithText("Замес с добавками").assertIsDisplayed()
            rule.onNodeWithTag("bake-previous-step").assertIsDisplayed()
        }
    }
}
