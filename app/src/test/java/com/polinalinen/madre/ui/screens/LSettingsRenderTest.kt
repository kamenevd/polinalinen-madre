package com.polinalinen.madre.ui.screens

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.account.*
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual native Compose, synthetic state. Not SDK login, phone, EGL or full-family acceptance. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=Application::class)
class LSettingsRenderTest {
    @get:Rule val rule=createComposeRule()
    @Test fun actualL1Frames() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val cases=listOf("guest","signed","nofam","profile","look","notify","about","dev","starter")
            .flatMap { scene -> listOf(Atmosphere.DAWN,Atmosphere.NIGHT).map{scene to it} }
        var current by mutableStateOf<Pair<String,Atmosphere>?>(null)
        rule.setContent { current?.let{(scene,theme)->key(scene,theme){
            val prefs=remember{ReadingPreferences(context,"l-render-$scene-${theme.name}").apply {chooseAtmosphere(Atmosphere.AUTO);setReduced(false)}}
            RazvorotTheme(prefs,theme) {
                val profile=VerifiedYandexProfile("synthetic-l-fixture","dima","Дмитрий Каменев",null,true,1L)
                val legacy=if(scene=="signed")FamilyBookState.SignedIn(FamilyAccount("fixture-user","","Дима","fixture-family","Семья Каменевых")) else FamilyBookState.SignedOut
                LSettingsContent("Дима",{},{},{},{},"Мадре",{},24,{},true,{},3,7,prefs,legacy,
                    com.polinalinen.madre.model.BookDisplaySettings(),{},{},null,
                    initialPage=if(scene in listOf("profile","look","notify","about","dev"))scene else "root",
                    accountOverride=if(scene=="guest")YandexAccountState.SignedOut else YandexAccountState.SignedIn(profile),
                    aliceStatusOverride="Нет связи · открыть подключение")
                if(scene=="starter")StarterSettingsSheet("Мадре",{},24,{},true,{}){}
            }
        }} }
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app")else File(".")
        val directory=File(module,"build/outputs/roborazzi/settings-l").apply{mkdirs()}
        cases.forEach { (scene,theme)->
            rule.runOnIdle{current=null};rule.waitForIdle()
            rule.runOnIdle{current=scene to theme};rule.waitForIdle()
            val output=File(directory,"$scene-${if(theme==Atmosphere.NIGHT)"night" else "dawn"}.png")
            captureScreenRoboImage(filePath=output.absolutePath,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record))
            val bitmap=requireNotNull(BitmapFactory.decodeFile(output.path))
            assertEquals(412,bitmap.width);assertEquals(915,bitmap.height);bitmap.recycle()
        }
    }
}
