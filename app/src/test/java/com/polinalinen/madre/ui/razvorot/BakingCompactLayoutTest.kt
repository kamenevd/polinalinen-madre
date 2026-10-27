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
    private fun expected(session:BakingSession)=StepIngredients.text(StepIngredients.parts(session.recipe,session.currentStepIndex,session.trustedPortions!!))
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
        val cases=recipes.flatMap { recipe -> (StepIngredients.linkedSteps(recipe)+recipe.timeline.indices.maxBy { expected(bake(recipe,it)).length }).map{bake(recipe,it)} }+bake()+bake(step=2)
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

    private fun popupAndAddedState() {
        val original=bake();val session=mutableStateOf(original)
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        restoration.setContent { RazvorotTheme(force=Atmosphere.DAWN) { BakingPaper(session.value,702,{},{},{},{},onOpenAlice={},onPreviousStep={}) } }
        val sugar=rule.onNodeWithTag("bake-ingredient-1",useUnmergedTree=true)
        sugar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Не добавлено")).performClick()
        rule.onNodeWithTag("bake-ingredient-popup").assertIsDisplayed()
        rule.onNodeWithText("50 г",useUnmergedTree=true).assertIsDisplayed()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithTag("bake-ingredient-add").performClick()
        rule.onNodeWithTag("bake-ingredient-popup").assertDoesNotExist()
        sugar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Добавлено"))
        val layouts=mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("✓ сахар 50 г",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertEquals(androidx.compose.ui.text.style.TextDecoration.LineThrough,layouts.single().layoutInput.style.textDecoration)
        assertEquals(DawnPaper.sage,layouts.single().layoutInput.style.color)
        for(rotate in listOf(false,true)) {
            rule.onNodeWithContentDescription("Открыть большой таймер").performClick()
            sugar.assertDoesNotExist()
            if(rotate)restoration.emulateSavedInstanceStateRestore() // Paragraph is absent.
            rule.onNode(SemanticsMatcher("Close timer") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label=="Свернуть таймер" }).performClick()
            sugar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Добавлено"))
        }
        restoration.emulateSavedInstanceStateRestore()
        sugar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Добавлено"))
        for(other in listOf(original.copy(id=8),original.copy(bakeKey="another-bake"),original.copy(currentStepIndex=2),original.copy(scaleFactor=3.0),original.copy(recipeRevision="new"),original.copy(recipe=original.recipe.copy(description="updated")))) {
            rule.runOnIdle{session.value=other}
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Добавлено"),useUnmergedTree=true).assertCountEquals(0)
            rule.onNodeWithTag("bake-ingredient-popup").assertDoesNotExist()
        }
        rule.runOnIdle{session.value=original}
        sugar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Добавлено"))
        for(invalid in listOf(original.copy(scaleFactor=null),original.copy(restoredStepValid=false))) {
            rule.runOnIdle{session.value=invalid}
            sugar.assertDoesNotExist()
            rule.onNodeWithTag("bake-instructions").assertTextEquals("Порции не сохранены — подтвердите количество для этой выпечки.")
        }
    }
    @Test fun popupExplainsExactFractionAndDoesNotMultiplyToTaste() {
        val session=mutableStateOf(bake(recipes.single{it.id=="cinnamon_buns"},0).copy(scaleFactor=3.0))
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) { BakingPaper(session.value,702,{},{},{},{}) } }
        val starter=StepIngredients.parts(session.value.recipe,0,3).indexOfFirst{it.ref!=null}
        rule.onNodeWithTag("bake-ingredient-$starter",useUnmergedTree=true).performClick()
        rule.onNodeWithText("113 г",useUnmergedTree=true).assertIsDisplayed()
        rule.onNodeWithText("37,5 г × 3 → 113 г").assertIsDisplayed()
        rule.onNodeWithTag("bake-ingredient-add").performClick()
        rule.runOnIdle { session.value=session.value.copy(currentStepIndex=4) }
        val vanilla=StepIngredients.parts(session.value.recipe,4,3).indexOfFirst{it.amount=="по вкусу"}
        rule.onNodeWithTag("bake-ingredient-$vanilla",useUnmergedTree=true).performClick()
        rule.onNodeWithText("По вкусу · без пересчёта").assertIsDisplayed()
        rule.onNodeWithText("по вкусу × 3").assertDoesNotExist()
    }
    @Test fun anchoredPopupAddedGreenStrikeAndScopedRestore412()=popupAndAddedState()
    @Test @Config(qualifiers="ru-w360dp-h780dp-mdpi") fun anchoredPopupAddedGreenStrikeAndScopedRestore360()=popupAndAddedState()

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
