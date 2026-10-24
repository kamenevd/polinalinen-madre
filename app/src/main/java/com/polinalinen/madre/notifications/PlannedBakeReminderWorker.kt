package com.polinalinen.madre.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.navigation.BakePlanning
import com.polinalinen.madre.navigation.BookPreferences
import com.polinalinen.madre.navigation.PlannedBake
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Reminder only: WorkManager never creates a bake or stores account credentials. */
class PlannedBakeReminderWorker(context: Context, params: WorkerParameters): Worker(context,params) {
    override fun doWork(): Result {
        val id=inputData.getString(KEY_PLAN_ID)?:return Result.success()
        runBlocking{(applicationContext as MadreApplication).prepareBakeNumbers()}
        BookPreferences(applicationContext).use { prefs ->
            val plan=prefs.plans.firstOrNull{it.id==id}?:return Result.success()
            val recipe=runBlocking{(applicationContext as MadreApplication).recipeRepository.getRecipes()}.firstOrNull{it.id==plan.recipeId}
                ?:return Result.success()
            val nonce=prefs.notificationNonce(id,plan)?:return Result.retry()
            MadreNotifier(applicationContext).postPlannedBake(plan,recipe.name,nonce)
        }
        return Result.success()
    }
    companion object { const val KEY_PLAN_ID="planned_id" }
}

class PlannedBakeScheduler(private val context: Context) {
    fun schedule(plan: PlannedBake, nowMillis: Long = System.currentTimeMillis()): androidx.work.Operation {
        val request=OneTimeWorkRequestBuilder<PlannedBakeReminderWorker>()
            .setInitialDelay((BakePlanning.reminderAt(plan,nowMillis)-nowMillis).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(PlannedBakeReminderWorker.KEY_PLAN_ID to plan.id)).build()
        return WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(plan.id),ExistingWorkPolicy.REPLACE,request)
    }
    fun cancel(id: String):androidx.work.Operation {
        val operation=WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
        MadreNotifier(context).cancelPlannedBake(id)
        return operation
    }
    /** Closes process death between private-plan commit and durable WorkManager enqueue. */
    fun restoreMissing(plans: List<PlannedBake>) {
        if(plans.isEmpty())return
        val manager=WorkManager.getInstance(context)
        plans.forEach { plan ->
            val known=manager.getWorkInfosForUniqueWork(uniqueName(plan.id)).get()
            if(known.none{!it.state.isFinished || it.state==androidx.work.WorkInfo.State.SUCCEEDED})schedule(plan).result.get()
        }
    }
    companion object { fun uniqueName(id: String)="planned-bake-$id" }
}
