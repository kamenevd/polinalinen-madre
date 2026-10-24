package com.polinalinen.madre.ui.kit

import org.junit.Assert.*
import org.junit.Test

class PaperHapticsTest {
    private class Platform:PaperHapticPlatform {
        var allowed=true;var viewResult=true;var vibrationResult=true;var failView=false
        var viewCalls=0;var vibrationCalls=0;var kind:PaperHaptic?=null
        override fun systemAllows()=allowed
        override fun viewFeedback(kind:PaperHaptic):Boolean { viewCalls++;this.kind=kind;if(failView)error("unsupported OEM view path");return viewResult }
        override fun vibratorFeedback(kind:PaperHaptic):Boolean { vibrationCalls++;this.kind=kind;return vibrationResult }
    }
    @Test fun appOptOutNeverCallsEitherPlatformPath(){val p=Platform();assertFalse(PaperHapticDispatcher(p).perform(false));assertEquals(0,p.viewCalls+p.vibrationCalls)}
    @Test fun explicitPlatformDenialStopsBothPaths(){val p=Platform().apply{allowed=false};assertFalse(PaperHapticDispatcher(p).perform(true));assertEquals(0,p.viewCalls+p.vibrationCalls)}
    @Test fun successfulDirectRequestNeverDoublesWithView(){val p=Platform();assertTrue(PaperHapticDispatcher(p).perform(true,PaperHaptic.PAGE));assertEquals(0,p.viewCalls);assertEquals(1,p.vibrationCalls)}
    @Test fun viewTrueCannotSuppressOrFakeDirectRequest(){val p=Platform().apply{vibrationResult=false};assertFalse(PaperHapticDispatcher(p).perform(true,PaperHaptic.TIMER));assertEquals(1,p.viewCalls);assertEquals(1,p.vibrationCalls);assertEquals(PaperHaptic.TIMER,p.kind)}
    @Test fun throwingSupplementDoesNotCrash(){val p=Platform().apply{vibrationResult=false;failView=true};assertFalse(PaperHapticDispatcher(p).perform(true));assertEquals(1,p.vibrationCalls)}
    @Test fun deviceWithoutVibratorReturnsHonestFailure(){val p=Platform().apply{viewResult=false;vibrationResult=false};assertFalse(PaperHapticDispatcher(p).perform(true));assertEquals(1,p.vibrationCalls)}
}
