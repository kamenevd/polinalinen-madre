package com.polinalinen.madre.ui.razvorot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.model.*
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Bounds and real callbacks, not pixel/style thresholds or an asynchronous navigation fixture. */
@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi")
class BakingFooterInteractionTest {
    @get:Rule val rule=createComposeRule()
    private val back="Вернуться на прошлый шаг"
    private fun bake()=BakingSession(7,Recipe("footer","Хлеб","","",emptyMap(),listOf(
        TimelineStep(StepType.WAIT,"Опара","Подождите",10),
        TimelineStep(StepType.ACTION,"Замес","Замесите тесто",20),
        TimelineStep(StepType.WAIT,"Расстойка","Подождите",30))),1,0L,0L,scaleFactor=2.0,bakeNumber=2)

    @Test fun previousStepIsFullWidthBelowMainActionsAndAboveCompactVoiceRow() {
        var previous=0;var navigation=0;var alice=0;var paused=0;var next=0
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BakingPaper(bake(),1100,{paused++},{next++},{},{navigation++},
                onOpenAlice={alice++},onPreviousStep={previous++})
        } }
        val main=rule.onNodeWithTag("bake-main-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val previousBounds=rule.onNodeWithTag("bake-previous-step").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val voice=rule.onNodeWithTag("bake-voice-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(main.left,previousBounds.left,0.5f);assertEquals(main.right,previousBounds.right,0.5f)
        assertTrue(previousBounds.top>=main.bottom);assertTrue(voice.top>=previousBounds.bottom)
        rule.onNodeWithText("Пауза").assertIsDisplayed().performClick()
        rule.onNodeWithText("Дальше →").assertIsDisplayed().performClick()
        rule.onNodeWithText("Алиса",substring=true).assertIsDisplayed().performClick()
        rule.onNodeWithText(back).assertIsEnabled().performClick()
        rule.onNodeWithText("Таймер прошлого шага начнётся заново.").assertIsDisplayed()
        assertEquals(0,previous);assertEquals(0,navigation)
        rule.onNodeWithText("Вернуться").performClick()
        assertEquals(1,previous);assertEquals(0,navigation)
        assertEquals(1,paused);assertEquals(1,next);assertEquals(1,alice)
    }

    @Test fun dismissingConfirmationNeverInvokesPreviousStep() {
        var previous=0
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BakingPaper(bake(),1100,{},{},{},{},onPreviousStep={previous++})
        } }
        rule.onNodeWithText(back).performClick()
        rule.onNodeWithText("Остаться").performClick()
        assertEquals(0,previous)
    }

    @Test fun newSessionRevisionDismissesStaleConfirmation() {
        var previous=0
        val session=mutableStateOf(bake())
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BakingPaper(session.value,1100,{},{},{},{},onPreviousStep={previous++})
        } }
        rule.onNodeWithText(back).performClick()
        rule.onNodeWithText("Вернуться").assertIsDisplayed()
        rule.runOnIdle { session.value=session.value.copy(revision=1) }
        rule.onNodeWithText("Вернуться").assertDoesNotExist()
        assertEquals(0,previous)
    }

    @Test fun firstUnknownInvalidAndCompletedSessionsCannotGoBack() {
        var previous=0
        val session=mutableStateOf(bake())
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BakingPaper(session.value,1100,{},{},{},{},onPreviousStep={previous++})
        } }
        for(blocked in listOf(bake().copy(currentStepIndex=0),bake().copy(scaleFactor=null),
            bake().copy(restoredStepValid=false),bake().copy(completedAt=1))) {
            rule.runOnIdle { session.value=blocked }
            rule.onNodeWithText(back).assertIsNotEnabled()
            assertFalse(blocked.canStepBack)
        }
        assertEquals(0,previous)
    }
}
