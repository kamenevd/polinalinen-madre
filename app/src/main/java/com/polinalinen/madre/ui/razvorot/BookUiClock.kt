package com.polinalinen.madre.ui.razvorot

import androidx.compose.runtime.*
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** One injectable wall clock for oven date, ready presets and the plan. Monotonic bake timers are unchanged. */
val LocalBookClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }
@Composable internal fun rememberBookNow(clock:Clock):Long {
    var now by remember(clock) { mutableLongStateOf(clock.millis()) }
    LaunchedEffect(clock) { while(true) { delay(30_000); now=clock.millis() } }
    return now
}
internal fun relativeBookTime(atMillis:Long,nowMillis:Long,clock:Clock):String {
    val at=Instant.ofEpochMilli(atMillis).atZone(clock.zone)
    val today=Instant.ofEpochMilli(nowMillis).atZone(clock.zone).toLocalDate()
    val day=when(at.toLocalDate()) {
        today->"сегодня";today.plusDays(1)->"завтра"
        else->at.format(DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("ru")))
    }
    return "$day в ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}
