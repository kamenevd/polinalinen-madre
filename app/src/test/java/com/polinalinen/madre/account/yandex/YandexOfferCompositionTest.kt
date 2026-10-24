package com.polinalinen.madre.account.yandex

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class YandexOfferCompositionTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun signedOutOfferHasAllBenefitsAndSkipDoesNotLaunchLogin() {
        var skipped=0;var logins=0
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            YandexOfferContent(YandexAccountState.SignedOut,false,{logins++},{skipped++},{})
        }}
        listOf("Алиса в одно касание","Копия на Яндекс Диске","Ваше имя и портрет","Вместе за закваской")
            .forEach{rule.onNodeWithText(it).assertIsDisplayed()}
        rule.onNodeWithText("Войдите — и Алиса\nузнает вашу книгу").assertExists()
        rule.onNodeWithContentDescription("Назад").assertDoesNotExist()
        rule.onNodeWithText("ПОЗЖЕ").performClick()
        rule.runOnIdle{assertEquals(1,skipped);assertEquals(0,logins)}
    }
}
