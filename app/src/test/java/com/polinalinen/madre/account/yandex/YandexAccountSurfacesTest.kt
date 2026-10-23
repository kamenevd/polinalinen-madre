package com.polinalinen.madre.account.yandex

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
class YandexAccountSurfacesTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()

    @Test fun optionalOfferCanBeSkippedWithoutLaunchingLogin() {
        var skipped=0;var logins=0
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            YandexOfferContent(YandexAccountState.SignedOut,false,{logins++},{skipped++},{})
        }}
        rule.onNodeWithContentDescription("Войти с Яндекс ID").assertIsDisplayed()
        rule.onNodeWithText("Продолжить без входа").assertIsDisplayed().performClick()
        rule.runOnIdle{assertEquals(1,skipped);assertEquals(0,logins)}
    }

    @Test fun unconfirmedServerRevocationIsVisibleAndCannotLaunchANewLogin() {
        var retries=0;var logins=0
        rule.setContent{RazvorotTheme(force=Atmosphere.NIGHT){
            YandexAccountPanel(YandexAccountState.SignedOut,true,{logins++},{},{retries++})
        }}
        rule.onNodeWithContentDescription("Войти с Яндекс ID").assertDoesNotExist()
        rule.onNodeWithText("На телефоне отключено. Отзыв доступа на сервере ещё не подтверждён.").assertIsDisplayed()
        rule.onNodeWithText("Повторить отключение").performClick()
        rule.runOnIdle{assertEquals(1,retries);assertEquals(0,logins)}
    }

    @Test fun onlySigningInOffersBrowserFallbackAndMissingAvatarHasNamedMonogram() {
        val state=mutableStateOf<YandexAccountState>(YandexAccountState.SigningIn(1L,false))
        var browsers=0
        rule.setContent{RazvorotTheme(force=Atmosphere.DUSK){
            YandexAccountPanel(state.value,false,{if(it)browsers++},{},{})
        }}
        rule.onNodeWithText("ВОЙТИ ЧЕРЕЗ БРАУЗЕР").performClick()
        rule.runOnIdle{assertEquals(1,browsers);state.value=YandexAccountState.Binding(1L)}
        rule.onNodeWithText("ВОЙТИ ЧЕРЕЗ БРАУЗЕР").assertDoesNotExist()
        rule.onNodeWithText("Узнаём вас…").assertIsDisplayed()
        rule.runOnIdle{state.value=YandexAccountState.SignedIn(VerifiedYandexProfile(
            "synthetic_uid_hash","fixture-login","Синтетическое имя",null,true,1000L))}
        rule.onNodeWithContentDescription("Аккаунт Яндекса, Синтетическое имя",useUnmergedTree=true).assertExists()
        rule.onNodeWithText("Синтетическое имя").assertIsDisplayed()
        rule.onNodeWithContentDescription("Войти с Яндекс ID").assertDoesNotExist()
    }
}
