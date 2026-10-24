package com.polinalinen.madre.ui.kit

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

internal enum class PaperHaptic { TOUCH, PAGE, TIMER }
internal interface PaperHapticPlatform {
    fun systemAllows():Boolean
    fun viewFeedback(kind:PaperHaptic):Boolean
    fun vibratorFeedback(kind:PaperHaptic):Boolean
}
/** One dispatch, one fallback; app opt-out and system policy are authoritative. */
internal class PaperHapticDispatcher(private val platform:PaperHapticPlatform) {
    fun perform(enabled:Boolean,kind:PaperHaptic=PaperHaptic.TOUCH):Boolean {
        if(!enabled || !platform.systemAllows())return false
        if(runCatching{platform.viewFeedback(kind)}.getOrDefault(false))return true
        return runCatching{platform.vibratorFeedback(kind)}.getOrDefault(false)
    }
}
internal class AndroidPaperHapticPlatform(private val context:Context,private val view:View?):PaperHapticPlatform {
    override fun systemAllows():Boolean {
        if(Settings.System.getInt(context.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1)==0)return false
        val policy=context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return policy==null || policy==NotificationManager.INTERRUPTION_FILTER_ALL || policy==NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
    override fun viewFeedback(kind:PaperHaptic):Boolean = view?.performHapticFeedback(
        if(kind==PaperHaptic.TIMER)HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING) ?: false
    @Suppress("DEPRECATION")
    override fun vibratorFeedback(kind:PaperHaptic):Boolean {
        val vibrator=if(Build.VERSION.SDK_INT>=31)context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if(vibrator?.hasVibrator()!=true)return false
        val effectId=if(kind==PaperHaptic.TIMER)VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
        val predefined=Build.VERSION.SDK_INT>=29 && (Build.VERSION.SDK_INT<30 ||
            vibrator.areEffectsSupported(effectId).firstOrNull()!=Vibrator.VIBRATION_EFFECT_SUPPORT_NO)
        val effect=if(predefined)VibrationEffect.createPredefined(effectId)
            else VibrationEffect.createOneShot(if(kind==PaperHaptic.TIMER)30L else 20L,VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        return true
    }
}
internal fun performPaperHaptic(context:Context,view:View?=null,kind:PaperHaptic=PaperHaptic.TOUCH,
    enabled:Boolean=context.getSharedPreferences("razvorot_reading",Context.MODE_PRIVATE).getBoolean("haptics",true)):Boolean =
    PaperHapticDispatcher(AndroidPaperHapticPlatform(context,view)).perform(enabled,kind)
