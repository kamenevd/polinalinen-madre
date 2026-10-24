package com.polinalinen.madre.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.*
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.razvorot.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BookClockUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun actualAssetDurationHeaderChosenChipAndSubmittedPlanUseOneClock() {
        val app=ApplicationProvider.getApplicationContext<MadreApplication>()
        val recipe=runBlocking{RecipeRepository(app).getRecipes()}.single{it.id=="home_bread"}
        val clock=Clock.fixed(Instant.parse("2026-10-24T13:40:00Z"),ZoneId.of("Europe/Moscow"))
        var scheduled:Long?=null
        val open=androidx.compose.runtime.mutableStateOf(false)
        rule.setContent{CompositionLocalProvider(LocalBookClock provides clock){RazvorotTheme(force=Atmosphere.DAWN){Box {
            BookOvenContent(listOf(recipe),emptyList(),emptyList(),{},{open.value=true},{},{},{})
            if(open.value)BookStartSheet(recipe,2,{fail("Scheduling must not start a timer")},{},initialMode=1,onPlan={_,at->scheduled=at})
        }}}}
        rule.onNodeWithText("ПЕЧЬ · СУББОТА, 24 ОКТЯБРЯ").assertExists()
        rule.onNodeWithText(recipe.name).performScrollTo().performClick()
        rule.onNodeWithText("К ужину · 19:00").assertIsNotEnabled()
        rule.onNodeWithText("Завтра к ужину").assertIsSelected()
        rule.onNodeWithText("Начать сегодня в 20:30").assertExists()
        rule.onNodeWithText("Готово завтра в 19:00").assertExists()
        rule.onNodeWithText("Запланировать").performScrollTo().performClick()
        assertNotNull(scheduled)
        assertTrue(scheduled!!>=clock.millis())
        assertEquals(BakePlanning.options(RecipeScale.totalMinutes(recipe,2),clock.millis(),clock.zone)[3].readyAtMillis,
            scheduled!!+RecipeScale.totalMinutes(recipe,2)*60_000L)
    }
    @Test fun timePassingAfterRenderCannotSubmitAPastStart() {
        val clock=MovingClock(Instant.parse("2026-10-24T15:45:00Z"))
        val recipe=com.polinalinen.madre.viewmodel.fakes.sampleRecipe().copy(timeline=listOf(
            TimelineStep(StepType.WAIT,"Синтетическая пауза","Только интерфейсный тест",10)))
        var actions=0
        rule.setContent{RazvorotTheme(force=Atmosphere.NIGHT){
            BookStartSheet(recipe,2,{actions++},{},onPlan={_,_->actions++},initialMode=1,clock=clock)
        }}
        rule.onNodeWithText("К ужину · 19:00").assertIsSelected().assertIsEnabled()
        clock.point=Instant.parse("2026-10-24T16:10:00Z")
        rule.onNodeWithText("Запланировать").performScrollTo().performClick()
        assertEquals(0,actions)
        rule.onNodeWithText("На этот срок уже не успеть. Выберите другое время или начните сейчас.").assertExists()
    }
    private class MovingClock(var point:Instant):Clock() {
        override fun getZone()=ZoneId.of("Europe/Moscow")
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(point,zone)
        override fun instant()=point
    }
}
