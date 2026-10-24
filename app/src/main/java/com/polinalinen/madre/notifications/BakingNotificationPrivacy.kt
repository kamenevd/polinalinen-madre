package com.polinalinen.madre.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.polinalinen.madre.R

/** A hidden lock-screen progress notification exposes no recipe, step, remaining time or actions. */
internal fun NotificationCompat.Builder.bakingLockScreenPrivacy(
    context: Context, channel: String, showProgress: Boolean, numbers:List<Int> = emptyList(),plannedNumber:Int?=null,
): NotificationCompat.Builder {
    setVisibility(if (showProgress) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
    if (!showProgress) setPublicVersion(NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification_bread)
        .setContentTitle("Мадре")
        .setContentText(if(plannedNumber!=null && plannedNumber in 1..9)"Запланированная выпечка №$plannedNumber" else publicBakeNumbers(numbers))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build())
    return this
}

internal fun publicBakeNumbers(numbers:List<Int>):String {
    val valid=numbers.filter{it in 1..9}.distinct().sorted()
    return if(valid.isEmpty())"Выпечка идёт" else "Выпечка ${valid.joinToString(" и "){"№$it"}} ${if(valid.size==1)"идёт" else "идут"}"
}
