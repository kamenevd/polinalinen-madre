package com.polinalinen.madre.navigation

import android.content.Context
import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import com.polinalinen.madre.model.BakeNumbers

/** Caller owns Application.plannedBakeOperationGate; prefs locks never cross a suspend call.
 * Reservations keep an unwritten finale's number after its Room timer row is retired.
 */
class BakeNumberStore(context: Context) {
    private val prefs = context.getSharedPreferences("madre_prefs", Context.MODE_PRIVATE)
    data class Finale(val sessionId:Long,val recipeId:String,val recipeName:String,val portions:Int,val completedAt:Long,val number:Int,val recordId:Long?=null,val bakeKey:String?=null,val revision:Long=0,val recipeRevision:String?=null)
    fun finales():List<Finale> = prefs.all.mapNotNull { (key,value) ->
        if(!key.startsWith("bake_number_finale_"))null else runCatching { com.google.gson.Gson().fromJson(value as String,Finale::class.java) }.getOrNull()
    }
    fun strictFinales(): List<Finale> = prefs.all.filterKeys { it.startsWith("bake_number_finale_") }.map { (key, raw) ->
        val f = requireNotNull(com.google.gson.Gson().fromJson(raw as String, Finale::class.java))
        require(key == "bake_number_finale_${f.sessionId}" && f.recipeId.isNotBlank() && f.recipeName.isNotBlank() &&
            f.number in 1..9 && f.portions in 1..5 && f.completedAt > 0 && f.revision >= 0)
        f
    }
    fun rememberFinale(value:Finale) { check(prefs.edit().putString("bake_number_finale_${value.sessionId}",com.google.gson.Gson().toJson(value)).commit()) }
    fun forgetFinale(id:Long) { check(prefs.edit().remove("bake_number_finale_$id").commit()) }
    private fun held(): Map<Long, Int> = prefs.all.mapNotNull { (key, value) ->
        key.removePrefix("bake_number_session_").toLongOrNull()?.takeIf { key.startsWith("bake_number_session_") }
            ?.let { it to (value as? Int ?: 0) }
    }.toMap()
    fun number(id: Long): Int = prefs.getInt("bake_number_session_$id", 0)
    fun reserve(id: Long, number: Int) {
        check(prefs.edit().putInt("bake_number_session_$id", number).commit())
    }
    fun release(id: Long, now: Long = System.currentTimeMillis()) {
        val number = number(id)
        val edit = prefs.edit().remove("bake_number_session_$id").remove("bake_number_finale_$id")
        if (number in 1..9) edit.putLong("bake_number_freed_$number", now)
        check(edit.commit())
    }
    fun freed(): Map<Int, Long> = (1..9).associateWith { prefs.getLong("bake_number_freed_$it", Long.MIN_VALUE) }
    fun releasePlan(number: Int, now: Long = System.currentTimeMillis()) {
        if (number in 1..9) check(prefs.edit().putLong("bake_number_freed_$number", now).commit())
    }
    fun next(active: List<ActiveBakeEntity>, plans: List<PlannedBake>, now: Long = System.currentTimeMillis()): Int? {
        if((active.map{it.sessionId}+held().keys).distinct().size+plans.size>=9)return null
        return BakeNumbers.allocate(active.map { it.bakeNumber } + held().values + plans.map { it.number }, freed(), now)
    }

    /** Room backfill owns priority; pending finales next, then plans. Idempotent across process death. */
    fun reconcile(active: List<ActiveBakeEntity>, pendingFinales: Set<Long>, book: BookPreferences) {
        // Existing timers still restore; unreadable plans cannot authorize any backfill write.
        val plans=try { book.snapshotPlans() } catch (_:IllegalStateException) { return }
        val edit = prefs.edit()
        held().keys.filter { id -> active.none { it.sessionId == id } && id !in pendingFinales }
            .forEach { edit.remove("bake_number_session_$it") }
        active.forEach { edit.putInt("bake_number_session_${it.sessionId}", it.bakeNumber) }
        check(edit.commit())
        val used = active.map { it.bakeNumber }.filter { it in 1..9 }.toMutableSet()
        pendingFinales.sorted().filter { id -> active.none { it.sessionId == id } }.forEach { id ->
            val prior = number(id)
            val n = prior.takeIf { it in 1..9 && it !in used } ?: BakeNumbers.allocate(used, emptyMap(), 0) ?: 0
            reserve(id, n); used.add(n)
        }
        plans.sortedWith(compareBy<PlannedBake> { it.startAtMillis }.thenBy { it.id }).forEach { plan ->
            val transferred = active.firstOrNull { it.bakeKey == book.voicePlanIdentity(plan.id).first }
            val n = transferred?.bakeNumber ?: plan.number.takeIf { it in 1..9 && it !in used }
                ?: BakeNumbers.allocate(used, emptyMap(), 0) ?: 0
            if (plan.number != n) check(book.putPlan(plan.copy(number = n)))
            used.add(n)
        }
    }
    companion object {
        fun plannedBakeKey(id: String): String = java.util.UUID.nameUUIDFromBytes(("planned-bake:$id").toByteArray(Charsets.UTF_8)).toString()
    }
}
