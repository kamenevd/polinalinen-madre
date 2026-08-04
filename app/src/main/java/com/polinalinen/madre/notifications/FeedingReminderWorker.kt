package com.polinalinen.madre.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.polinalinen.madre.sourdough.StarterName
import java.util.concurrent.TimeUnit

/**
 * Cycle 11: напоминание о кормлении едет через WorkManager, а не AlarmManager.
 * Точность «плюс-минус несколько минут» для суточного цикла закваски избыточна
 * и так, а exact alarm стоит отдельного разрешения и репутации у батареи —
 * ровно на этом v3 обожглась (баг #5).
 */
class FeedingReminderWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        // Cycle 14: в шторке стоит то же имя, что в дневнике и в колофоне —
        // одно на всю книгу, приведённое к общему виду в StarterName.
        val name = StarterName.sanitize(inputData.getString(KEY_STARTER_NAME).orEmpty())
        MadreNotifier(applicationContext).postFeedingReminder(
            title = StarterName.hungryTitle(name),
            text = "Пора кормить — мука и вода по вашему обычному соотношению.",
        )
        return Result.success()
    }

    companion object {
        const val KEY_STARTER_NAME = "starter_name"
    }
}

/**
 * Ставит и снимает то самое напоминание. Отдельный класс от планировщика:
 * [FeedingReminderPlanner] решает КОГДА (и проверяется юнит-тестом),
 * scheduler — только разговаривает с WorkManager.
 *
 * Перепланирование всегда идёт по одному имени с REPLACE: новое кормление,
 * смена интервала и выключение напоминаний не оставляют позади «хвост» из
 * старых запросов.
 */
class FeedingReminderScheduler(private val context: Context) {

    fun apply(configId: Long, plan: FeedingReminderPlan, starterName: String) {
        val workManager = WorkManager.getInstance(context)
        val uniqueName = FeedingReminderPlanner.uniqueWorkName(configId)
        when (plan) {
            is FeedingReminderPlan.Cancel -> workManager.cancelUniqueWork(uniqueName)
            is FeedingReminderPlan.Schedule -> {
                val request = OneTimeWorkRequestBuilder<FeedingReminderWorker>()
                    .setInitialDelay(plan.delayMillis, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(FeedingReminderWorker.KEY_STARTER_NAME to starterName))
                    .build()
                workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
