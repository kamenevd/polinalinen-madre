package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.ui.kit.RazvorotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** NAV replaces the obsolete two-state source-string proof with real production choice interactions.
 * The separate ShelfReviewTest two-state policy contract is unchanged. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BakingCompleteShelfButtonUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun missingPhotoDisablesOnlyPhotoAndPrivateChoiceCanReturnToStatOnly() {
        var choice by mutableStateOf(ShelfShareDecision.PUT_WITHOUT_PHOTO)
        rule.setContent { RazvorotTheme { BakeFinaleShareChoices(choice,false,false){choice=it} } }
        rule.onNodeWithText("С фото").assertIsNotEnabled()
        rule.onNodeWithText("Без фото").assertIsSelected()
        rule.onNodeWithText("Только мне").performClick()
        assertEquals(ShelfShareDecision.KEEP,choice)
        rule.onNodeWithText("Без фото").performClick()
        assertEquals(ShelfShareDecision.PUT_WITHOUT_PHOTO,choice)
        rule.onNodeWithText("Поставить на полку?").assertDoesNotExist()
    }
    @Test fun realPhotoChoiceCanBeChangedBackToStatOnlyOrPrivate() {
        var choice by mutableStateOf(ShelfShareDecision.PUT_WITH_PHOTO)
        rule.setContent { RazvorotTheme { BakeFinaleShareChoices(choice,true,false){choice=it} } }
        rule.onNodeWithText("С фото").assertIsSelected()
        rule.onNodeWithText("Без фото").performClick()
        assertEquals(ShelfShareDecision.PUT_WITHOUT_PHOTO,choice)
        rule.onNodeWithText("С фото").performClick()
        assertEquals(ShelfShareDecision.PUT_WITH_PHOTO,choice)
        rule.onNodeWithText("Только мне").performClick()
        assertEquals(ShelfShareDecision.KEEP,choice)
    }
    @Test fun busyFinishDisablesEveryShareChoice() {
        var calls=0
        rule.setContent { RazvorotTheme { BakeFinaleShareChoices(ShelfShareDecision.PUT_WITHOUT_PHOTO,true,true){calls++} } }
        listOf("С фото","Без фото","Только мне").forEach {
            rule.onNodeWithText(it).assertIsNotEnabled().performTouchInput{click()}
        }
        assertEquals(0,calls)
    }
}
