package com.polinalinen.madre.navigation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson


/** Navigation-only state. Existing family/notes/Room keys are never replaced. */
data class BookBookmark(val recipeId: String, val page: Int, val section:String?=null, val step:Int?=null) {
    fun encode() = "$recipeId:$page"
    companion object {
        fun decode(value: String?): BookBookmark? {
            val id = value?.substringBeforeLast(':', "").orEmpty()
            val page = value?.substringAfterLast(':')?.toIntOrNull()
            return if (id.isNotBlank() && page != null && page >= 0) BookBookmark(id, page) else null
        }
    }
}

data class PlannedBake(val recipeId: String, val portions: Int, val startAtMillis: Long, val number: Int = 0) {
    val id: String get() = "$recipeId:$startAtMillis"
}

object BookStartup {
    const val RECENT_MILLIS = 12 * 60 * 60 * 1000L
    fun tab(saved: Int, lastVisit: Long, now: Long): Int =
        if (saved in 0..3 && now - lastVisit in 0 until RECENT_MILLIS) saved else 0
}

class BookPreferences(context: Context, namespace: String = "madre_prefs") : AutoCloseable {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val gson = Gson()
    var plansReadError by mutableStateOf<String?>(null)
        private set
    var bookmark by mutableStateOf(readBookmark())
        private set
    var plans by mutableStateOf(runCatching { readPlans() }.getOrDefault(emptyList()))
        private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bookmark" || key?.startsWith("bookmark_")==true) bookmark = readBookmark()
        if (key == "planned_bakes") runCatching { readPlans() }.onSuccess { plans = it }
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    fun initialTab(now: Long = System.currentTimeMillis()) = BookStartup.tab(
        prefs.getInt("last_tab", 0), prefs.getLong("last_visit", 0), now)
    fun visit(tab: Int, now: Long = System.currentTimeMillis()) {
        prefs.edit().putInt("last_tab", tab.coerceIn(0, 3)).putLong("last_visit", now).apply()
    }
    fun saveBookmark(value: BookBookmark) {
        bookmark = value
        // Keep the historical recipeId:page wire value for downgrade; details are optional and keyed to that exact position.
        prefs.edit().putString("bookmark", value.encode()).putString("bookmark_detail_key",value.encode())
            .putString("bookmark_section",value.section).putInt("bookmark_step",value.step?:-1).apply()
    }
    private fun readBookmark():BookBookmark? {
        val saved=BookBookmark.decode(prefs.getString("bookmark",null))?:return null
        return if(prefs.getString("bookmark_detail_key",null)==saved.encode())saved.copy(
            section=prefs.getString("bookmark_section",null),step=prefs.getInt("bookmark_step",-1).takeIf{it>=0}) else saved
    }
    fun portions(recipeId: String) = prefs.getInt("chapter_portions_$recipeId", 2).coerceIn(1, 5)
    fun savePortions(recipeId: String, value: Int) {
        prefs.edit().putInt("chapter_portions_$recipeId", value.coerceIn(1, 5)).apply()
    }
    /** Transactions read committed prefs, not an Activity listener's possibly queued presentation state. */
    fun currentPlan(id:String):PlannedBake? = synchronized(PLANS_LOCK){readPlans().firstOrNull{it.id==id}}
    fun snapshotPlans():List<PlannedBake> = synchronized(PLANS_LOCK){readPlans()}
    fun putPlan(plan: PlannedBake, replacingId: String? = null): Boolean = synchronized(PLANS_LOCK) {
        require(plan.portions in 1..5 && plan.startAtMillis > 0 && plan.recipeId.isNotBlank())
        val previous=runCatching { readPlans() }.getOrElse { return@synchronized false }
        val next = (previous.filterNot { it.id == plan.id || it.id == replacingId } + plan).sortedBy { it.startAtMillis }
        val edit=prefs.edit().putString("planned_bakes", gson.toJson(next))
        // Number backfill changes presentation only; existing local reminder capabilities stay valid.
        if(previous.any{it.id==plan.id && it.copy(number=plan.number)!=plan})edit.remove("planned_action_nonce_${plan.id}")
        if(replacingId!=null && replacingId!=plan.id)edit.remove("planned_action_nonce_$replacingId")
        if (!edit.commit()) return@synchronized false
        plans = next
        true
    }
    fun removePlan(id: String): Boolean = synchronized(PLANS_LOCK) {
        val previous=runCatching { readPlans() }.getOrElse { return@synchronized false }
        val next = previous.filterNot { it.id == id }
        if (!prefs.edit().putString("planned_bakes", gson.toJson(next)).remove("planned_action_nonce_$id").commit()) return@synchronized false
        plans = next
        true
    }
    /** Local notification capability, not an account/OAuth token. Never placed in WorkManager data. */
    fun notificationNonce(id: String, expected: PlannedBake? = null): String? = synchronized(PLANS_LOCK) {
        val current=runCatching { readPlans() }.getOrElse { return@synchronized null }.firstOrNull{it.id==id}?:return@synchronized null
        if(expected!=null && current!=expected)return@synchronized null
        prefs.getString("planned_action_nonce_$id",null)?.let{return@synchronized it}
        val nonce=java.util.UUID.randomUUID().toString()
        if(prefs.edit().putString("planned_action_nonce_$id",nonce).commit())nonce else null
    }
    fun consumeNotificationAction(id: String, nonce: String?): Boolean = synchronized(PLANS_LOCK) {
        if(nonce==null)return@synchronized false
        if(runCatching { readPlans() }.getOrElse { return@synchronized false }.none{it.id==id})return@synchronized false
        val stored=prefs.getString("planned_action_nonce_$id",null)?:return@synchronized false
        if(!java.security.MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8),nonce.toByteArray(Charsets.UTF_8)))return@synchronized false
        prefs.edit().remove("planned_action_nonce_$id").commit()
    }
    /** Concrete array needs no anonymous generic Signature under R8 full mode.
     * A failed read is NOT an empty collection and may never authorize a write.
     */
    private fun readPlans(): List<PlannedBake> = try {
        val decoded=requireNotNull(gson.fromJson(prefs.getString("planned_bakes", "[]"), Array<PlannedBake>::class.java))
        decoded.forEach { require(it.recipeId.isNotBlank() && it.portions in 1..5 && it.startAtMillis > 0 && it.number in 0..9) }
        require(decoded.map { it.id }.distinct().size == decoded.size)
        decoded.sortedBy { it.startAtMillis }.also { plansReadError=null }
    } catch (error:Exception) {
        plansReadError=PLANS_READ_ERROR
        throw IllegalStateException(PLANS_READ_ERROR,error)
    }
    override fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    companion object {
        private val PLANS_LOCK=Any()
        const val PLANS_READ_ERROR="Не удалось прочитать планы. Исходные данные сохранены без изменений."
    }
}
