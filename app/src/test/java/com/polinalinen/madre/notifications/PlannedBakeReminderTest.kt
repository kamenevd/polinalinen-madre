package com.polinalinen.madre.notifications

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import androidx.work.workDataOf
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.navigation.BakePlanning
import com.polinalinen.madre.navigation.BookPreferences
import com.polinalinen.madre.navigation.PlannedBake
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** Real private preferences, WorkManager database, notifications and Room; no family device or account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=MadreApplication::class)
class PlannedBakeReminderTest {
    private val app get()=ApplicationProvider.getApplicationContext<MadreApplication>()
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runCatching{WorkManager.initialize(app,Configuration.Builder().build())}
    }
    private fun plan()=runBlocking {
        PlannedBake(app.recipeRepository.getRecipes().first().id,2,System.currentTimeMillis()+90*60_000L)
    }
    private fun worker(id:String)=TestWorkerBuilder.from(app,PlannedBakeReminderWorker::class.java,Executor{it.run()})
        .setInputData(workDataOf(PlannedBakeReminderWorker.KEY_PLAN_ID to id)).build()

    @Test fun reminderPostsBothRealActionsButNeverStartsABake()=runBlocking {
        val plan=plan()
        BookPreferences(app).use{assertTrue(it.putPlan(plan))}
        val before=app.activeBakeRepository.all()
        assertEquals(ListenableWorker.Result.success(),worker(plan.id).doWork())
        assertEquals(before,app.activeBakeRepository.all())
        val posted=manager.activeNotifications.single{it.tag=="planned:${plan.id}"}.notification
        assertTrue(posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().startsWith("№1 Пора ставить опару"))
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,posted.visibility)
        assertEquals("Мадре",posted.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("Запланированная выпечка №1",posted.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Начать","Через 30 мин"),posted.actions.map{it.title.toString()})
        val body=shadowOf(posted.contentIntent).savedIntent
        assertEquals(PlannedBakeRequest(plan.id,PlannedBakeAction.OPEN),PlannedBakeActions.read(app,body))
        val start=shadowOf(posted.actions.first().actionIntent).savedIntent
        assertEquals(PlannedBakeRequest(plan.id,PlannedBakeAction.START),PlannedBakeActions.read(app,start))
        assertNull(PlannedBakeActions.read(app,start))
        assertNull(PlannedBakeActions.read(app,shadowOf(posted.actions.last().actionIntent).savedIntent))
        assertEquals(before,app.activeBakeRepository.all())
        // 7.0.16 backfills the number even when the reminder is the first entry after upgrade.
        // Every other field and both capability guards above must remain unchanged.
        BookPreferences(app).use{assertEquals(listOf(plan.copy(number=1)),it.plans)}
    }
    @Test fun plainExternalIntentsCannotStartOrSnoozeAndNeverConsumeTheRealCapability() {
        val plan=plan()
        BookPreferences(app).use { prefs ->
            assertTrue(prefs.putPlan(plan))
            val nonce=prefs.notificationNonce(plan.id)!!
            assertNull(PlannedBakeActions.read(app,PlannedBakeActions.intent(app,plan,PlannedBakeAction.START)))
            assertNull(PlannedBakeActions.read(app,PlannedBakeActions.intent(app,plan,PlannedBakeAction.SNOOZE,"wrong")))
            assertEquals(PlannedBakeRequest(plan.id,PlannedBakeAction.SNOOZE),
                PlannedBakeActions.read(app,PlannedBakeActions.intent(app,plan,PlannedBakeAction.SNOOZE,nonce)))
            assertNull(PlannedBakeActions.read(app,PlannedBakeActions.intent(app,plan,PlannedBakeAction.SNOOZE,nonce)))
        }
    }
    @Test fun scheduledWorkPersistsAndReplacingOnePlanDoesNotCancelAnother() {
        val first=plan();val second=first.copy(startAtMillis=first.startAtMillis+60_000L)
        val scheduler=PlannedBakeScheduler(app);val wm=WorkManager.getInstance(app)
        BookPreferences(app).use{assertTrue(it.putPlan(first));assertTrue(it.putPlan(second))}
        scheduler.schedule(first).result.get();scheduler.schedule(first).result.get();scheduler.schedule(second).result.get()
        assertEquals(1,wm.getWorkInfosForUniqueWork(PlannedBakeScheduler.uniqueName(first.id)).get().count{!it.state.isFinished})
        BookPreferences(app).use{assertTrue(it.removePlan(first.id))}
        scheduler.cancel(first.id)
        assertTrue(wm.getWorkInfosForUniqueWork(PlannedBakeScheduler.uniqueName(first.id)).get().all{it.state.isFinished})
        assertTrue(wm.getWorkInfosForUniqueWork(PlannedBakeScheduler.uniqueName(second.id)).get().any{it.state==WorkInfo.State.ENQUEUED})
        assertEquals(ListenableWorker.Result.success(),worker(first.id).doWork())
        assertTrue(manager.activeNotifications.none{it.tag=="planned:${first.id}"})
    }
    @Test fun startupReconcilesAPlanCommittedBeforeProcessDeathWithoutDuplicatingItsWork() {
        val plan=plan();val scheduler=PlannedBakeScheduler(app);val wm=WorkManager.getInstance(app)
        BookPreferences(app).use{assertTrue(it.putPlan(plan))}
        assertTrue(wm.getWorkInfosForUniqueWork(PlannedBakeScheduler.uniqueName(plan.id)).get().isEmpty())
        BookPreferences(app).use{scheduler.restoreMissing(it.plans)}
        val before=wm.getWorkInfosForUniqueWork(PlannedBakeScheduler.uniqueName(plan.id)).get().single{!it.state.isFinished}
        BookPreferences(app).use{scheduler.restoreMissing(it.plans)}
        val after=wm.getWorkInfosForUniqueWork(PlannedBakeScheduler.uniqueName(plan.id)).get().single{!it.state.isFinished}
        assertEquals(before.id,after.id)
    }
    @Test fun changingPortionsRevokesTheOldNotificationAndRejectsAnOutdatedWorkerSnapshot() {
        val plan=plan()
        BookPreferences(app).use { prefs ->
            assertTrue(prefs.putPlan(plan));val old=prefs.notificationNonce(plan.id,plan)!!
            val changed=plan.copy(portions=3);assertTrue(prefs.putPlan(changed,plan.id))
            assertFalse(prefs.consumeNotificationAction(plan.id,old))
            assertNull(prefs.notificationNonce(plan.id,plan))
            assertNotNull(prefs.notificationNonce(changed.id,changed))
        }
    }
    @Test fun coldPreferencesPreservePlansAndSnoozeOnlyReplacesItsOwnPlan() {
        val plan=plan();val other=plan.copy(startAtMillis=plan.startAtMillis+60_000L)
        val unrelated=app.getSharedPreferences("madre_prefs",0)
        unrelated.edit().putString("my_name","synthetic name to preserve").commit()
        BookPreferences(app).use{assertTrue(it.putPlan(plan));assertTrue(it.putPlan(other))}
        val now=System.currentTimeMillis();val postponed=BakePlanning.snooze(plan,now)
        assertEquals(now+30*60_000L,BakePlanning.reminderAt(postponed,now))
        BookPreferences(app).use{assertTrue(it.putPlan(postponed,plan.id))}
        BookPreferences(app).use{assertEquals(setOf(other,postponed),it.plans.toSet())}
        assertEquals("synthetic name to preserve",unrelated.getString("my_name",null))
    }
}
