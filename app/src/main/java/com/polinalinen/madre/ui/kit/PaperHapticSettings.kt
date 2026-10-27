package com.polinalinen.madre.ui.kit

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit test does not change, or depend on, the saved automatic-feedback opt-out. */
@Composable internal fun PaperHapticSettings(preferences:ReadingPreferences) {
    val context=LocalContext.current
    val view=LocalView.current
    LaunchedEffect(context) {
        val probe=withContext(Dispatchers.IO){AndroidPaperHapticPlatform(context,null).probe()}
        val current=PaperHapticDiagnostics.state
        PaperHapticDiagnostics.report(if(current.lastCall=="Ещё не проверяли")probe
            else current.copy(hasVibrator=probe.hasVibrator,amplitudeControl=probe.amplitudeControl))
    }
    PaperSettingSwitch("Тактильный отклик",preferences.haptics,preferences::chooseHaptics)
    PaperButton("Проверить",{performPaperHaptic(context,view,enabled=true)},haptic=false,variant=PaperButtonVariant.TEXT)
    PaperHapticDiagnosticRows(PaperHapticDiagnostics.state)
    QaDiagnosticControls()
}

/** Production uses its existing build flag; the explicit argument exercises both compiled presentations. */
@Composable internal fun PaperHapticDiagnosticRows(diagnostic:PaperHapticDiagnostic,
    qaDiagnostics:Boolean=com.polinalinen.madre.BuildConfig.QA_DIAGNOSTICS) {
    if(qaDiagnostics) {
        fun flag(value:Boolean?)=when(value){true->"да";false->"нет";null->"неизвестно"}
        Text("hasVibrator: ${flag(diagnostic.hasVibrator)} · amplitudeControl: ${flag(diagnostic.amplitudeControl)}",style=PaperType.note,color=LocalPaper.current.ink2)
        Text("Последний вызов: ${diagnostic.lastCall}"+diagnostic.error?.let{" · ошибка: $it"}.orEmpty(),style=PaperType.note,color=LocalPaper.current.ink2)
        Text(when(diagnostic.systemTouchEnabled) {
            false->"Android: вибрация касаний выключена. Система может подавить запрос TOUCH."
            true->"Android: вибрация касаний включена. Другие ограничения ОС могут подавить TOUCH."
            null->"Android: настройка касаний недоступна. Система может подавить запрос TOUCH."
        },style=PaperType.note,color=LocalPaper.current.ink2)
        Text("Запрос Android не подтверждает ощущение вибрации. Ограничения ОС и телефона сохраняются.",style=PaperType.note,color=LocalPaper.current.ink2)
    } else {
        Text("Нажмите «Проверить», чтобы почувствовать короткую вибрацию. Отклик зависит от настроек телефона.",style=PaperType.note,color=LocalPaper.current.ink2)
    }
}
