package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.BuildConfig
import com.polinalinen.madre.performance.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable internal fun QaDiagnosticControls() {
    if(!BuildConfig.QA_DIAGNOSTICS)return
    val monitor=LocalQaFrameMonitor.current?:return
    var open by remember{mutableStateOf(false)}
    PaperTextAction("Диагностика QA",{open=!open})
    if(open) {
        PaperSettingSwitch("Показывать FPS",monitor.enabled,monitor::setEnabled)
        Text("Локальный JankStats, без отправки данных. Обновление раз в секунду. Простой не означает лаги.",style=PaperType.note,color=LocalPaper.current.ink2)
    }
}
/** Only this leaf reads samples; no Activity/chapter state is written on a frame. */
@Composable internal fun QaFpsOverlay(monitor:QaFrameMonitor,modifier:Modifier=Modifier) {
    var sample by remember(monitor){mutableStateOf(QaFrameSnapshot())}
    LaunchedEffect(monitor) {
        while(true){delay(1000);sample=withContext(Dispatchers.Default){monitor.sample()}}
    }
    Column(modifier.background(LocalPaper.current.bg2).padding(8.dp).testTag("qa-fps-overlay")) {
        val fps=sample.fps?.let{String.format(Locale.ROOT,"%.1f FPS",it)}?:"Мало кадров / простой"
        Text(fps,style=PaperType.ui,color=LocalPaper.current.ink)
        Text(String.format(Locale.ROOT,"%d кадров · UI p95 %.1f мс · jank %.1f%%",sample.frames,sample.p95UiMillis,sample.jankPercent),style=PaperType.note,color=LocalPaper.current.ink2)
        Text("JankStats · текущее окно · 1 с",style=PaperType.note,color=LocalPaper.current.ink2)
    }
}
