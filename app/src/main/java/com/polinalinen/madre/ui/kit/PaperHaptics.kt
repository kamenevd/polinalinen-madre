package com.polinalinen.madre.ui.kit

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal enum class PaperHaptic { TOUCH, PAGE, TIMER }
internal interface PaperHapticPlatform {
    fun systemAllows():Boolean
    fun viewFeedback(kind:PaperHaptic):Boolean
    fun vibratorFeedback(kind:PaperHaptic):Boolean
}
/** Direct request first. A View's Boolean is never evidence of a motor request. */
internal class PaperHapticDispatcher(private val platform:PaperHapticPlatform) {
    fun perform(enabled:Boolean,kind:PaperHaptic=PaperHaptic.TOUCH):Boolean {
        if(!enabled || !platform.systemAllows())return false
        val requested=runCatching{platform.vibratorFeedback(kind)}.getOrDefault(false)
        if(!requested)runCatching{platform.viewFeedback(kind)}
        return requested
    }
}
internal data class PaperHapticDiagnostic(val hasVibrator:Boolean?=null,val amplitudeControl:Boolean?=null,
    val lastCall:String="Ещё не проверяли",val error:String?=null)
internal object PaperHapticDiagnostics {
    var state by androidx.compose.runtime.mutableStateOf(PaperHapticDiagnostic())
        private set
    fun report(value:PaperHapticDiagnostic){state=value}
}
internal class AndroidPaperHapticPlatform(private val context:Context,private val view:View?,
    private val vibratorProvider:()->Vibrator?={
        if(Build.VERSION.SDK_INT>=31)context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    },private val vibrationSender:(Vibrator,VibrationEffect,AudioAttributes?)->Unit={motor,effect,attributes->
        if(attributes==null)motor.vibrate(effect) else @Suppress("DEPRECATION") motor.vibrate(effect,attributes)
    }):PaperHapticPlatform {
    fun probe():PaperHapticDiagnostic = try {
        vibratorProvider()?.let{PaperHapticDiagnostics.state.copy(hasVibrator=it.hasVibrator(),amplitudeControl=it.hasAmplitudeControl())}
            ?:PaperHapticDiagnostic(lastCall="Нет сервиса Vibrator")
    }catch(error:Exception){PaperHapticDiagnostic(lastCall="Ошибка сервиса",error=error.javaClass.simpleName)}
    override fun systemAllows():Boolean {
        val policy=context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val allowed=policy==null || policy==NotificationManager.INTERRUPTION_FILTER_ALL || policy==NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        if(!allowed)PaperHapticDiagnostics.report(PaperHapticDiagnostics.state.copy(lastCall="Ограничено режимом «Не беспокоить»",error=null))
        return allowed
    }
    override fun viewFeedback(kind:PaperHaptic):Boolean = view?.performHapticFeedback(
        if(kind==PaperHaptic.TIMER)HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK,
        0) ?: false
    @Suppress("DEPRECATION")
    override fun vibratorFeedback(kind:PaperHaptic):Boolean {
        try {
            val vibrator=vibratorProvider()
            if(vibrator==null){PaperHapticDiagnostics.report(PaperHapticDiagnostic(lastCall="Нет сервиса Vibrator"));return false}
            val diagnostic=PaperHapticDiagnostic(vibrator.hasVibrator(),vibrator.hasAmplitudeControl())
            if(diagnostic.hasVibrator!=true){PaperHapticDiagnostics.report(diagnostic.copy(lastCall="Устройство сообщает: мотора нет"));return false}
            if(kind==PaperHaptic.TIMER) {
                vibrationSender(vibrator,VibrationEffect.createWaveform(longArrayOf(0,80,60,120),-1),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            } else {
                // UNKNOWN/default usage, no FORCE flags, no alarm disguise or system-setting writes.
                // Android/OEM still decides whether the foreground request can play.
                vibrationSender(vibrator,VibrationEffect.createOneShot(25,VibrationEffect.DEFAULT_AMPLITUDE),null)
            }
            PaperHapticDiagnostics.report(diagnostic.copy(lastCall=if(kind==PaperHaptic.TIMER)"TIMER · waveform · ALARM" else "$kind · oneShot 25 мс · DEFAULT"))
            return true
        } catch(error:Exception) {
            PaperHapticDiagnostics.report(PaperHapticDiagnostics.state.copy(lastCall="Прямой запрос не передан",error=error.javaClass.simpleName))
            return false
        }
    }
}
internal fun performPaperHaptic(context:Context,view:View?=null,kind:PaperHaptic=PaperHaptic.TOUCH,
    enabled:Boolean=context.getSharedPreferences("razvorot_reading",Context.MODE_PRIVATE).getBoolean("haptics",true)):Boolean =
    if(!enabled){PaperHapticDiagnostics.report(PaperHapticDiagnostics.state.copy(lastCall="Отключено в приложении",error=null));false}
    else PaperHapticDispatcher(AndroidPaperHapticPlatform(context,view)).perform(true,kind)
