package com.polinalinen.madre.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.polinalinen.madre.MainActivity
import com.polinalinen.madre.navigation.BookPreferences
import com.polinalinen.madre.navigation.PlannedBake

/** Only notification capabilities may request a write. An ordinary external intent can only open the plan. */
enum class PlannedBakeAction { OPEN, START, SNOOZE }
data class PlannedBakeRequest(val id: String, val action: PlannedBakeAction)

object PlannedBakeActions {
    const val EXTRA_ACTION="madre.planned_action"
    private const val EXTRA_NONCE="madre.planned_nonce"
    fun intent(context: Context, plan: PlannedBake, action: PlannedBakeAction, nonce: String? = null): Intent =
        Intent(context,MainActivity::class.java)
            .setAction("${context.packageName}.planned.${action.name}")
            .setData(Uri.fromParts("madre-planned",plan.id,null))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_PLANNED_ID,plan.id)
            .putExtra(EXTRA_ACTION,action.name)
            .apply { if(nonce!=null)putExtra(EXTRA_NONCE,nonce) }
    fun pendingIntent(context: Context, plan: PlannedBake, action: PlannedBakeAction, nonce: String? = null): PendingIntent =
        PendingIntent.getActivity(context,1202,intent(context,plan,action,nonce),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun read(context: Context, intent: Intent?): PlannedBakeRequest? {
        val id=intent?.getStringExtra(MainActivity.EXTRA_PLANNED_ID)?.takeIf{it.isNotBlank()}?:return null
        val action=runCatching{PlannedBakeAction.valueOf(intent.getStringExtra(EXTRA_ACTION).orEmpty())}.getOrNull()?:return null
        return BookPreferences(context).use { prefs ->
            if(prefs.plans.none{it.id==id})return@use null
            if(action!=PlannedBakeAction.OPEN && !prefs.consumeNotificationAction(id,intent.getStringExtra(EXTRA_NONCE)))return@use null
            PlannedBakeRequest(id,action)
        }
    }
}
