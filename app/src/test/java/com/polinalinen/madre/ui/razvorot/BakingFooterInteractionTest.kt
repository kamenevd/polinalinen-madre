package com.polinalinen.madre.ui.razvorot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
    private fun previousAction()=rule.onNodeWithTag("bake-previous-step").onChildAt(0)
    private fun assertNoConfirmation() {
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithText("Вернуться на прошлый шаг?").assertDoesNotExist()
        rule.onNodeWithText("Таймер прошлого шага начнётся заново.").assertDoesNotExist()
    }
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
        previousAction().assertTextEquals("Назад").assertIsEnabled()
        rule.onNodeWithText("Пауза").assertIsDisplayed().performClick()
        rule.onNodeWithText("Дальше →").assertIsDisplayed().performClick()
        rule.onNodeWithText("Алиса",substring=true).assertIsDisplayed().performClick()
        previousAction().performClick()
        rule.runOnIdle {
            assertEquals(1,previous);assertEquals(0,navigation)
            assertEquals(1,paused);assertEquals(1,next);assertEquals(1,alice)
        }
        assertNoConfirmation()
    }

    @Test fun footerInvokesPreviousCallbackOnceImmediatelyWithoutConfirmation() {
        var previous=0;var navigation=0
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BakingPaper(bake(),1100,{},{},{},{navigation++},onPreviousStep={previous++})
        } }
        // Use the actual footer action, independent of its old/new text.
        previousAction().performClick()
        rule.runOnIdle { assertEquals(1,previous);assertEquals(0,navigation) }
        assertNoConfirmation()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1,previous) }
    }

    @Test fun footerNeverOpensADialogAndTopBackStillOnlyNavigates() {
        var previous=0;var navigation=0
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BakingPaper(bake(),1100,{},{},{},{navigation++},onPreviousStep={previous++})
        } }
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.runOnIdle { assertEquals(1,navigation);assertEquals(0,previous) }
        previousAction().performClick()
        assertNoConfirmation()
        rule.runOnIdle { assertEquals(1,navigation);assertEquals(1,previous) }
    }

    @Test fun revisionSessionSwitchAndRestoreNeverReplayPreviousOrKeepStaleCallback() {
        val calls=mutableListOf<Long>()
        val session=mutableStateOf(bake())
        val restoration=StateRestorationTester(rule)
        restoration.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            val current=session.value
            BakingPaper(current,1100,{},{},{},{},onPreviousStep={calls.add(current.id)})
        } }
        previousAction().performClick()
        rule.runOnIdle { assertEquals(listOf(7L),calls);session.value=session.value.copy(revision=1) }
        rule.runOnIdle { session.value=bake().copy(id=8,currentStepIndex=2) }
        restoration.emulateSavedInstanceStateRestore()
        assertNoConfirmation()
        rule.runOnIdle { assertEquals(listOf(7L),calls) }
        previousAction().performClick()
        rule.runOnIdle { assertEquals(listOf(7L,8L),calls) }
        assertNoConfirmation()
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
            previousAction().assertIsNotEnabled().performClick()
            assertFalse(blocked.canStepBack)
            assertNoConfirmation()
        }
        rule.runOnIdle { assertEquals(0,previous) }
    }

    @Test fun absentPreviousCallbackDisablesOnlyFooterAction() {
        var navigation=0;var next=0;var paused=0
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BakingPaper(bake(),1100,{paused++},{next++},{},{navigation++},onPreviousStep=null)
        } }
        previousAction().assertIsNotEnabled().performClick()
        assertNoConfirmation()
        rule.onNodeWithText("Пауза").performClick()
        rule.onNodeWithText("Дальше →").performClick()
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.runOnIdle { assertEquals(1,navigation);assertEquals(1,next);assertEquals(1,paused) }
    }
}
