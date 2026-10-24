package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import com.polinalinen.madre.data.repository.BakeHistory
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.BakingTick
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.model.BakeUndo
import com.polinalinen.madre.model.adjustTimer
import com.polinalinen.madre.notifications.BakingNotificationPlanner
import com.polinalinen.madre.notifications.BakeStepEndWorker
import com.polinalinen.madre.notifications.BootRestorePlanner
import com.polinalinen.madre.notifications.BakingProgress
import com.polinalinen.madre.notifications.BakingProgressService
import com.polinalinen.madre.notifications.MadreNotifier
import com.polinalinen.madre.notifications.NotificationLedger
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.shelf.ShelfSharePolicy
import com.polinalinen.madre.sync.ShelfSync
import com.polinalinen.madre.ui.components.CoffeeRing
import com.polinalinen.madre.utils.BakeSessionLedger
import com.polinalinen.madre.utils.PhotoStore
import com.polinalinen.madre.utils.PrefsBakeSessionLedger
import com.polinalinen.madre.data.repository.activeSnapshot
import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity
import com.polinalinen.madre.voice.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4 BakingViewModel — holds a LIST of independent baking sessions, not one.
 * A real kitchen can have bread proofing while pizza dough rests at the same
 * time (2026-07-21: Дима asked for this explicitly, HTML prototype already
 * reworked around it). Each session owns its own timer Job — finishing or
 * leaving one must never touch another's countdown.
 *
 * Закрывает баг v3 #7 (job leak): каждый Job из [timerJobs] отменяется явно
 * по id (advanceStep/exitSession) и все разом в onCleared — ни один не
 * остаётся висеть в фоне.
 */
