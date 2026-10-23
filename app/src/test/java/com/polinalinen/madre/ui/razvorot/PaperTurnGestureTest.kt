package com.polinalinen.madre.ui.razvorot

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w360dp-h640dp-xhdpi",application=MadreApplication::class)
class PaperTurnGestureTest {
    @get:Rule val rule=createComposeRule()
    private var page by mutableIntStateOf(1)
    private fun show() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("paper_gesture_test",0).edit().clear().commit()
        val prefs=ReadingPreferences(context,"paper_gesture_test").apply{chooseMode(ReadingMode.SLIDE)}
        rule.setContent { RazvorotTheme(prefs,Atmosphere.DAWN) {
            Box(Modifier.size(360.dp,600.dp)){PaperTurn(page,3,prefs,{page=it},Modifier.testTag("paper-test")){index->Text("Страница $index")}}
        } }
        rule.waitForIdle()
    }
    @Test fun textAndVerticalDragsAreInert() {
        show()
        rule.onNodeWithTag("paper-test").performTouchInput{swipe(Offset(width*.95f,height*.2f),Offset(width*.95f,height*.8f),400)}
        rule.waitForIdle();assertThat(page).isEqualTo(1)
        rule.onNodeWithTag("paper-test").performTouchInput{swipe(center,Offset(width*.05f,center.y),400)}
        rule.waitForIdle();assertThat(page).isEqualTo(1)
    }
    @Test fun forwardAndBackwardFlickUseRealWidget() {
        show()
        rule.onNodeWithTag("paper-test").performTouchInput{swipeLeft(durationMillis=150)}
        rule.waitUntil(3000){page==2};assertThat(page).isEqualTo(2)
        rule.onNodeWithTag("paper-test").performTouchInput{swipe(Offset(width*.12f,center.y),Offset(width*.85f,center.y),150)}
        rule.waitUntil(3000){page==1};assertThat(page).isEqualTo(1)
    }
    @Test fun cancellationKeepsCurrentPage() {
        show()
        rule.onNodeWithTag("paper-test").performTouchInput{
            down(Offset(width*.95f,center.y));moveTo(Offset(width*.4f,center.y),300);cancel()
        }
        rule.waitForIdle();assertThat(page).isEqualTo(1)
    }
}
