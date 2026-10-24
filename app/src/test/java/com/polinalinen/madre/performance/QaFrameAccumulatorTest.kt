package com.polinalinen.madre.performance

import org.junit.Assert.*
import org.junit.Test

class QaFrameAccumulatorTest {
    @Test fun idleDoesNotPretendToBeZeroFpsOrJank() {
        val buffer=QaFrameAccumulator();assertNull(buffer.sample().fps)
        buffer.add(1,10_000_000,false);assertNull(buffer.sample().fps)
    }
    @Test fun copiedPrimitiveFramesProduceNearestRankP95AndReset() {
        val buffer=QaFrameAccumulator()
        buffer.add(0,10_000_000,false);buffer.add(16_000_000,20_000_000,false);buffer.add(32_000_000,30_000_000,true)
        val sample=buffer.sample();assertEquals(3,sample.frames);assertEquals(30.0,sample.p95UiMillis,.001)
        assertEquals(62.5,sample.fps!!,.001);assertEquals(100.0/3,sample.jankPercent,.001)
        assertEquals(0,buffer.sample().frames)
    }
    @Test fun storageStaysBoundedAcrossManyFrames() {
        val buffer=QaFrameAccumulator();repeat(2000){buffer.add(it*16_000_000L,10_000_000,false)}
        assertEquals(2000,buffer.sample().frames)
    }
}
