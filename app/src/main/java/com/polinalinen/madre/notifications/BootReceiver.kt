package com.polinalinen.madre.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.polinalinen.madre.MadreApplication
import java.util.concurrent.TimeUnit

/**
 * Cycle 15: перезагрузка телефона больше не отменяет выпечку.
 *
 * Отсчёт шага живёт в BakingViewModel, то есть внутри процесса приложения:
 * ребут уносил его целиком, и уведомление «расстойка подошла» не приходило
 * никогда — человек узнавал об этом, открыв книгу. Пережившее ребут состояние
 * лежит в active_bakes (Room), а разбудить книгу и заново поставить сроки
 * некому, кроме BOOT_COMPLETED.
 *
 * Сам receiver не делает НИЧЕГО, кроме постановки работы в очередь: ни Room, ни
 * своего CoroutineScope здесь быть не должно — процесс после onReceive живёт
 * считанные секунды и вправе умереть посреди запроса (баг v3 #2). Всю работу
 * делает [BakeRestoreWorker], которому WorkManager это время гарантирует.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            RESTORE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BakeRestoreWorker>().build(),
        )
    }

    companion object {
        const val RESTORE_WORK_NAME = "restore-active-bakes"
    }
}

/**
 * Читает незавершённые выпечки и заново ставит срок каждой. Решение по каждой
 * строке принимает [BootRestorePlanner] — здесь только Room и WorkManager.
 */
class BakeRestoreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MadreApplication ?: return Result.success()
        val repository = app.activeBakeRepository
        val workManager = WorkManager.getInstance(applicationContext)
        val now = System.currentTimeMillis()

        BootRestorePlanner.plan(repository.all(), now).forEach { (bake, decision) ->
            when (decision) {
                is BootRestorePlanner.Decision.Forget -> repository.forget(decision.sessionId)
                is BootRestorePlanner.Decision.Keep -> Unit
                is BootRestorePlanner.Decision.Notify -> {
                    val request = OneTimeWorkRequestBuilder<BakeStepEndWorker>()
                        .setInitialDelay(decision.delayMillis, TimeUnit.MILLISECONDS)
                        .setInputData(
                            workDataOf(
                                BakeStepEndWorker.KEY_SESSION_ID to bake.sessionId,
                                BakeStepEndWorker.KEY_STEP_INDEX to bake.stepIndex,
                                BakeStepEndWorker.KEY_RECIPE_NAME to bake.recipeName,
                                BakeStepEndWorker.KEY_STEP_TITLE to bake.stepTitle,
                            )
                        )
                        .build()
                    workManager.enqueueUniqueWork(
                        BootRestorePlanner.uniqueWorkName(bake.sessionId),
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
                }
            }
        }
        return Result.success()
    }
}

/**
 * «Время вышло» для шага, который досчитал до нуля уже без живого таймера.
 *
 * Ключ тот же, что у BakingViewModel ([BakingNotificationPlanner.stepDoneKey]):
 * если приложение к этому моменту успело открыться и отсчитать шаг само,
 * уведомление заменит своё же, а не встанет вторым.
 */
class BakeStepEndWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        if (sessionId < 0) return Result.success()
        val stepIndex = inputData.getInt(KEY_STEP_INDEX, 0)
        val recipeName = inputData.getString(KEY_RECIPE_NAME).orEmpty()
        val stepTitle = inputData.getString(KEY_STEP_TITLE).orEmpty()

        MadreNotifier(applicationContext).postBakingNotification(
            key = BakingNotificationPlanner.stepDoneKey(sessionId, stepIndex),
            title = "«$recipeName» — время вышло",
            text = "$stepTitle: шаг закончен, можно продолжать.",
        )
        return Result.success()
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_STEP_INDEX = "step_index"
        const val KEY_RECIPE_NAME = "recipe_name"
        const val KEY_STEP_TITLE = "step_title"
    }
}
