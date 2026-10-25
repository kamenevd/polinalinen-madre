package com.polinalinen.madre.ui.kit

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.familyui.FamilyFixtureTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose semantics only, not physical vibration or a new settings design. */
@RunWith(AndroidJUnit4::class)
@Config(sdk=[35], application=Application::class)
class PaperHapticSettingsTest {
    @get:Rule val rule=createComposeRule()
    private val diagnostic=PaperHapticDiagnostic(true,false,
        "TOUCH · oneShot 25 мс · DEFAULT_AMPLITUDE", "SecurityException", false)
    private val plain="Нажмите «Проверить», чтобы почувствовать короткую вибрацию. Отклик зависит от настроек телефона."

    @Test fun releasePresentationHasExactlyOnePlainLineAndNoTechnicalRows() {
        rule.setContent { FamilyFixtureTheme(Atmosphere.DAWN) { Column {
            PaperHapticDiagnosticRows(diagnostic,qaDiagnostics=false)
        } } }
        rule.onNodeWithText(plain).assertExists()
        rule.onAllNodes(hasText("",substring=true)).assertCountEquals(1)
        for(token in listOf("hasVibrator","amplitudeControl","DEFAULT_AMPLITUDE","Последний вызов","TOUCH","SecurityException"))
            rule.onNodeWithText(token,substring=true).assertDoesNotExist()
    }
    @Test fun qaPresentationPreservesAllTechnicalDetailsAndPhysicalCaveat() {
        rule.setContent { FamilyFixtureTheme(Atmosphere.DAWN) { Column {
            PaperHapticDiagnosticRows(diagnostic,qaDiagnostics=true)
        } } }
        rule.onNodeWithText(plain).assertDoesNotExist()
        rule.onAllNodes(hasText("",substring=true)).assertCountEquals(4)
        rule.onNodeWithText("hasVibrator: да · amplitudeControl: нет").assertExists()
        rule.onNodeWithText("Последний вызов: TOUCH · oneShot 25 мс · DEFAULT_AMPLITUDE · ошибка: SecurityException").assertExists()
        rule.onNodeWithText("Android: вибрация касаний выключена. Система может подавить запрос TOUCH.").assertExists()
        rule.onNodeWithText("Запрос Android не подтверждает ощущение вибрации. Ограничения ОС и телефона сохраняются.").assertExists()
    }
}
