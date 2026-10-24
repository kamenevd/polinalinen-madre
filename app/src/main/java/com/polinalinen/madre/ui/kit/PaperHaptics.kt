package com.polinalinen.madre.ui.kit


import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

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
    val lastCall:String="Ещё не проверяли",val error:String?=null,val systemTouchEnabled:Boolean?=null)
internal object PaperHapticDiagnostics {
    var state by androidx.compose.runtime.mutableStateOf(PaperHapticDiagnostic())
        private set
    fun report(value:PaperHapticDiagnostic){state=value}
}
internal class AndroidPaperHapticPlatform(private val context:Context,private val view:View?,
    private val vibratorProvider:()->Vibrator?={
        (if(Build.VERSION.SDK_INT>=31)context.getSystemService(VibratorManager::class.java)?.defaultVibrator else null)
            ?: @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    },private val vibrationSender:(Vibrator,VibrationEffect,AudioAttributes?)->Unit={motor,effect,attributes->
        if(Build.VERSION.SDK_INT>=33)motor.vibrate(effect,VibrationAttributes.Builder().setUsage(
            if(attributes?.usage==AudioAttributes.USAGE_ALARM)VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_TOUCH).build())
        else @Suppress("DEPRECATION") motor.vibrate(effect,requireNotNull(attributes))
    }):PaperHapticPlatform {
    private fun systemTouchEnabled():Boolean?=runCatching {
        Settings.System.getInt(context.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED)!=0
    }.getOrNull()
    fun probe():PaperHapticDiagnostic = try {
        vibratorProvider()?.let{PaperHapticDiagnostics.state.copy(hasVibrator=it.hasVibrator(),amplitudeControl=it.hasAmplitudeControl(),systemTouchEnabled=systemTouchEnabled())}
            ?:PaperHapticDiagnostic(lastCall="Нет сервиса Vibrator")
    }catch(error:Exception){PaperHapticDiagnostic(lastCall="Ошибка сервиса",error=error.javaClass.simpleName)}
    override fun systemAllows():Boolean {
        // Do not guess per-usage DND policy from a coarse interruption-filter enum.
        // Unprivileged native vibrate() enforces OS/OEM policy; no bypass flags/settings.
        return true
    }
    override fun viewFeedback(kind:PaperHaptic):Boolean = view?.performHapticFeedback(
        if(kind==PaperHaptic.TIMER)HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK,
        0) ?: false
    @Suppress("DEPRECATION")
    override fun vibratorFeedback(kind:PaperHaptic):Boolean {
        try {
            val vibrator=vibratorProvider()
            if(vibrator==null){PaperHapticDiagnostics.report(PaperHapticDiagnostic(lastCall="Нет сервиса Vibrator"));return false}
            val diagnostic=PaperHapticDiagnostic(vibrator.hasVibrator(),runCatching{vibrator.hasAmplitudeControl()}.getOrNull(),systemTouchEnabled=systemTouchEnabled())
            if(diagnostic.hasVibrator!=true){PaperHapticDiagnostics.report(diagnostic.copy(lastCall="Устройство сообщает: мотора нет"));return false}
            if(kind==PaperHaptic.TIMER) {
                vibrationSender(vibrator,VibrationEffect.createWaveform(longArrayOf(0,80,60,120),-1),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            } else {
                // API26–32 audio overload maps sonification to TOUCH; API33+ uses VibrationAttributes.
                // UNKNOWN is not a workaround: API31 reclassifies a short UNKNOWN as TOUCH.
                // OS/OEM touch/DND policy still applies. No alarm disguise or bypass flags.
                vibrationSender(vibrator,VibrationEffect.createOneShot(25,VibrationEffect.DEFAULT_AMPLITUDE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            PaperHapticDiagnostics.report(diagnostic.copy(lastCall=if(kind==PaperHaptic.TIMER)"TIMER · waveform · ALARM" else "TOUCH · oneShot 25 мс · DEFAULT_AMPLITUDE"))
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
