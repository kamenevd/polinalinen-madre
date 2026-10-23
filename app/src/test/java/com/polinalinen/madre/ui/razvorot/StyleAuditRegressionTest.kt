package com.polinalinen.madre.ui.razvorot

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.PI

@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi")
class StyleAuditRegressionTest {
    @get:Rule val rule=createComposeRule()
    @Test fun exactNineTokensForAllFourAtmospheres() {
        val expected=listOf(
            longArrayOf(0xFFF4F1EC,0xFFEAE5DD,0xFFFFFFFF,0xFF16130F,0xFF4F4943,0xFF8F8880,0xFFE1DBD2,0xFFD2602A,0xFFB83A2E),
            longArrayOf(0xFFF8F3EA,0xFFEFE7DA,0xFFFFFFFF,0xFF16130F,0xFF4F4943,0xFF8F8880,0xFFE6DCCD,0xFFD2602A,0xFFB83A2E),
            longArrayOf(0xFFF1E2CC,0xFFE7D3B6,0xFFFBF1E2,0xFF2A1C10,0xFF5F4630,0xFF9A7F64,0xFFDCC6A6,0xFFC24E1C,0xFFB83A2E),
            longArrayOf(0xFF0D0B0A,0xFF1A1512,0xFF191411,0xFFF2E9DE,0xFFC0B3A5,0xFF7F7469,0xFF2A231F,0xFFF08A4B,0xFFD0503F))
        listOf(DawnPaper,DayPaper,DuskPaper,NightPaper).forEachIndexed { i,p ->
            assertThat(listOf(p.bg,p.bg2,p.card,p.ink,p.ink2,p.mut,p.line,p.acc,p.ribbon).map{it.toArgb().toLong() and 0xffffffffL}).containsExactlyElementsIn(expected[i].toList()).inOrder()
        }
    }
    @Test fun everyAvailableMaterialColorRoleIsFromTheApprovedPalette() {
        for(p in listOf(DawnPaper,DayPaper,DuskPaper,NightPaper)) {
            val scheme=paperScheme(p)
            val allowed=listOf(p.bg,p.bg2,p.card,p.ink,p.ink2,p.mut,p.line,p.acc,p.ribbon,Color.Black,Color.White).map{it.value.toLong()}
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
    @Test fun exactV3LightNumbersAndNightSymmetry() {
        assertThat(PaperLight.faceShade(0f)).isEqualTo(1f)
        assertThat(PaperLight.faceShade((PI/2).toFloat())).isWithin(.00001f).of(.6f)
        assertThat(PaperLight.versoShade((PI/2).toFloat())).isWithin(.00001f).of(.6f)
        assertThat(PaperLight.versoShade(PI.toFloat())).isWithin(.00001f).of(.8f)
        assertThat(PaperLight.DESATURATION).isEqualTo(.45f)
        assertThat(PaperLight.PRINT).isEqualTo(.07f)
        assertThat(PaperLight.HIGHLIGHT).isEqualTo(.035f)
        assertThat(PaperLight.SHADOW).isEqualTo(.58f)
        assertThat(PaperLight.SHADOW_MAX).isEqualTo(.8f)
        assertThat(PaperLight.crease(0f,60f,false)).isWithin(.00001f).of(.76f)
        assertThat(PaperLight.crease(0f,60f,true)).isWithin(.00001f).of(.616f)
        assertThat(PaperLight.receiver(54f,60f,false)).isWithin(.00001f).of(.42f)
        assertThat(PaperLight.receiver(54f,60f,true)).isWithin(.00001f).of(.2f)
        for(i in 0..100) {
            val t=(PI/2*i/100).toFloat()
            assertThat(PaperLight.faceShade(t)+PaperLight.highlight(t,false)).isAtMost(1f)
            assertThat(PaperLight.crease(i.toFloat(),60f,true)).isEqualTo(PaperLight.crease(-i.toFloat(),60f,true))
        }
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
