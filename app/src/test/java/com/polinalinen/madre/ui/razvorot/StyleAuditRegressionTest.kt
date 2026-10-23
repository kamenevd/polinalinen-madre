package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config


@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi")
class StyleAuditRegressionTest {
    @get:Rule val rule=createComposeRule()

    @Test fun everyAvailableMaterialColorRoleIsFromTheApprovedPalette() {
        for(p in listOf(DawnPaper,DayPaper,DuskPaper,NightPaper)) {
            val scheme=paperScheme(p)
            val allowed=listOf(p.bg,p.bg2,p.card,p.ink,p.ink2,p.mut,p.line,p.acc,p.acc2,p.ribbon,p.sage,p.sage2,p.error,Color.Black,Color.White).map{it.value.toLong()}
            val getters=ColorScheme::class.java.methods.filter{it.name.startsWith("get")&&it.parameterCount==0&&it.returnType==java.lang.Long.TYPE}
            assertThat(getters.size).isAtLeast(35)
            getters.forEach{g->assertWithMessage(g.name).that(g.invoke(scheme) as Long).isIn(allowed)}
            assertThat(scheme.onSurfaceVariant).isEqualTo(p.mut)
            assertThat(scheme.outline).isEqualTo(p.line)
            assertThat(scheme.secondary).isEqualTo(p.acc)
            assertThat(scheme.surfaceContainer).isEqualTo(p.card)
        }
    }
    @Test fun overlineHasRealBoldCapsAndTracking() {
        val prefs=ReadingPreferences(ApplicationProvider.getApplicationContext<Context>(),"audit-overline")
        rule.setContent{RazvorotTheme(prefs,Atmosphere.DAWN){SmallLabel("Пекарь и Ёжик")}}
        val result=mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("ПЕКАРЬ И ЁЖИК").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(result)}
        assertThat(result.single().layoutInput.style.fontWeight).isEqualTo(FontWeight.Bold)
        assertThat(result.single().layoutInput.style.fontSize.value).isEqualTo(11f)
        assertThat(result.single().layoutInput.style.letterSpacing.value).isEqualTo(1.8f)
    }

    @Test fun tocChapterHeadingUsesRealMediumWeight() {
        val recipe=Recipe("audit","Глава для проверки","","",emptyMap(),listOf(TimelineStep(StepType.ACTION,"Замес","",5)))
        val prefs=ReadingPreferences(ApplicationProvider.getApplicationContext<Context>(),"audit-fan-weight")
        rule.setContent {RazvorotTheme(prefs,Atmosphere.DAWN){BookTocContent(listOf(recipe,recipe.copy(id="second",name="Вторая"),recipe.copy(id="third",name="Третья")),emptyList(),null,{_,_->},{})}}
        val results=mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(recipe.name,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(results)}
        assertThat(results.single().layoutInput.style.fontWeight).isEqualTo(FontWeight.Medium)
    }
    @Test fun bakingStepHeadingUsesMediumAndOnlyWaitingStepsAreItalic() {
        val type=mutableStateOf(StepType.ACTION)
        val prefs=ReadingPreferences(ApplicationProvider.getApplicationContext<Context>(),"audit-bake-heading")
        rule.setContent {
            val recipe=Recipe("audit","Аудит","","",emptyMap(),listOf(TimelineStep(type.value,"Заголовок шага","Описание",5)))
            RazvorotTheme(prefs,Atmosphere.DAWN){BakingPaper(BakingSession(7,recipe,0,0L,0L),120L,{},{},{},{})}
        }
        fun heading():TextLayoutResult {
            val results=mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("Заголовок шага",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(results)}
            return results.single()
        }
        assertThat(heading().layoutInput.style.fontWeight).isEqualTo(FontWeight.Medium)
        assertThat(heading().layoutInput.style.fontStyle).isEqualTo(FontStyle.Normal)
        rule.runOnIdle{type.value=StepType.WAIT}
        assertThat(heading().layoutInput.style.fontWeight).isEqualTo(FontWeight.Medium)
        assertThat(heading().layoutInput.style.fontStyle).isEqualTo(FontStyle.Italic)
    }
    @Test fun readingMetadataUsesActualTimelineAndPreservesHoursAndRemainders() {
        val recipe=Recipe("audit","Аудит","","",emptyMap(),listOf(TimelineStep(StepType.ACTION,"Замес","",5),TimelineStep(StepType.WAIT,"Ночь","",600),TimelineStep(StepType.ACTION,"Дальше","",10)))
        assertThat(stepOverline(recipe,1)).isEqualTo("Через 5 мин · Ждём 10 ч")
        assertThat(stepOverline(recipe,2)).isEqualTo("Через 10 ч 5 мин · 10 мин")
        assertThat(recipe.timeline[1].durationMinutes).isEqualTo(600)
        assertThat(staticTimerRequired("HUAWEI","huawei")).isTrue()
        assertThat(staticTimerRequired("google","google")).isFalse()
    }
}
