package com.polinalinen.madre.ui.screens

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=MadreApplication::class,qualifiers="ru-w412dp-h915dp-mdpi")
class SettingsInputHintsTest {
    @get:Rule val rule=createComposeRule()
    private lateinit var view:View
    private fun show(content:@Composable ()->Unit){rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){view=LocalView.current;Column{content()}}}}
    private fun flags(label:String):Int {
        rule.onNodeWithText(label).performClick();rule.waitForIdle()
        var bits=0
        rule.runOnIdle{val info=EditorInfo();assertNotNull(view.onCreateInputConnection(info));bits=info.inputType}
        return bits
    }
    @Test fun actualOwnNameFieldRequestsSentencesWithoutRewritingItsValue() {
        show{SettingsContent("полина",{},{},{},"Мадре",{},0,0,24,{},true,{},false,{},null,FamilyBookState.SignedOut,{},{},initialPage="profile")}
        assertTrue(flags("полина") and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES!=0)
        rule.onNodeWithText("полина").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("полина")))
    }
    @Test fun actualFamilyFieldRequestsWordsButCodeDoesNot() {
        show{FamilyBookField("Название полки","каменевы",onValueChange={});FamilyBookField("Код приглашения","abc123",onValueChange={})}
        assertTrue(flags("каменевы") and InputType.TYPE_TEXT_FLAG_CAP_WORDS!=0)
        assertEquals(0,flags("abc123") and (InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
    }
    @Test fun fullSettingsAtDoubleFontRemainScrollableAndHapticSwitchKeepsRealPreference() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs=ReadingPreferences(context,"settings15-large").apply{chooseHaptics(true)}
        show{
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)) {
                SettingsContent("Полина",{},{},{},"Мадре",{},3,7,24,{},true,{},false,{},"Семья Каменевых",FamilyBookState.SignedOut,{},{},preferences=prefs)
            }
        }
        rule.onNodeWithText("О приложении").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Уведомления и вибрация").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Вибрация при касаниях").performScrollTo().assertIsDisplayed().assertIsOn().performClick().assertIsOff()
        rule.runOnIdle{assertFalse(ReadingPreferences(context,"settings15-large").haptics)}
        rule.onNodeWithText("Проверить").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle{assertFalse(ReadingPreferences(context,"settings15-large").haptics)}
    }
    @Test fun actualStarterFieldRequestsSentencesWithoutChangingStoredText() {
        show{StarterNameField("соня",{})}
        assertTrue(flags("соня") and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES!=0)
        rule.onNodeWithText("соня").assertTextEquals("соня")
    }
}
