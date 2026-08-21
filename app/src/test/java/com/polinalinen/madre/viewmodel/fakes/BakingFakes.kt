package com.polinalinen.madre.viewmodel.fakes

import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.data.repository.BakeHistory
import com.polinalinen.madre.model.Ingredient
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.model.TimelineStep
import com.polinalinen.madre.sync.ShelfSync
import com.polinalinen.madre.utils.BakeSessionLedger
import com.polinalinen.madre.viewmodel.BakingViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class RecordCall(
    val recipeId: String,
    val recipeName: String,
    val portions: Int,
    val completedAtMillis: Long,
)

class FakeBakeHistory : BakeHistory {
    private val records = linkedMapOf<Long, BakeRecordEntity>()
    private val stream = MutableStateFlow<List<BakeRecordEntity>>(emptyList())
    private var nextId = 1L

    val recordCalls = mutableListOf<RecordCall>()
    val attachCalls = mutableListOf<Pair<Long, String>>()
    val order = mutableListOf<String>()
    var onEvent: ((String) -> Unit)? = null

    var holdRecord: CompletableDeferred<Unit>? = null
    var throwOnAttach: Throwable? = null
    var throwOnRecord: Throwable? = null
    var throwOnGet: Throwable? = null

    override fun observeAll(): Flow<List<BakeRecordEntity>> = stream

    override suspend fun record(
        recipeId: String,
        recipeName: String,
        portions: Int,
        completedAtMillis: Long,
    ): Long {
        order += "record"
        onEvent?.invoke("record")
        recordCalls += RecordCall(recipeId, recipeName, portions, completedAtMillis)
        holdRecord?.await()
        throwOnRecord?.let { throw it }
        val id = nextId++
        records[id] = BakeRecordEntity(
            id = id,
            recipeId = recipeId,
            recipeName = recipeName,
            portions = portions,
            completedAtMillis = completedAtMillis,
        )
        stream.value = records.values.toList()
        return id
    }

    override suspend fun attachPhoto(recordId: Long, path: String) {
        order += "attach"
        onEvent?.invoke("attach")
        throwOnAttach?.let { throw it }
        attachCalls += recordId to path
        val current = records[recordId] ?: return
        records[recordId] = current.copy(photoPath = path)
        stream.value = records.values.toList()
    }

    override suspend fun getCompletedAt(recordId: Long): Long? {
        throwOnGet?.let { throw it }
        return records[recordId]?.completedAtMillis
    }

    override suspend fun get(recordId: Long): BakeRecordEntity? {
        throwOnGet?.let { throw it }
        return records[recordId]
    }

    fun put(record: BakeRecordEntity) {
        records[record.id] = record
        nextId = maxOf(nextId, record.id + 1)
        stream.value = records.values.toList()
    }
}

data class ShareCall(
    val recordId: Long,
    val recipeId: String,
    val recipeName: String,
    val portions: Int,
    val bakedAtMillis: Long,
    val displayName: String?,
    val familyName: String?,
    val photoPath: String?,
)

class FakeShelfSync : ShelfSync {
    val calls = mutableListOf<ShareCall>()
    val order = mutableListOf<String>()
    var throwOnShare: Throwable? = null
    var onEvent: ((String) -> Unit)? = null

    override fun shareBakeStat(
        recordId: Long,
        recipeId: String,
        recipeName: String,
        portions: Int,
        bakedAtMillis: Long,
        displayName: String?,
        familyName: String?,
        photoPath: String?,
    ) {
        order += "share"
        onEvent?.invoke("share")
        throwOnShare?.let { throw it }
        calls += ShareCall(
            recordId = recordId,
            recipeId = recipeId,
            recipeName = recipeName,
            portions = portions,
            bakedAtMillis = bakedAtMillis,
            displayName = displayName,
            familyName = familyName,
            photoPath = photoPath,
        )
    }
}

class FakeBakeSessionLedger : BakeSessionLedger {
    private val pointers = linkedMapOf<Long, Long>()
    var commitFails = false

    override fun recordIdFor(sessionId: Long): Long? = pointers[sessionId]

    override fun remember(sessionId: Long, recordId: Long): Boolean {
        if (commitFails) return false
        pointers[sessionId] = recordId
        return true
    }

    override fun forget(sessionId: Long) {
        pointers.remove(sessionId)
    }

    fun put(sessionId: Long, recordId: Long) {
        pointers[sessionId] = recordId
    }
}

fun sampleRecipe(id: String = "rye"): Recipe = Recipe(
    id = id,
    name = "Ржаной",
    emoji = "",
    description = "Тестовый рецепт",
    ingredients = mapOf("main" to listOf(Ingredient(name = "Мука", amount = 100.0, unit = "г", category = "flour"))),
    timeline = listOf(
        TimelineStep(
            type = StepType.ACTION,
            title = "Замес",
            description = "Смешайте тесто",
            durationMinutes = 1,
        ),
    ),
)

@Suppress("UNCHECKED_CAST")
fun seedActiveSession(
    vm: BakingViewModel,
    sessionId: Long = 1L,
    recipe: Recipe = sampleRecipe(),
    scaleFactor: Double = 1.0,
): Long {
    val sessionsField = BakingViewModel::class.java.getDeclaredField("_sessions").apply { isAccessible = true }
    val sessions = sessionsField.get(vm) as MutableStateFlow<List<BakingSession>>
    sessions.value = listOf(BakingSession.start(id = sessionId, recipe = recipe, scaleFactor = scaleFactor))

    val remainingField = BakingViewModel::class.java.getDeclaredField("_remainingSeconds").apply { isAccessible = true }
    val remaining = remainingField.get(vm) as MutableStateFlow<Map<Long, Long>>
    remaining.value = mapOf(sessionId to 0L)
    return sessionId
}
