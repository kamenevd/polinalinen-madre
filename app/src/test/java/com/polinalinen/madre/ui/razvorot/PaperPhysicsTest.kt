package com.polinalinen.madre.ui.razvorot

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class PaperPhysicsTest {
    @Test fun edgeAndDirectionLock() {
        assertThat(Curl.direction(350f,-20f,0f,360f)).isEqualTo(1)
        assertThat(Curl.direction(30f,20f,0f,360f)).isEqualTo(-1)
        assertThat(Curl.direction(10f,20f,0f,360f)).isEqualTo(0)
        assertThat(Curl.direction(180f,-200f,0f,360f)).isEqualTo(0)
        assertThat(Curl.direction(350f,0f,100f,360f)).isEqualTo(0)
        assertThat(Curl.direction(350f,-7f,0f,360f)).isEqualTo(0)
        assertThat(Curl.direction(350f,-10f,30f,360f)).isEqualTo(0)
    }
    @Test fun threeGripZones() {
        assertThat(Curl.zone(20f,780f)).isEqualTo(-22f)
        assertThat(Curl.zone(390f,780f)).isEqualTo(0f)
        assertThat(Curl.zone(760f,780f)).isEqualTo(22f)
    }
    @Test fun verticalCannotDetachSheet() {
        for(y in listOf(20f,390f,760f)) {
            val fold=PaperFold.fromFinger(360f,780f,1,350f,y,350f,y+120f)
            assertThat(fold.length).isEqualTo(0f)
            assertThat(fold.spineAttached()).isTrue()
        }
    }
    @Test fun spineAndRadiusAndVerticalBound() {
        for(y in listOf(20f,390f,760f))for(x in 80..350 step 10)for(dy in listOf(-200f,0f,200f)) {
            val fold=PaperFold.fromFinger(360f,780f,1,350f,y,x.toFloat(),y+dy)
            assertThat(fold.spineAttached()).isTrue()
            assertThat(fold.radius).isWithin(.001f).of(kotlin.math.max(38f,78f-.06f*fold.length))
            // The zone base is at most22 degrees and finger delta at most15.
            assertThat(abs(fold.phi)).isAtMost(Math.toRadians(37.0).toFloat())
        }
    }
    @Test fun horizontalReleaseOnly() {
        assertThat(Curl.complete(1,.3f,-.6f)).isTrue()
        assertThat(Curl.complete(1,.3f,0f)).isFalse()
        assertThat(Curl.complete(1,.51f,.11f)).isTrue()
        assertThat(Curl.complete(1,.51f,.13f)).isFalse()
        assertThat(Curl.complete(-1,.3f,.6f)).isTrue()
        assertThat(Curl.complete(-1,.51f,-.13f)).isFalse()
    }
    @Test fun smoothEdgeLift() {
        val a=PaperFold.anchored(360f,780f,390f,0f,0f)
        val b=PaperFold.anchored(360f,780f,390f,.01f,0f)
        assertThat(a.distance).isWithin(.001f).of(0f)
        // theta-sin(theta)=L/r must not jump to a 90 degree edge at touchdown.
        assertThat(b.distance/b.radius).isLessThan(.2f)
    }
    @Test fun springSettlesWithoutFixedTween() {
        val s=PaperSpring(0f,0f,100f,36f,1f)
        s.step(16f);val first=s.x
        s.step(16f)
        assertThat(first).isGreaterThan(0f)
        assertThat(s.x).isGreaterThan(first)
        repeat(150){s.step(16f)}
        assertThat(s.x).isWithin(.01f).of(100f)
    }
    @Test fun lowFpsMustBeContinuousAndLatchesForSession() {
        val fps=PaperFrameRate()
        for(t in 30L..1980L step 30)fps.frame(t,30f)
        assertThat(fps.disabled).isFalse()
        fps.frame(1996L,16f)
        for(t in 2026L..4066L step 30)fps.frame(t,30f)
        assertThat(fps.disabled).isTrue()
        fps.frame(4082L,16f)
        assertThat(fps.disabled).isTrue()
    }
    @Test fun themeHourBoundaries() {
        assertThat(atmosphereAt(4)).isEqualTo(Atmosphere.NIGHT)
        assertThat(atmosphereAt(5)).isEqualTo(Atmosphere.DAWN)
        assertThat(atmosphereAt(11)).isEqualTo(Atmosphere.DAY)
        assertThat(atmosphereAt(17)).isEqualTo(Atmosphere.DUSK)
        assertThat(atmosphereAt(22)).isEqualTo(Atmosphere.NIGHT)
    }
}