class BakingViewModel @JvmOverloads constructor(
    app: Application,
    private val bakeHistory: BakeHistory = (app as MadreApplication).bakeHistoryRepository,
    private val shelfSync: ShelfSync = (app as MadreApplication).syncRepository,
    private val sessionLedger: BakeSessionLedger = PrefsBakeSessionLedger(
        app.getSharedPreferences("madre_prefs", android.content.Context.MODE_PRIVATE),
    ),
) : AndroidViewModel(app), VoiceController {

    private val madreApp = app as MadreApplication
    private val recipeRepository = madreApp.recipeRepository
    private val activeBakeRepository = madreApp.activeBakeRepository
    private val numberStore = madreApp.bakeNumbers
    private val transitionGate = Mutex()
    private val plannedBakesOwner=lazy {
        com.polinalinen.madre.navigation.PlannedBakeOwner(madreApp,viewModelScope,madreApp.plannedBakeOperationGate,
            awaitReady={sessionIdsReady.await()},sessionForPlan={sessionForPlan(it)?.id},startBake={plan,portions->
                _recipes.value.firstOrNull{it.id==plan.recipeId}?.let{startPlannedBakeUnderGate(it,portions.toDouble(),plan.id)}
            })
    }
    val plannedBakes get()=plannedBakesOwner.value
    @Volatile private var controllerLive = true
    private val _mutationError = MutableStateFlow<String?>(null)
    val mutationError: StateFlow<String?> = _mutationError.asStateFlow()
    private val _restoreReady = MutableStateFlow(false)
    val restoreReady: StateFlow<Boolean> = _restoreReady.asStateFlow()
    private val _undo = MutableStateFlow<BakeUndo?>(null)
    val undo: StateFlow<BakeUndo?> = _undo.asStateFlow()
    private var undoExpiry: Job? = null
    private val _pendingAbandons = MutableStateFlow<Map<Long, BakeUndo>>(emptyMap())
    val pendingAbandons: StateFlow<Map<Long, BakeUndo>> = _pendingAbandons.asStateFlow()
    private val abandonJobs = mutableMapOf<Long, Job>()

    private val sourdoughRepository = madreApp.sourdoughRepository

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _sessions = MutableStateFlow<List<BakingSession>>(emptyList())
    val sessions: StateFlow<List<BakingSession>> = _sessions.asStateFlow()

    /** Секунд до конца текущего шага — отдельное число на каждую активную сессию. */
    private val _remainingSeconds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val remainingSeconds: StateFlow<Map<Long, Long>> = _remainingSeconds.asStateFlow()

    // Cycle 16: срез той же карты по одной сессии. Экран, читающий карту целиком,
    // перерисовывался раз в секунду весь — вместе со списком рецептов на первой
    // полосе. Здесь поток отдаёт одно число и гасит повторы, так что подписчик
    // просыпается ровно тогда, когда меняется ЕГО секунда.
    private val remainingFlows = mutableMapOf<Long, StateFlow<Long>>()

    /**
     * Остаток текущего шага одной выпечки. Экземпляр потока на сессию один:
     * два виджета на странице таймера (цифры и записка про масло) подписываются
     * на него же, а не заводят по своему `map` на каждую рекомпозицию.
     */
    fun remainingFor(sessionId: Long): StateFlow<Long> = remainingFlows.getOrPut(sessionId) {
        _remainingSeconds
            .map { it[sessionId] ?: 0L }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                _remainingSeconds.value[sessionId] ?: 0L,
            )
    }

    // Cycle 18: nearestSessionId убран вместе с нажатием на ляссе. Он отвечал
    // на единственный вопрос — «к какой выпечке ведёт лента», — а лента больше
    // никуда не ведёт: к своей выпечке ведёт её талон. Заодно ушёл combine,
    // просыпавшийся на каждый тик остатка.
    // Сколько выпечек бросили незавершёнными в этой сессии приложения — источник
    // триггера «отменённая выпечка» для клякс (DESIGN-V4.md Cycle 3, InkBlot).
    // В памяти, не в Room: фича явно не требует новой таблицы, а клякса как
    // «след сегодняшней спешки» и не должна пережидать перезапуск приложения.
    private val _cancelledCount = MutableStateFlow(0)
    val cancelledCount: StateFlow<Int> = _cancelledCount.asStateFlow()

    // Сколько раз испечён каждый рецепт — питает «затёртость» страниц и строк
    // оглавления (DESIGN-V4.md Cycle 4, WornPages). Ноль новых таблиц: это
    // просто иной разрез той же bake_records, что кормит формуляр.
    val bakeCounts: StateFlow<Map<String, Int>> =
        bakeHistory.observeAll()
            .map { records -> records.groupingBy { it.recipeId }.eachCount() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // «Старое фото» (Cycle 6, AgedPhoto): вклеенные фотокарточки этой сессии
    // приложения — Complete-экран показывает свежее фото сразу после выбора.
    private val _bakePhotoPaths = MutableStateFlow<Map<Long, String>>(emptyMap())
    val bakePhotoPaths: StateFlow<Map<Long, String>> = _bakePhotoPaths.asStateFlow()

    class BakeCompletion(
        val recipeId: String,
        val recipeName: String,
        val portions: Int,
        val completedAtMillis: Long,
        val bakeNumber: Int = 0,
    ) {
        val recordId = CompletableDeferred<Long>()
        private val recordStarted = AtomicBoolean(false)

        fun reserveRecordInsert(): Boolean = recordStarted.compareAndSet(false, true)
    }

    private val _completions = MutableStateFlow<Map<Long, BakeCompletion>>(emptyMap())
    val completions: StateFlow<Map<Long, BakeCompletion>> = _completions.asStateFlow()

    private val _finishing = MutableStateFlow<Set<Long>>(emptySet())
    val finishing: StateFlow<Set<Long>> = _finishing.asStateFlow()

    private companion object {
        const val RECORD_WAIT_MS = 5_000L
    }

    /**
     * Cycle 17: есть ли куда делиться. Без аккаунта общей книги не существует,
     * и кнопка «Поделиться статистикой» до сих пор всё равно нажималась — с
     * ответом «статистика отправлена в общую книгу», которого не случалось ни
     * разу. Кнопки при false просто нет: неактивная кнопка на этом экране
     * объясняла бы себя дольше, чем строка в «Выходных данных», куда за
     * аккаунтом и ходят.
     */
    private val _sharingAvailable = MutableStateFlow(false)
    val sharingAvailable: StateFlow<Boolean> = _sharingAvailable.asStateFlow()

    private val timerJobs = mutableMapOf<Long, Job>()
    private var nextSessionId = 1L
    /** Cycle 17: startBaking ждёт посева id и restore из active_bakes. */
    private val sessionIdsReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    // Cycle 11: уведомления по ходу выпечки. Журнал и нотификатор — поля этой
    // ViewModel, а не статические синглтоны: живут ровно столько, сколько живут
    // сами сессии выпечки, и не протекают между тестами.
    private val notifier = MadreNotifier(app)
    private val notificationLedger = NotificationLedger()

    /**
     * Cycle 19: как зовут закваску — для шапки карточки в шторке.
     *
     * Берётся из тех же sourdough_configs, что читает колофон, а не заводится
     * второй строкой в коде: имя у книги одно, и «Мадре · Бородинский» в шторке
     * у человека, назвавшего закваску Соней, было бы неправдой. Простое поле, а
     * не поток: читатель у него один — сборка слепка, — и та читает его в тот
     * же момент, когда собирает всё остальное.
     */
    private var starterName: String = StarterName.DEFAULT

    init {
        viewModelScope.launch {
            val config = sourdoughRepository.getOrCreateDefaultConfig()
            sourdoughRepository.observeConfig(config.userId).collect { latest ->
                val renamed = StarterName.sanitize(latest?.name.orEmpty())
                if (renamed != starterName) {
                    starterName = renamed
                    // Переименовали — шапка в шторке меняется сразу, а не со
                    // следующим тиком: на паузе следующего тика может и не быть.
                    publishProgress()
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val recipes = recipeRepository.getRecipes()
            _recipes.value = recipes
            // Cycle 15/17: посеять nextSessionId И восстановить UI-сессии из
            // active_bakes (policy A) до того, как startBaking выдаст новый id.
            val pendingFinales=sessionLedger.pendingSessionIds()+numberStore.finales().map{it.sessionId}
            val saved = madreApp.plannedBakeOperationGate.withLock {
                val rows=activeBakeRepository.initializeLegacyKeys()
                com.polinalinen.madre.navigation.BookPreferences(madreApp).use { book -> numberStore.reconcile(rows,pendingFinales.toSet(),book) }
                rows
            }
            nextSessionId = maxOf(nextSessionId, saved.maxOfOrNull { it.sessionId + 1 } ?: 1L,
                pendingFinales.maxOrNull()?.plus(1L) ?: 1L)
            val byId = recipes.associateBy { it.id }
            val nowElapsed = BakingClock.elapsed()
            val nowWall = BakingClock.wallClock()
            val restored = saved.mapNotNull { row ->
                val recipe = byId[row.recipeId] ?: return@mapNotNull null
                val restoredRecipe = if(row.recipeRevision!=null && row.recipeRevision==recipeRepository.revision &&
                    row.stepIndex in recipe.timeline.indices && row.stepDurationMinutes>=0 && recipe.timeline[row.stepIndex].title==row.stepTitle)
                    recipe.copy(timeline=recipe.timeline.mapIndexed { index,step->if(index==row.stepIndex)step.copy(durationMinutes=row.stepDurationMinutes) else step })
                    else recipe
                BakingSession.restoreFromActive(
                    sessionId = row.sessionId,
                    recipe = restoredRecipe,
                    stepIndex = row.stepIndex,
                    startedAtWallClock = row.startedAtWallClock,
                    pausedAtWallClock = row.pausedAtWallClock,
                    nowElapsed = nowElapsed,
                    nowWallClock = nowWall,
                    scaleFactor = row.scaleFactor,
                    bakeKey = requireNotNull(row.bakeKey),
                    revision = row.revision,
                    recipeRevision = row.recipeRevision,
                    bakeNumber = row.bakeNumber,
                )
            }
            if (restored.isNotEmpty()) {
                _sessions.value = restored
                restored.forEach { s ->
                    val rem = s.remainingSeconds(nowElapsed)
                    _remainingSeconds.update { it + (s.id to rem) }
                    if (!s.isCompleted) restartTimer(s.id)
                }
                publishProgress()
                if (restored.any { !it.isCompleted }) {
                    BakingProgressService.start(getApplication())
                }
            }
            // Back leaves the final photo/share choice undecided. Restore its existing record, never insert another.
            pendingFinales.forEach { restoreCompletionNow(it) }
            recoverCancellationEffects()
            sessionIdsReady.complete(Unit)
            _restoreReady.value = true
            madreApp.voiceCoordinator.attach(this@BakingViewModel)
        }
        // Токен под Keystore — диск, читаем на IO.
        viewModelScope.launch {
            _sharingAvailable.value = withContext(Dispatchers.IO) {
                !madreApp.authTokenStore.read().isNullOrBlank()
            }
        }
    }

    override fun voiceSessions(): List<BakingSession> = _sessions.value
    override fun voiceRecipeRevision(): String = recipeRepository.revision.orEmpty()
    override suspend fun applyVoice(command: VoiceCommand, receivedElapsed: Long, authority: () -> VoiceAuthority) =
        applyVoiceCommand(command, receivedElapsed, authority)

    fun session(id: Long): BakingSession? = _sessions.value.find { it.id == id }

    /** A timer is opened only after the snapshot is durably created. */
    suspend fun startBaking(recipe: Recipe, scaleFactor: Double): Long? = numberedStart(recipe,scaleFactor,false)
    suspend fun startBakingWithUndo(recipe: Recipe, scaleFactor: Double): Long? = numberedStart(recipe,scaleFactor,true)
    private suspend fun numberedStart(recipe: Recipe, scaleFactor: Double, undo: Boolean): Long? {
        sessionIdsReady.await()
        return madreApp.plannedBakeOperationGate.withLock { startBakingInternal(recipe,scaleFactor,undo) }
    }
    suspend fun previewBakeNumber(): Int? {
        sessionIdsReady.await()
        return madreApp.plannedBakeOperationGate.withLock {
            com.polinalinen.madre.navigation.BookPreferences(madreApp).use { book -> numberStore.next(activeBakeRepository.all(),book.snapshotPlans()) }
        }
    }
    /** Durable primitive. All production card/sheet/notification entries use plannedBakes transactions. */
    internal suspend fun startPlannedBake(recipe: Recipe, scaleFactor: Double, planId: String): Long? {
        sessionIdsReady.await()
        return madreApp.plannedBakeOperationGate.withLock { startPlannedBakeUnderGate(recipe,scaleFactor,planId) }
    }
    private suspend fun startPlannedBakeUnderGate(recipe: Recipe, scaleFactor: Double, planId: String): Long? = viewModelScope.async {
        startBakingInternal(recipe,scaleFactor,true,plannedBakeKey(planId),planId)
    }.await()
    fun sessionForPlan(planId: String): BakingSession? = _sessions.value.firstOrNull{it.bakeKey==plannedBakeKey(planId)}
    private fun plannedBakeKey(planId: String)=java.util.UUID.nameUUIDFromBytes(("planned-bake:$planId").toByteArray(Charsets.UTF_8)).toString()

    private suspend fun startBakingInternal(recipe: Recipe, scaleFactor: Double, withUndo: Boolean, dedupeKey: String? = null, planId: String? = null): Long? {
        sessionIdsReady.await()
        return transitionGate.withLock {
            if(dedupeKey!=null)_sessions.value.firstOrNull{it.bakeKey==dedupeKey}?.let{return@withLock it.id}
            try {
                require(scaleFactor in 1.0..5.0 && scaleFactor == scaleFactor.toInt().toDouble())
                check(controllerLive)
                val durable=activeBakeRepository.all()
                if(dedupeKey!=null)durable.firstOrNull{it.bakeKey==dedupeKey}?.let { row ->
                    val restored=BakingSession.restoreFromActive(row.sessionId,recipe,row.stepIndex,row.startedAtWallClock,row.pausedAtWallClock,
                        BakingClock.elapsed(),BakingClock.wallClock(),row.scaleFactor,requireNotNull(row.bakeKey),row.revision,row.recipeRevision,row.bakeNumber)
                    _sessions.update{list->list.filterNot{it.id==row.sessionId}+restored}
                    numberStore.reserve(row.sessionId,row.bakeNumber)
                    restartTimer(row.sessionId);publishProgress()
                    return@withLock row.sessionId
                }
                val number=com.polinalinen.madre.navigation.BookPreferences(madreApp).use { book ->
                    planId?.let{book.currentPlan(it)?.number?.takeIf{n->n in 1..9}}
                        ?: numberStore.next(durable,book.snapshotPlans())
                }
                if(number==null) { _mutationError.value=com.polinalinen.madre.model.BakeNumbers.LIMIT_MESSAGE;return@withLock null }
                val id = maxOf(nextSessionId,durable.maxOfOrNull{it.sessionId+1}?:1L)
                nextSessionId=id+1
                val started=BakingSession.start(id,recipe,scaleFactor)
                val next=started.copy(recipeRevision=recipeRepository.revision,bakeKey=dedupeKey?:started.bakeKey,bakeNumber=number)
                activeBakeRepository.commit(id, null, next.activeSnapshot(), valid = { controllerLive })
                numberStore.reserve(id,number)
                sessionLedger.forget(id)
                _completions.update { it - id }
                _finishing.update { it - id }
                _sessions.update { it + next }
                scheduleStepDeadline(next.activeSnapshot())
                restartTimer(id)
                publishProgress()
                BakingProgressService.start(getApplication())
                if (withUndo) offerUndo(next, next.revision, BakeUndo.Kind.START, 5000, "Начали «${recipe.name}» · ×${next.trustedPortions}")
                _mutationError.value = null
                id
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _mutationError.value = "Не удалось сохранить выпечку. Попробуй ещё раз."; null }
        }
    }

    fun advanceStep(id: Long, onCompleted: () -> Unit = {}) = advanceStepInternal(id, onCompleted, false)
    fun advanceStepWithUndo(id: Long, onCompleted: () -> Unit = {}) = advanceStepInternal(id, onCompleted, true)
    private fun advanceStepInternal(id: Long, onCompleted: () -> Unit, undoable: Boolean) = launchTransition {
        val old = session(id) ?: return@launchTransition
        if (old.isCompleted) return@launchTransition
        if (old.isLastStep && old.trustedPortions == null) {
            _mutationError.value = "Сначала подтверди порции этой выпечки."
            return@launchTransition
        }
        val beforeClock = BakingClock.elapsed()
        val beforeRemaining = (old.stepEndsAtElapsed(beforeClock)-beforeClock).coerceAtLeast(0)
        val next = old.advance(beforeClock, BakingClock.wallClock()).copy(revision = old.revision + 1)
        persistTransition(old, next)
        if (next.isCompleted) {
            val completion = reserveCompletion(id, next.recipe.id, next.recipe.name,
                requireNotNull(next.trustedPortions), requireNotNull(next.completedAt))
            startRecordInsert(id, completion)
            onCompleted()
        } else if (undoable) offerUndo(old, next.revision, BakeUndo.Kind.ADVANCE, 6000,
            "Шаг ${next.currentStepIndex + 1} · ${next.currentStep.title}", capturedRemainingMillis=beforeRemaining)
    }

    private fun offerUndo(before: BakingSession, expectedRevision: Long, kind: BakeUndo.Kind, millis: Long, message: String, capturedRemainingMillis: Long? = null) {
        undoExpiry?.cancel()
        val now = BakingClock.elapsed()
        val token = BakeUndo(java.util.UUID.randomUUID().toString(), before, expectedRevision, now + millis, kind,
            capturedRemainingMillis ?: (before.stepEndsAtElapsed(now) - now).coerceAtLeast(0), message)
        _undo.value = token
        undoExpiry = viewModelScope.launch {
            delay(millis)
            if (_undo.value?.token == token.token) _undo.value = null
        }
    }

    fun undo(token: String) = launchTransition {
        val pending = _undo.value?.takeIf { it.token == token }
            ?: _pendingAbandons.value.values.firstOrNull { it.token == token } ?: return@launchTransition
        val current = session(pending.sessionId)
        if (!pending.valid(current, BakingClock.elapsed())) {
            if(_undo.value?.token==pending.token)_undo.value = null
            return@launchTransition
        }
        val old = requireNotNull(current)
        when (pending.kind) {
            BakeUndo.Kind.START -> { persistTransition(old, null); cleanExitedSession(old.id) }
            BakeUndo.Kind.ADVANCE -> persistTransition(old, pending.restoredStep(old, BakingClock.elapsed(), BakingClock.wallClock()))
            BakeUndo.Kind.ABANDON -> forgetAbandon(old.id)
        }
        if(_undo.value?.token==pending.token)_undo.value = null
    }

    /** Keep Room/timer/lease untouched for eight seconds. A killed process safely keeps the bake. */
    fun abandonWithUndo(id: Long) = launchTransition {
        val old=session(id)?:return@launchTransition
        if(old.isCompleted || _finishing.value.contains(id) || _pendingAbandons.value.containsKey(id))return@launchTransition
        val now=BakingClock.elapsed()
        val token=BakeUndo(java.util.UUID.randomUUID().toString(),old,old.revision,now+8000,
            BakeUndo.Kind.ABANDON,(old.stepEndsAtElapsed(now)-now).coerceAtLeast(0),"${com.polinalinen.madre.model.BakeNumbers.label(old.bakeNumber)} ${old.recipe.name} выброшена")
        _pendingAbandons.update { it+(id to token) }
        undoExpiry?.cancel();_undo.value=token
        abandonJobs[id]=viewModelScope.launch {
            delay(8000)
            launchTransition {
                val pending=_pendingAbandons.value[id]?:return@launchTransition
                val current=session(id)
                if(pending.token==token.token && pending.matches(current) && !_finishing.value.contains(id))
                    cancelSessionNow(requireNotNull(current))
                else forgetAbandon(id)
            }.join()
        }
    }

    private fun forgetAbandon(id: Long) {
        val token=_pendingAbandons.value[id]
        abandonJobs.remove(id)?.cancel()
        _pendingAbandons.update { it-id }
        if(token!=null && _undo.value?.token==token.token)_undo.value=null
    }

    /** Await real Room/ledger restoration before interpreting a notification as a missing bake. */
    suspend fun prepareBakeDestination(sessionId: Long): Boolean {
        sessionIdsReady.await()
        return madreApp.plannedBakeOperationGate.withLock { transitionGate.withLock {
            _pendingAbandons.value[sessionId]?.let { pending ->
                val current=session(sessionId)
                if(pending.valid(current,BakingClock.elapsed()))forgetAbandon(sessionId)
                else if(pending.matches(current))cancelSessionNow(requireNotNull(current))
            }
            session(sessionId) != null || ensureCompletion(sessionId) != null
        } }
    }

    fun adjustTime(id: Long, minutes: Int) = launchTransition {
        val old=session(id)?:return@launchTransition
        if(old.isCompleted || _finishing.value.contains(id))return@launchTransition
        persistTransition(old,old.adjustTimer(minutes,BakingClock.elapsed(),BakingClock.wallClock()),resetNotifications=true)
    }

    private fun launchTransition(block: suspend () -> Unit) = viewModelScope.launch {
        sessionIdsReady.await()
        madreApp.plannedBakeOperationGate.withLock { transitionGate.withLock {
            try { check(controllerLive); block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _mutationError.value = "Изменение не сохранено. Проверь выпечку на телефоне." }
        } }
    }

    /** All local mutations and voice use this same durable-before-publish path. Caller owns transitionGate. */
    private suspend fun persistTransition(old: BakingSession, next: BakingSession?, resetNotifications: Boolean = false) {
        if(next?.isCompleted==true)numberStore.rememberFinale(com.polinalinen.madre.navigation.BakeNumberStore.Finale(
            old.id,old.recipe.id,old.recipe.name,requireNotNull(old.trustedPortions),requireNotNull(next.completedAt),old.bakeNumber))
        try { activeBakeRepository.commit(old.id, if (old.isCompleted) null else old.activeSnapshot(),
            next?.takeUnless { it.isCompleted }?.activeSnapshot(),
            preserveUnknownStep = !old.restoredStepValid, valid = { controllerLive }) }
        catch(e:Exception) { if(next?.isCompleted==true)numberStore.forgetFinale(old.id);throw e }
        if(next==null)numberStore.release(old.id)
        if(resetNotifications)notificationLedger.forgetSession(old.id).forEach { notifier.cancelByKey(it) }
        publishTransition(old, next)
        _mutationError.value = null
    }

    private fun publishTransition(old: BakingSession, next: BakingSession?) {
        forgetAbandon(old.id)
        if (_undo.value?.sessionId == old.id) { undoExpiry?.cancel(); _undo.value = null }
        _sessions.update { list -> list.mapNotNull { if (it.id == old.id) next else it } }
        if (next == null || next.isCompleted) {
            stopTimer(old.id)
            WorkManager.getInstance(getApplication()).cancelUniqueWork(BootRestorePlanner.uniqueWorkName(old.id))
        } else {
            scheduleStepDeadline(next.activeSnapshot())
            restartTimer(old.id)
        }
        publishProgress()
    }

    /**
     * Cycle 17: срок шага в WorkManager на КАЖДОМ переходе, не только после
     * BOOT. Иначе смерть процесса без ребута съедает «время вышло».
     */
    private fun scheduleStepDeadline(bake: com.polinalinen.madre.data.db.entities.ActiveBakeEntity) {
        val decision = BootRestorePlanner.decide(bake, System.currentTimeMillis())
        val wm = WorkManager.getInstance(getApplication())
        when (decision) {
            is BootRestorePlanner.Decision.Forget -> Unit
            is BootRestorePlanner.Decision.Keep -> {
                wm.cancelUniqueWork(BootRestorePlanner.uniqueWorkName(bake.sessionId))
            }
            is BootRestorePlanner.Decision.Notify -> {
                val request = OneTimeWorkRequestBuilder<BakeStepEndWorker>()
                    .setInitialDelay(decision.delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setInputData(
                        workDataOf(
                            BakeStepEndWorker.KEY_SESSION_ID to bake.sessionId,
                            BakeStepEndWorker.KEY_STEP_INDEX to bake.stepIndex,
                            BakeStepEndWorker.KEY_RECIPE_NAME to bake.recipeName,
                            BakeStepEndWorker.KEY_STEP_TITLE to bake.stepTitle,
                            BakeStepEndWorker.KEY_BAKE_NUMBER to bake.bakeNumber,
                        )
                    )
                    .build()
                wm.enqueueUniqueWork(
                    BootRestorePlanner.uniqueWorkName(bake.sessionId),
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }

    /**
     * Отложенный кадр «Испечено»: только в памяти до finish(), без записи в Room.
     * При замене прошлый файл чистится, если ни одна сессия больше на него не смотрит.
     */
    fun stageBakePhoto(sessionId: Long, path: String) {
        if (_finishing.value.contains(sessionId)) return
        if (path.isBlank()) return
        val previous = _bakePhotoPaths.value[sessionId]
        _bakePhotoPaths.update { it + (sessionId to path) }
        if (!previous.isNullOrBlank() && previous != path) {
            val referenced = _bakePhotoPaths.value.values.toSet()
            viewModelScope.launch(Dispatchers.IO) {
                PhotoStore.deleteIfUnreferenced(getApplication(), previous) { candidate ->
                    candidate in referenced
                }
            }
        }
    }

    /**
     * Снять отложенный кадр до finish(): только память и файл. Room/сеть не трогаем.
     */
    fun unstageBakePhoto(sessionId: Long) {
        if (_finishing.value.contains(sessionId)) return
        val previous = _bakePhotoPaths.value[sessionId] ?: return
        _bakePhotoPaths.update { it - sessionId }
        val referenced = _bakePhotoPaths.value.values.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            PhotoStore.deleteIfUnreferenced(getApplication(), previous) { candidate ->
                candidate in referenced
            }
        }
    }

    /**
     * Завершение «Испечено»: idempotent guard, запись фото в личный формуляр и
     * (по решению) постановка факта на полку.
     */
    fun finish(sessionId: Long, decision: ShelfShareDecision, onExit: () -> Unit = {}): Job? {
        if (session(sessionId)?.trustedPortions == null && session(sessionId) != null) return null
        val stagedPhoto = _bakePhotoPaths.value[sessionId]
        // P10: обещали поставить с кадром, а кадра нет — finish не стартует.
        if (ShelfSharePolicy.wantsPhoto(decision) && stagedPhoto.isNullOrBlank()) return null
        if (!markFinishing(sessionId)) return null
        val frozenPhoto = stagedPhoto

        return viewModelScope.launch {
            val completion = transitionGate.withLock { ensureCompletion(sessionId) }
            var retiredRestoredCompletion = false
            try {
                val recordId = completion?.let { withTimeoutOrNull(RECORD_WAIT_MS) { it.recordId.await() } }
                if (recordId != null && completion != null) {
                    retiredRestoredCompletion = true
                    if (!frozenPhoto.isNullOrBlank()) {
                        runCatching { bakeHistory.attachPhoto(recordId, frozenPhoto) }
                    }
                    if (ShelfSharePolicy.shouldEnqueue(decision)) {
                        val account = madreApp.familyAccountRepository.currentAccount()
                        runCatching {
                            shelfSync.shareBakeStat(
                                recordId = recordId,
                                recipeId = completion.recipeId,
                                recipeName = completion.recipeName,
                                portions = completion.portions,
                                bakedAtMillis = completion.completedAtMillis,
                                displayName = account?.displayName,
                                familyName = account?.familyName,
                                photoPath = if (ShelfSharePolicy.wantsPhoto(decision)) frozenPhoto else null,
                            )
                        }
                    }
                }
            } finally {
                if (session(sessionId) != null) {
                    madreApp.plannedBakeOperationGate.withLock { transitionGate.withLock { exitSessionNow(sessionId) } }
                } else if(retiredRestoredCompletion) {
                    madreApp.plannedBakeOperationGate.withLock { transitionGate.withLock { numberStore.release(sessionId);cleanExitedSession(sessionId) } }
                }
                onExit()
            }
        }
    }

    fun restoreCompletion(sessionId: Long?) {
        if (sessionId == null) return
        viewModelScope.launch { restoreCompletionNow(sessionId) }
    }

    private suspend fun ensureCompletion(sessionId: Long): BakeCompletion? {
        _completions.value[sessionId]?.let { return it }
        val activeSession = session(sessionId)
        if (activeSession != null && activeSession.isCompleted) {
            val completion = reserveCompletion(
                sessionId = sessionId,
                recipeId = activeSession.recipe.id,
                recipeName = activeSession.recipe.name,
                portions = activeSession.trustedPortions ?: return null,
                completedAtMillis = activeSession.completedAt ?: BakingClock.wallClock(),
            )
            startRecordInsert(sessionId, completion)
            return completion
        }
        return restoreCompletionNow(sessionId)
    }

    private suspend fun restoreCompletionNow(sessionId: Long): BakeCompletion? {
        _completions.value[sessionId]?.let { return it }
        val saved=numberStore.finales().firstOrNull{it.sessionId==sessionId}
        val recordId = sessionLedger.recordIdFor(sessionId) ?: saved?.recordId
        if(recordId==null) {
            if(saved==null || session(sessionId)?.isCompleted==false)return null
            val pending=reserveCompletion(sessionId,saved.recipeId,saved.recipeName,saved.portions,saved.completedAt)
            startRecordInsert(sessionId,pending)
            return pending
        }
        val record = bakeHistory.get(recordId) ?: return null
        val candidate = BakeCompletion(
            recipeId = record.recipeId,
            recipeName = record.recipeName,
            portions = record.portions,
            completedAtMillis = record.completedAtMillis,
            bakeNumber = numberStore.number(sessionId),
        )
        candidate.recordId.complete(recordId)
        val completion = putCompletionIfAbsent(sessionId, candidate)
        if (!completion.recordId.isCompleted) {
            completion.recordId.complete(recordId)
        }
        return completion
    }

    private fun reserveCompletion(
        sessionId: Long,
        recipeId: String,
        recipeName: String,
        portions: Int,
        completedAtMillis: Long,
    ): BakeCompletion {
        while (true) {
            val current = _completions.value
            current[sessionId]?.let { return it }
            val candidate = BakeCompletion(
                recipeId = recipeId,
                recipeName = recipeName,
                portions = portions,
                completedAtMillis = completedAtMillis,
                bakeNumber = numberStore.number(sessionId),
            )
            val next = current + (sessionId to candidate)
            if (_completions.compareAndSet(current, next)) return candidate
        }
    }

    private fun putCompletionIfAbsent(sessionId: Long, candidate: BakeCompletion): BakeCompletion {
        while (true) {
            val current = _completions.value
            current[sessionId]?.let { return it }
            val next = current + (sessionId to candidate)
            if (_completions.compareAndSet(current, next)) return candidate
        }
    }

    private fun startRecordInsert(sessionId: Long, completion: BakeCompletion) {
        if (!completion.reserveRecordInsert()) return
        viewModelScope.launch {
            val recordId = runCatching {
                bakeHistory.record(
                    recipeId = completion.recipeId,
                    recipeName = completion.recipeName,
                    portions = completion.portions,
                    completedAtMillis = completion.completedAtMillis,
                )
            }.getOrNull() ?: return@launch
            numberStore.finales().firstOrNull{it.sessionId==sessionId}?.let{numberStore.rememberFinale(it.copy(recordId=recordId))}
            if (sessionLedger.remember(sessionId, recordId) && !completion.recordId.isCompleted) {
                completion.recordId.complete(recordId)
            }
        }
    }

    private fun markFinishing(sessionId: Long): Boolean {
        while (true) {
            val current = _finishing.value
            if (current.contains(sessionId)) return false
            val next = current + sessionId
            if (_finishing.compareAndSet(current, next)) return true
        }
    }

    fun confirmPortions(id: Long, portions: Int) = launchTransition {
        val old = session(id) ?: return@launchTransition
        if (portions !in 1..5 || old.trustedPortions != null || _finishing.value.contains(id)) return@launchTransition
        persistTransition(old, old.copy(scaleFactor = portions.toDouble(), revision = old.revision + 1))
    }

    fun stepBack(id: Long) = launchTransition {
        val old = session(id) ?: return@launchTransition
        if (old.isCompleted || _finishing.value.contains(id)) return@launchTransition
        persistTransition(old, old.retreat(BakingClock.elapsed(), BakingClock.wallClock()).copy(revision = old.revision + 1))
    }

    fun togglePause(id: Long) = launchTransition {
        val old = session(id) ?: return@launchTransition
        if (old.isCompleted || _finishing.value.contains(id)) return@launchTransition
        persistTransition(old, old.togglePause(BakingClock.elapsed()).copy(revision = old.revision + 1))
    }

    fun exitSession(id: Long) = launchTransition { exitSessionNow(id) }

    private suspend fun exitSessionNow(id: Long) {
        val old = session(id) ?: return
        persistTransition(old, null)
        cleanExitedSession(id)
    }

    private fun cleanExitedSession(id: Long) {
        _remainingSeconds.update { it - id }
        remainingFlows.remove(id)
        notificationLedger.forgetSession(id).forEach { key -> notifier.cancelByKey(key) }
        _bakePhotoPaths.update { it - id }
        _completions.update { it - id }
        sessionLedger.forget(id)
    }

    fun cancelSession(id: Long) = launchTransition {
        val old = session(id) ?: return@launchTransition
        if (_finishing.value.contains(id)) return@launchTransition
        cancelSessionNow(old)
    }

    private suspend fun cancelSessionNow(old: BakingSession) {
        val id=old.id
        persistTransition(old, null)
        if (!old.isCompleted) {
            _cancelledCount.update { it + 1 }
            val prefs = getApplication<Application>().getSharedPreferences("madre_prefs", android.content.Context.MODE_PRIVATE)
            val key = CoffeeRing.prefsKey(old.recipe.id)
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        }
        cleanExitedSession(id)
    }

    /** The only voice write entry. Network code cannot write Room or mint an APPLIED acknowledgement. */
    suspend fun applyVoiceCommand(
        command: VoiceCommand,
        receivedElapsed: Long,
        authority: () -> VoiceAuthority,
    ): VoiceCommandReceiptEntity? {
        sessionIdsReady.await()
        return madreApp.plannedBakeOperationGate.withLock { applyVoiceLocked(command,receivedElapsed,authority) }
    }

    private suspend fun applyVoiceLocked(command: VoiceCommand,receivedElapsed: Long,authority: ()->VoiceAuthority): VoiceCommandReceiptEntity? = transitionGate.withLock {
            try {
                val fingerprint = command.fingerprint()
                val prior = activeBakeRepository.receipt(command.id)
                if (prior != null) return@withLock prior.takeIf { it.fingerprint == fingerprint }
                val old = _sessions.value.find { it.bakeKey == command.bakeKey } ?: return@withLock null
                val decision = VoiceCommandPolicy.decide(command, old, authority(), BakingClock.elapsed(),
                    BakingClock.wallClock(), receivedElapsed)
                val proposed = if (decision.outcome == VoiceOutcome.APPLIED) decision.candidate else old
                val receipt = VoiceCommandReceiptEntity(command.id, fingerprint, old.bakeKey, old.revision,
                    decision.outcome.name, proposed?.revision ?: (old.revision + 1), command.expiresAt,
                    command.operation.name, old.recipe.id)
                val stored = activeBakeRepository.commit(old.id, old.activeSnapshot(), proposed?.activeSnapshot(), receipt) {
                    controllerLive && authority().attached && authority().consented &&
                        (decision.outcome != VoiceOutcome.APPLIED ||
                            VoiceCommandPolicy.decide(command, old, authority(), BakingClock.elapsed(),
                                BakingClock.wallClock(), receivedElapsed).outcome == VoiceOutcome.APPLIED)
                }
                if (decision.outcome == VoiceOutcome.APPLIED) {
                    publishTransition(old, proposed)
                    if (proposed == null) {
                        numberStore.release(old.id)
                        _cancelledCount.update { it + 1 }
                        cleanExitedSession(old.id)
                        recoverCancellationEffects()
                    }
                }
                stored
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null } // No durable outcome is NOT an acknowledgement.
    }

    private suspend fun recoverCancellationEffects() {
        val prefs = getApplication<Application>().getSharedPreferences("madre_prefs", android.content.Context.MODE_PRIVATE)
        activeBakeRepository.applyPendingCancellationEffects { receipt ->
            val marker = "voice_cancel_${receipt.commandId}"
            if (prefs.getBoolean(marker, false)) true else {
                val key = CoffeeRing.prefsKey(receipt.recipeId)
                prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).putBoolean(marker, true).commit()
            }
        }
    }

    private fun restartTimer(id: Long) {
        timerJobs[id]?.cancel()
        timerJobs[id] = viewModelScope.launch {
            while (true) {
                val s = session(id) ?: break
                // Монотонные часы, а не стенные: смена пояса или переход на
                // летнее время не должны отнимать у теста час расстойки
                // (и не должны дарить его).
                val nowElapsed = BakingClock.elapsed()
                if (!s.isPaused && !s.isCompleted) {
                    val remaining = s.remainingSeconds(nowElapsed)
                    _remainingSeconds.update { it + (id to remaining) }
                    notifyProgress(s, remaining)
                    // Слепок для шторки — только на идущем тике. Cycle 16: на
                    // паузе он собирался те же раз в секунду и слово в слово
                    // повторял предыдущий. Честность строки «пауза» от этого не
                    // зависит: её публикует togglePause в момент перехода, и
                    // дальше в шторке стоит ровно то, что происходит на деле.
                    publishProgress()
                }
                // Cycle 20: последний сон шага — короче секунды, ровно до его
                // конца. Иначе тики идут по своей сетке, а шаг кончается по
                // своей, и ноль приходит к человеку с опозданием до секунды.
                // На паузе границы нет — там обычный шаг.
                delay(
                    if (s.isPaused || s.isCompleted) {
                        BakingTick.INTERVAL_MS
                    } else {
                        BakingTick.sleepMillis(nowElapsed, s.stepEndsAtElapsed(nowElapsed))
                    }
                )
            }
        }
    }

    /**
     * Cycle 12: отдать текущее состояние всех выпечек в шторку.
     *
     * Своих часов у сервиса нет намеренно — источник времени здесь один, этот
     * таймер. Два независимых отсчёта неминуемо разошлись бы, и человек видел
     * бы на экране одно, а в уведомлении другое.
     */
    private fun publishProgress() {
        // Cycle 20: остаток считается ЗДЕСЬ, от часов, а не берётся из карты,
        // которую заполняет тик. Публикуют слепок и переходы — пауза,
        // продолжение, переименование закваски, — а они приходят между тиками:
        // карта в этот момент хранит секунду предыдущего тика, и шторка
        // получала на секунду больше, чем стояло на странице. Часы одни и те
        // же, монотонные, — то же число, что покажет следующий тик.
        val nowElapsed = BakingClock.elapsed()
        val bakes = _sessions.value.filterNot { it.isCompleted }.map { s ->
            val remaining = s.remainingSeconds(nowElapsed)
            BakingProgress(
                sessionId = s.id,
                recipeName = s.recipe.name,
                bakeNumber = s.bakeNumber,
                // Cycle 19: имя закваски едет в шапку своей карточки в шторке.
                starterName = starterName,
                stepTitle = s.currentStep.title,
                stepIndex = s.currentStepIndex,
                stepCount = s.recipe.timeline.size,
                remainingSeconds = remaining,
                // Полоска мерится длиной СВОЕГО шага: «шаг 3 из 8» уже сказало
                // всё, что нужно сказать про выпечку целиком.
                stepTotalSeconds = s.currentStep.durationMinutes * 60L,
                // Cycle 21: точного конца шага слепок больше не несёт. Он был
                // нужен базе системного хронометра, а хронометра в шторке нет:
                // он не останавливался на нуле и уходил в минус. Тикать по
                // границе шага продолжает сам таймер (BakingTick), и приходит
                // сюда уже готовый остаток.
                isPaused = s.isPaused,
                // Cycle 14: у следующего шага только название. Отсчёт в
                // слепке один — remainingSeconds текущего шага, тот же, что
                // показывает экран таймера.
                nextStepTitle = s.recipe.timeline.getOrNull(s.currentStepIndex + 1)?.title,
            )
        }
        madreApp.activeBakes.publish(bakes)
        // Пустой список сервис читает как «всё, выпечек нет»: он снимает свои
        // уведомления и уходит сам. Отдельная команда «стоп» ему не нужна.
    }

    /**
     * Cycle 11: два уведомления по ходу выпечки — конец шага-ожидания и просьба
     * достать сливочное масло за полчаса до шага, которому нужно мягкое.
     * Условия — в BakingNotificationPlanner, «показать один раз» — в журнале:
     * тик таймера идёт раз в секунду, а уведомление на пару «сессия + шаг»
     * уходит ровно одно.
     *
     * Cycle 17: UI-таймер по-прежнему в ViewModel, но срок шага дублируется в
     * WorkManager (scheduleStepDeadline), а сессии поднимаются из active_bakes.
     * Полный живой тик 1 Гц без процесса всё ещё невозможен —
     * это не фоновая гарантированная доставка (в отличие от напоминания о
     * кормлении, которое едет через WorkManager).
     */
    private fun notifyProgress(session: BakingSession, remainingSeconds: Long) {
        if (BakingNotificationPlanner.isButterPrepDue(session, remainingSeconds)) {
            val key = BakingNotificationPlanner.butterPrepKey(session.id, session.currentStepIndex)
            if (notificationLedger.markIfNew(key)) {
                notifier.postBakingNotification(
                    sessionId = session.id,
                    key = key,
                    title = "Достаньте сливочное масло",
                    text = "Через полчаса оно понадобится мягким — «${session.recipe.name}».",
                )
            }
        }
        if (BakingNotificationPlanner.isStepDone(session, remainingSeconds)) {
            val key = BakingNotificationPlanner.stepDoneKey(session.id, session.currentStepIndex)
            if (notificationLedger.markIfNew(key)) {
                notifier.postBakingNotification(
                    sessionId = session.id,
                    key = key,
                    title = "«${session.recipe.name}» — время вышло",
                    text = "${session.currentStep.title}: шаг закончен, можно продолжать.",
                )
            }
        }
    }

    private fun stopTimer(id: Long) {
        timerJobs[id]?.cancel()
        timerJobs.remove(id)
    }

    override fun onCleared() {
        synchronized(activeBakeRepository.commitFence) {
            controllerLive = false
            madreApp.voiceCoordinator.detach(this)
        }
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        abandonJobs.values.forEach { it.cancel() }
        abandonJobs.clear()
        if(plannedBakesOwner.isInitialized())plannedBakesOwner.value.close()
        // Часы книги остановились — значит, обновлять строку хода выпечки
        // больше некому. Оставить её висеть с застывшими цифрами было бы
        // ровно тем обманом, ради которого и заводился сервис.
        madreApp.activeBakes.clear()
        BakingProgressService.stop(getApplication())
        // БД НЕ закрываем — она живёт в Application (баг v3 #1 закрыт архитектурно).
        super.onCleared()
    }
}
