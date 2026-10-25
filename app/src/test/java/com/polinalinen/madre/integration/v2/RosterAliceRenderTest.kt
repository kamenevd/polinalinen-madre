package com.polinalinen.madre.integration.v2

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.familyui.FamilyFixtureTheme
import com.polinalinen.madre.ui.kit.Atmosphere
import com.polinalinen.madre.ui.screens.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=Application::class)
class RosterAliceRenderTest {
    @get:Rule val rule=createComposeRule()
    @Test fun actualJ05J06FramesDawnNightAndNoPicker() {
        val bakes=(1..3).map { n -> RosterBake("00000000-0000-0000-0000-00000000000$n",n,1,"fixture-recipe",
            listOf("Хлебушек домашний","Чиабатта","Пицца")[n-1],listOf(RosterBakeState.RUNNING,RosterBakeState.PAUSED,RosterBakeState.SCHEDULED)[n-1],
            0,2,"Опара",600,if(n==3)1792917000000 else null,portions=if(n==3)null else 2,controlReady=n!=3) }
        val state=RosterUiState(RosterStatus.CONNECTED,bakes,true)
        var scene by mutableStateOf<Pair<Boolean,Atmosphere>?>(null)
        var demoState by mutableStateOf(state)
        var consents=0
        rule.setContent {scene?.let{(sheet,theme)->key(sheet,theme){FamilyFixtureTheme(theme){
            if(sheet) RosterAliceSheetContent(demoState,2,{},{}) else RosterAlicePage(demoState,{},{consents++},{},{})
        }}}}
        val module=if(File("app/src/main/AndroidManifest.xml").exists())File("app")else File(".")
        val dir=File(module,"build/outputs/roborazzi/roster-v2").apply{mkdirs()}
        fun capture(name:String){
            val file=File(dir,"$name.png")
            captureScreenRoboImage(filePath=file.absolutePath,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record))
            val bitmap=BitmapFactory.decodeFile(file.path)!!
            assertEquals(412,bitmap.width);assertEquals(915,bitmap.height);bitmap.recycle()
        }
        for(theme in listOf(Atmosphere.DAWN,Atmosphere.NIGHT)) for(sheet in listOf(false,true)){
            rule.runOnIdle{scene=null;demoState=state};rule.waitForIdle();rule.runOnIdle{scene=sheet to theme};rule.waitForIdle()
            rule.onNodeWithText("Выберите выпечку").assertDoesNotExist()
            rule.onNodeWithText("Выпечка",useUnmergedTree=true).assertDoesNotExist()
            val suffix=if(theme==Atmosphere.NIGHT)"night"else"dawn"
            capture(if(sheet)"alice-sheet-numbers-$suffix"else"alice-linked-numbers-$suffix")
            if(!sheet){
                rule.runOnIdle { demoState=state.copy(status=RosterStatus.UNLINKED,consent=false) }
                val before=consents
                rule.onNodeWithContentDescription("Управлять голосом · все текущие выпечки").performScrollTo().performClick()
                rule.onNodeWithText("Разрешить для всех текущих выпечек?").assertIsDisplayed()
                rule.runOnIdle{assertEquals(before,consents)}
                rule.waitForIdle();capture("alice-installation-consent-$suffix")
                rule.onNodeWithText("Да · разрешить").performClick()
                rule.runOnIdle{assertEquals(before+1,consents)}
            } else {
                rule.onNodeWithText("Повтори шаг").performClick()
                rule.onNodeWithText("«Алиса, спроси у Голоса закваски, повтори шаг для выпечки номер 2»").assertIsDisplayed()
                assertEquals(2,state.bakes[1].bakeNumber) // example tap never selects/mutates a bake
            }
        }
        assertEquals(6,dir.listFiles{f->f.extension=="png"}!!.size)
    }
}
