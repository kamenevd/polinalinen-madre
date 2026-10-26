package com.polinalinen.madre.navigation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.TimelineStep
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.ui.kit.Atmosphere
import com.polinalinen.madre.ui.kit.RazvorotTheme
import com.polinalinen.madre.ui.razvorot.BookStartSheet
import com.polinalinen.madre.viewmodel.fakes.sampleRecipe
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BookPlanningUiTest {
    @get:Rule val rule=createComposeRule()
    private val recipe=sampleRecipe().copy(timeline=listOf(TimelineStep(StepType.WAIT,"Опара","Синтетический шаг интерфейсного теста",10)))
    private val now=LocalDate.of(2026,10,23).atTime(22,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    // The ambient clock can retain a zone initialized by an earlier timezone-changing test.
    // Keep this fixture's existing 22:00 instant and its zone together.
    private val clock=Clock.fixed(Instant.ofEpochMilli(now),ZoneId.systemDefault())

    @Test fun unavailableDinnerIsDisabledAndSchedulingDoesNotStartTheTimer() {
        var started=0;var scheduled:Pair<Int,Long>?=null
        rule.setContent { RazvorotTheme(force=Atmosphere.NIGHT) {
            val inheritedClock=com.polinalinen.madre.ui.razvorot.LocalBookClock.current
            println("BookPlanning clock diagnostic nowMillis=$now fixtureZone=${clock.zone} fixtureLocal=${clock.instant().atZone(clock.zone)} inheritedZone=${inheritedClock.zone} inheritedLocal=${Instant.ofEpochMilli(now).atZone(inheritedClock.zone)} inheritedDinnerAvailable=${BakePlanning.options(10,now,inheritedClock.zone)[0].available}")
            BookStartSheet(recipe,2,{started++},{},onPlan={portions,start->scheduled=portions to start},nowMillis=now,clock=clock)
        } }
        rule.onNodeWithText("Ко времени").performClick()
        rule.onNodeWithText("К ужину · 19:00").assertIsNotEnabled()
        rule.onNodeWithText("К завтраку · 8:00").performClick()
        assertEquals(0,started);assertNull(scheduled)
        rule.onNodeWithText("Запланировать").performScrollTo().performClick()
        assertEquals(0,started)
        assertEquals(2,scheduled!!.first)
        assertEquals(BakePlanning.options(10,now)[1].startAtMillis,scheduled!!.second)
    }
    @Test fun busyPlanCannotBeSubmittedAgain() {
        var calls=0
        rule.setContent { RazvorotTheme(force=Atmosphere.DAWN) {
            BookStartSheet(recipe,2,{calls++},{},onPlan={_,_->calls++},initialMode=1,busy=true,nowMillis=now,clock=clock)
        } }
        rule.onNodeWithText("Сохраняем…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("К завтраку · 8:00").assertIsNotEnabled()
        assertEquals(0,calls)
    }
}
