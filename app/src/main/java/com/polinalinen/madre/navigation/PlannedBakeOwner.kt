package com.polinalinen.madre.navigation

import android.content.Context
import com.polinalinen.madre.notifications.PlannedBakeScheduler
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retained VM-owned transactions. Card, sheet and notification callers never own the write job. */
class PlannedBakeOwner(
    context:Context,
    private val scope:CoroutineScope,
    private val gate:Mutex,
    private val awaitReady:suspend ()->Unit,
    private val sessionForPlan:(String)->Long?,
    private val startBake:suspend (PlannedBake,Int)->Long?,
):AutoCloseable {
    private val preferences=BookPreferences(context.applicationContext)
    private val app=context.applicationContext as com.polinalinen.madre.MadreApplication
    private val scheduler=PlannedBakeScheduler(context.applicationContext)
    private val _busy=MutableStateFlow(false)
    val busy=_busy.asStateFlow()

    private suspend fun <T> transaction(markBusy:Boolean=true,block:suspend ()->T):T = scope.async {
        awaitReady()
        gate.withLock {
            if(markBusy)_busy.value=true
            try { block() } finally { if(markBusy)_busy.value=false }
        }
    }.await()

    suspend fun start(id:String,expected:PlannedBake?=null,portions:Int?=null):Long? = transaction {
        val current=preferences.currentPlan(id)
        val existing=sessionForPlan(id)
        if(existing!=null) {
            if(current!=null)consume(id,release=false)
            return@transaction existing
        }
        if(current==null || (expected!=null && current!=expected))return@transaction null
        val amount=portions?:current.portions
        require(amount in 1..5)
        val session=startBake(current,amount)?:return@transaction null
        // Activity cancellation cannot leave a committed bake's original plan available to SNOOZE.
        consume(current.id,release=false)
        session
    }

    suspend fun snooze(id:String,clock:Clock,expected:PlannedBake?=null):PlannedBake? = transaction {
        val current=preferences.currentPlan(id)?:return@transaction null
        if(sessionForPlan(id)!=null) {consume(id);return@transaction null}
        if(expected!=null && current!=expected)return@transaction null
        val moved=BakePlanning.snooze(current,clock.millis())
        store(moved,id,clock)
    }

    suspend fun save(plan:PlannedBake,clock:Clock,replacingId:String?=null,expected:PlannedBake?=null) = transaction {
        if(replacingId!=null) {
            val current=preferences.currentPlan(replacingId)
            check(current!=null && (expected==null || current==expected) && sessionForPlan(replacingId)==null) {
                "Plan already started or changed"
            }
        }
        check(sessionForPlan(plan.id)==null) { "Plan already started" }
        val prior=preferences.currentPlan(replacingId?:plan.id)
        val number=prior?.number?.takeIf{it in 1..9}
            ?: app.bakeNumbers.next(app.activeBakeRepository.all(),preferences.snapshotPlans())
            ?: throw IllegalStateException(com.polinalinen.madre.model.BakeNumbers.LIMIT_MESSAGE)
        store(plan.copy(number=number),replacingId,clock)
    }

    suspend fun remove(id:String) = transaction {consume(id)}

    suspend fun restore() = transaction(markBusy=false) {
        val plans=preferences.snapshotPlans()
        plans.filter{sessionForPlan(it.id)!=null}.forEach{consume(it.id,release=false)}
        withContext(NonCancellable+Dispatchers.IO) {
            scheduler.restoreMissing(preferences.snapshotPlans())
        }
    }

    private suspend fun store(plan:PlannedBake,replacingId:String?,clock:Clock) = withContext(NonCancellable+Dispatchers.IO) {
        val now=clock.millis()
        check(plan.startAtMillis>=now) { "Start time has passed" }
        check(preferences.putPlan(plan,replacingId)) { "Plan was not persisted" }
        if(replacingId!=null && replacingId!=plan.id)scheduler.cancel(replacingId).result.get()
        scheduler.schedule(plan,now).result.get()
        plan
    }

    private suspend fun consume(id:String,release:Boolean=true) = withContext(NonCancellable+Dispatchers.IO) {
        val number=preferences.currentPlan(id)?.number?:0
        check(preferences.removePlan(id)) { "Plan was not removed" }
        if(release)app.bakeNumbers.releasePlan(number)
        scheduler.cancel(id).result.get()
    }

    override fun close()=preferences.close()
}
