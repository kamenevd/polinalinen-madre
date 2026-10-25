package com.polinalinen.madre.ui.screens

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.model.BookDisplaySettings
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=Application::class)
class LSettingsContractTest {
    @get:Rule val rule=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Application>()

    @Test fun autoActuallyFollowsSystemDarkAndLeavesSavedChoiceAsAuto() {
        val preferences=ReadingPreferences(context,"l-auto-contract").apply{chooseAtmosphere(Atmosphere.AUTO)}
        var night by mutableStateOf(false)
        var actual:Atmosphere?=null
        rule.setContent {
            val config=Configuration(context.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if(night)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                RazvorotTheme(preferences) { val value=LocalAtmosphere.current;SideEffect{actual=value} }
            }
        }
        rule.runOnIdle{assertEquals(Atmosphere.DAWN,actual);night=true}
        rule.runOnIdle{assertEquals(Atmosphere.NIGHT,actual);night=false}
        rule.runOnIdle{assertEquals(Atmosphere.DAWN,actual)}
        assertEquals(Atmosphere.AUTO,ReadingPreferences(context,"l-auto-contract").atmosphere)
    }

    @Test fun storedDayAndDuskRemainUsableButPickerExposesOnlyTheThreeApprovedChoices() {
        val preferences=ReadingPreferences(context,"l-legacy-palette")
        var actual:PaperColors?=null
        preferences.chooseAtmosphere(Atmosphere.DAY)
        rule.setContent { RazvorotTheme(preferences) {
            val colors=LocalPaper.current;SideEffect{actual=colors}
            PaperAtmospherePicker(preferences)
        } }
        rule.runOnIdle{assertEquals(DayPaper,actual);preferences.chooseAtmosphere(Atmosphere.DUSK)}
        rule.runOnIdle{assertEquals(DuskPaper,actual)}
        assertEquals(Atmosphere.DUSK,ReadingPreferences(context,"l-legacy-palette").atmosphere)
        rule.onNodeWithText("День").assertDoesNotExist()
        rule.onNodeWithText("Вечер").assertDoesNotExist()
        listOf("Утро","Ночь","Авто").forEach{rule.onNodeWithText(it).assertExists()}
    }

    @Test fun rootUsesRealRouteCallbacksAndTheSharedStarterEntryWithoutInlineControls() {
        var family=0;var alice=0;var starter=0
        val preferences=ReadingPreferences(context,"l-routes")
        rule.setContent { RazvorotTheme(preferences,Atmosphere.DAWN) {
            LSettingsContent("",{},{},{family++},{alice++},"Мадре",{},24,{},true,{},0,0,
                preferences,FamilyBookState.SignedOut,BookDisplaySettings(),{},{},null,
                onStarterSettings={starter++})
        } }
        rule.onNodeWithText("Нужен вход с Яндекс ID").assertExists()
        rule.onNodeWithText("Семейная полка").performClick()
        rule.onNodeWithText("Алиса").performClick()
        rule.onNodeWithText("Закваска").performClick()
        rule.runOnIdle{assertEquals(1,family);assertEquals(1,alice);assertEquals(1,starter)}
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("hasVibrator",substring=true).assertDoesNotExist()
    }

    @Test fun starterDoneSavesCapitalizedNameAndKeepsAllFiveIntervalsReachable() {
        var name="";var interval=24;var done=false
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            StarterSettingsContent("соня",{name=it},interval,{interval=it},true,{},{done=true})
        } }
        rule.onNodeWithText("неделя").performScrollTo().performClick()
        rule.onNodeWithText("Готово").performScrollTo().performClick()
        rule.runOnIdle{assertEquals("Соня",name);assertEquals(168,interval);assertTrue(done)}
    }

    @Test fun inlineDefaultStepperUsesCurrentRecipeAndPersistsBothBoundsAndReset() {
        val book=com.polinalinen.madre.navigation.BookPreferences(context,"l-default-portions")
        try {
            book.savePortions("bread",3)
            book.saveBookmark(com.polinalinen.madre.navigation.BookBookmark("bread",0))
            rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) { DefaultPortionsSettingRow(book) } }
            rule.onNodeWithText("Порции по умолчанию").assertExists()
            rule.onNodeWithText("×3").assertExists()
            rule.onNodeWithText("Как в рецепте").assertExists()
            repeat(2){rule.onNodeWithText("+").performClick()}
            rule.onNodeWithText("×5").assertExists()
            rule.onNodeWithText("+").assertIsNotEnabled()
            repeat(4){rule.onNodeWithText("−").performClick()}
            rule.onNodeWithText("−").assertIsNotEnabled()
            com.polinalinen.madre.navigation.BookPreferences(context,"l-default-portions").use {
                assertEquals(1,it.defaultPortions);assertEquals(1,it.portions("bread"))
                assertEquals(3,context.getSharedPreferences("l-default-portions",0).getInt("chapter_portions_bread",-1))
            }
            rule.onNodeWithText("Как в рецепте").performClick()
            rule.onNodeWithText("×3").assertExists()
            rule.runOnIdle{assertNull(book.defaultPortions)}
        } finally { book.close() }
    }

    @Test fun readerQuickSheetDoesNotDuplicateHapticsOrMotionSwitch() {
        val preferences=ReadingPreferences(context,"l-quick-contract")
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            com.polinalinen.madre.ui.razvorot.ReadingSettings(preferences){}
        } }
        rule.onNodeWithText("Как читать").assertExists()
        rule.onNodeWithText("Меньше движения").assertDoesNotExist()
        rule.onNodeWithText("Тактильный отклик").assertDoesNotExist()
        rule.onNodeWithText("Вибрация при касаниях").assertDoesNotExist()
        rule.onNodeWithText("Проверить").assertDoesNotExist()
    }

    @Test fun bothProductionEntriesMountOneNavOwnedStarterSheet() {
        val nav=File("src/main/java/com/polinalinen/madre/navigation/MadreNavHost.kt").readText()
        assertTrue(nav.contains("onOpenStarterSettings = { starterSettingsOpen=true }"))
        assertTrue(nav.contains("onStarterSettings = { starterSettingsOpen=true }"))
        assertTrue(nav.contains("if (starterSettingsOpen) com.polinalinen.madre.ui.screens.StarterSettingsSheet"))
        assertEquals(1,Regex("if \\(starterSettingsOpen\\)").findAll(nav).count())
    }
}
