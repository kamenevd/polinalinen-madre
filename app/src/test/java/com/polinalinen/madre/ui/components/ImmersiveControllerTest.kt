package com.polinalinen.madre.ui.components

import com.polinalinen.madre.ui.kit.ImmersiveController
import org.junit.Assert.*
import org.junit.Test

class ImmersiveControllerTest {
    @Test fun requestsAreScopedAndAChildPhotoCanOverrideRevealedReaderChrome() {
        val controller = ImmersiveController()
        val reader = Any(); val photo = Any()
        controller.request(reader, true); assertTrue(controller.wanted)
        controller.request(reader, false); assertFalse(controller.wanted)
        controller.request(photo, true); assertTrue(controller.wanted)
        controller.release(photo); assertFalse(controller.wanted)
        controller.request(reader, true); assertTrue(controller.wanted)
        controller.release(reader); assertFalse(controller.wanted)
    }
    @Test fun paperBrightnessAndModeAreIndependent() {
        val controller = ImmersiveController()
        controller.setPaperLight(false); assertFalse(controller.lightPaper)
        controller.request(Any(), true); assertTrue(controller.wanted)
        controller.setPaperLight(true); assertTrue(controller.wanted); assertTrue(controller.lightPaper)
    }
    @Test fun darkModalToneSurvivesThemeRecompositionAndRestoresOnDismiss() {
        val controller = ImmersiveController()
        val photo = Any(); val nested = Any()
        controller.setPaperLight(true)
        controller.requestTone(photo, false); assertFalse(controller.lightPaper)
        controller.setPaperLight(true); assertFalse(controller.lightPaper)
        controller.requestTone(nested, true); assertTrue(controller.lightPaper)
        controller.releaseTone(nested); assertFalse(controller.lightPaper)
        controller.releaseTone(photo); assertTrue(controller.lightPaper)
    }
}
