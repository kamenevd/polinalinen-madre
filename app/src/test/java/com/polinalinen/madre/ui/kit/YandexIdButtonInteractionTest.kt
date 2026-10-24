package com.polinalinen.madre.ui.kit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class YandexIdButtonInteractionTest {
    @get:Rule val rule=createComposeRule()
    @Test fun officialArtworkIsARealAccessibleButtonAndDisabledDoesNotInvokeLogin() {
        val dark=mutableStateOf(false);val enabled=mutableStateOf(true);val size=mutableStateOf(YandexIdButtonSize.M)
        var clicks=0
        rule.setContent {RazvorotTheme(force=if(dark.value)Atmosphere.NIGHT else Atmosphere.DAWN) {
            YandexIdButton({clicks++},enabled=enabled.value,size=size.value)
        }}
        val button=rule.onNodeWithContentDescription("Войти с Яндекс ID")
        button.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button))
        assertTrue(button.fetchSemanticsNode().boundsInRoot.height>=48f)
        button.performClick();rule.runOnIdle{assertEquals(1,clicks);dark.value=true;enabled.value=false;size.value=YandexIdButtonSize.L}
        button.assertIsDisplayed().assertIsNotEnabled()
        assertTrue(button.fetchSemanticsNode().boundsInRoot.height>=56f)
        rule.runOnIdle{assertEquals(1,clicks)}
    }
}
