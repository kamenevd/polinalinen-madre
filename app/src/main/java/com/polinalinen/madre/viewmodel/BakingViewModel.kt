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
import com.polinalinen.madre.model.BakingClock
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.BakingTick
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.notifications.BakingNotificationPlanner
import com.polinalinen.madre.notifications.BakeStepEndWorker
import com.polinalinen.madre.notifications.BootRestorePlanner
import com.polinalinen.madre.notifications.BakingProgress
import com.polinalinen.madre.notifications.BakingProgressService
import com.polinalinen.madre.notifications.MadreNotifier
import com.polinalinen.madre.notifications.NotificationLedger
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.shelf.ShelfSharePolicy
import com.polinalinen.madre.ui.components.CoffeeRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext

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
class BakingViewModel(app: Application) : AndroidViewModel(app) {

    private val madreApp = app as MadreApplication
    private val recipeRepository = madreApp.recipeRepository
    private val bakeHistoryRepository = madreApp.bakeHistoryRepository
    private val activeBakeRepository = madreApp.activeBakeRepository
    private val syncRepository = madreApp.syncRepository
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
        bakeHistoryRepository.observeAll()
            .map { records -> records.groupingBy { it.recipeId }.eachCount() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // «Старое фото» (Cycle 6, AgedPhoto): вклеенные фотокарточки этой сессии
    // приложения — Complete-экран показывает свежее фото сразу после выбора.
    private val _bakePhotoPaths = MutableStateFlow<Map<Long, String>>(emptyMap())
    val bakePhotoPaths: StateFlow<Map<Long, String>> = _bakePhotoPaths.asStateFlow()

    // sessionId → id записи формуляра: нужен, чтобы фотокарточка, выбранная на
    // Complete-экране, легла именно в свою строку bake_records.
    private val bakeRecordIds = mutableMapOf<Long, Long>()
    private val bakeCompletedAt = mutableMapOf<Long, Long>()
    private val sharedSessionIds = mutableSetOf<Long>()

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
            val saved = activeBakeRepository.all()
            nextSessionId = maxOf(nextSessionId, saved.maxOfOrNull { it.sessionId + 1 } ?: 1L)
            val byId = recipes.associateBy { it.id }
            val nowElapsed = BakingClock.elapsed()
            val nowWall = BakingClock.wallClock()
            val restored = saved.mapNotNull { row ->
                val recipe = byId[row.recipeId] ?: return@mapNotNull null
                BakingSession.restoreFromActive(
                    sessionId = row.sessionId,
                    recipe = recipe,
                    stepIndex = row.stepIndex,
                    startedAtWallClock = row.startedAtWallClock,
                    pausedAtWallClock = row.pausedAtWallClock,
                    nowElapsed = nowElapsed,
                    nowWallClock = nowWall,
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
            sessionIdsReady.complete(Unit)
        }
        // Токен под Keystore — диск, читаем на IO.
        viewModelScope.launch {
            _sharingAvailable.value = withContext(Dispatchers.IO) {
                !madreApp.authTokenStore.read().isNullOrBlank()
            }
        }
    }

    fun session(id: Long): BakingSession? = _sessions.value.find { it.id == id }

    /** Возвращает id новой сессии — экран таймера должен открыться именно на ней. */
    fun startBaking(recipe: Recipe, scaleFactor: Double): Long {
        // Cycle 17: не выдавать id=1, пока init не прочитал active_bakes.
        if (!sessionIdsReady.isCompleted) {
            // Явный wait нельзя на main без suspend API; блокируем через runBlocking
            // только если init ещё в полёте — обычно уже complete.
            kotlinx.coroutines.runBlocking { sessionIdsReady.await() }
        }
        val id = nextSessionId++
        _sessions.update { it + BakingSession.start(id = id, recipe = recipe, scaleFactor = scaleFactor) }
        rememberActiveBake(id)
        restartTimer(id)
        // Слепок публикуется ДО подъёма сервиса, а не после: сервис читает
        // список сразу при создании, и пустой список для него означает «всё,
        // выпечек нет» — он бы ушёл, не показав ни строки.
        publishProgress()
        // Строка хода выпечки в шторке. Сервис поднимается ровно здесь —
        // из явного действия человека («Начать выпечку»), а не из фона, и
        // потому не спотыкается об ограничения Android 12+.
        BakingProgressService.start(getApplication())
        return id
    }

    fun advanceStep(id: Long) {
        val nowElapsed = BakingClock.elapsed()
        val nowWallClock = BakingClock.wallClock()
        _sessions.update { list -> list.map { if (it.id == id) it.advance(nowElapsed, nowWallClock) else it } }
        val s = session(id)
        if (s?.isCompleted == true) {
            stopTimer(id)
            forgetActiveBake(id)
            // Выпечка дошла до конца — строка хода ей больше не нужна, даже
            // если человек ещё стоит на странице «Готово» и вклеивает фото.
            publishProgress()
            // Формуляр книги и хитмэп на Полке читают именно эту таблицу — пишем
            // один раз, ровно в момент завершения (не раньше, не задним числом).
            //
            // Cycle 17: отправка в общую книгу переехала ВНУТРЬ этой корутины.
            // Раньше она стояла следующей строкой снаружи и потому уходила
            // раньше, чем insert возвращал id: ключ события брался из номера
            // сессии просто потому, что id записи в тот момент ещё не
            // существовало. Отсюда и росла подмена ключей (см. SyncEventId.forBake).
            viewModelScope.launch {
                val recordId =
                    bakeHistoryRepository.record(s.recipe.id, s.recipe.name, s.scaleFactor.toInt().coerceAtLeast(1))
                bakeRecordIds[id] = recordId
                val completed = bakeHistoryRepository.getCompletedAt(recordId) ?: System.currentTimeMillis()
                bakeCompletedAt[id] = completed
                val prefs = getApplication<Application>()
                    .getSharedPreferences(ShelfSharePolicy.PREFS, android.content.Context.MODE_PRIVATE)
                val mode = ShelfSharePolicy.read(prefs)
                if (ShelfSharePolicy.shouldShareOnComplete(mode, _sharingAvailable.value)) {
                    shareBakeStats(id)
                }
            }
        } else {
            rememberActiveBake(id)
            restartTimer(id)
        }
    }

    /**
     * Cycle 15: снимок текущего шага в active_bakes — по нему BootReceiver
     * восстановит срок после перезагрузки телефона. Пишется на каждом переходе
     * шага и на паузе, то есть ровно тогда, когда меняется срок его конца.
     *
     * Отметка паузы переводится в стенные часы: монотонных после ребута не
     * существует. Перевод точный — [BakingSession.togglePause] держит обе
     * отметки начала шага в одном моменте.
     */
    private fun rememberActiveBake(id: Long) {
        val s = session(id) ?: return
        val step = s.currentStep
        val snapshot = ActiveBakeEntity(
            sessionId = s.id,
            recipeId = s.recipe.id,
            recipeName = s.recipe.name,
            stepTitle = step.title,
            stepIndex = s.currentStepIndex,
            stepDurationMinutes = step.durationMinutes,
            isWaitStep = step.type == StepType.WAIT,
            startedAtWallClock = s.startedAtWallClock,
            pausedAtWallClock = s.pausedAtElapsed?.let {
                s.startedAtWallClock + (it - s.startedAtElapsed)
            },
        )
        viewModelScope.launch {
            activeBakeRepository.remember(snapshot)
            scheduleStepDeadline(snapshot)
        }
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
     * Выпечка закончена или брошена — восстанавливать после ребута нечего.
     * Снимаем и уже назначенный срок: уведомление «время вышло» от выпечки,
     * которой больше нет, — тот же обман, что и застывшая строка в шторке.
     */
    private fun forgetActiveBake(id: Long) {
        WorkManager.getInstance(getApplication())
            .cancelUniqueWork(BootRestorePlanner.uniqueWorkName(id))
        viewModelScope.launch { activeBakeRepository.forget(id) }
    }

    /**
     * Отправить статистику этой выпечки в общую книгу (Cycle 5). Идемпотентно
     * дважды: unique work name «sync-bake-record-<recordId>» + KEEP гасит
     * повтор, пока запись ещё в очереди этого устройства, а client_event_id —
     * повтор, который очереди уже не виден (доставленный POST с потерянным
     * ответом, доотправка после переустановки).
     *
     * Cycle 17: ключ считается от id строки формуляра, поэтому до записи в
     * bake_records отправлять нечего — [advanceStep] зовёт этот метод сразу
     * после insert'а при политике «всегда», а лист «Поставить на полку?» —
     * позже, с экрана готовности.
     */
    fun shareBakeStats(id: Long, withPhoto: Boolean = false) {
        val s = session(id) ?: return
        val recordId = bakeRecordIds[id] ?: return
        val account = madreApp.familyAccountRepository.currentAccount()
        val photoPath = if (withPhoto) _bakePhotoPaths.value[id] else null
        val bakedAtMillis = bakeCompletedAt[id] ?: System.currentTimeMillis()
        syncRepository.shareBakeStat(
            recordId = recordId,
            recipeId = s.recipe.id,
            recipeName = s.recipe.name,
            portions = s.scaleFactor.toInt().coerceAtLeast(1),
            bakedAtMillis = bakedAtMillis,
            displayName = account?.displayName,
            familyName = account?.familyName,
            photoPath = photoPath,
        )
        sharedSessionIds += id
    }

    /**
     * «Старое фото» (Cycle 6 → Cycle 11): вклеить готовый снимок в запись
     * формуляра этой выпечки. На вход приходит путь ОТНОСИТЕЛЬНО filesDir
     * (Cycle 15, PhotoStore.commit) — копированием, поворотом и оформлением
     * занимается ui/photo/PhotoAttachment, а content-URI до Room не доходит
     * вовсе. Обратно в файл его собирает PhotoStore.resolve.
     *
     * Запись формуляра создаётся асинхронно в advanceStep, но к моменту, когда
     * человек выбрал и оформил кадр, insert давно завершён — bakeRecordIds
     * уже заполнен.
     */
    fun attachBakePhoto(sessionId: Long, path: String) {
        if (path.isBlank()) return
        _bakePhotoPaths.update { it + (sessionId to path) }
        val recordId = bakeRecordIds[sessionId] ?: return
        viewModelScope.launch { bakeHistoryRepository.attachPhoto(recordId, path) }
        if (sessionId in sharedSessionIds) {
            syncRepository.shareBakePhoto(recordId, path)
        }
    }

    /**
     * Cycle 11: убрать фотокарточку со страницы. Файл в filesDir намеренно не
     * удаляем: та же карточка может быть уже вписана в запись формуляра, и
     * стирать её из-под истории книги эта кнопка не должна.
     */
    fun clearBakePhoto(sessionId: Long) {
        _bakePhotoPaths.update { it - sessionId }
        val recordId = bakeRecordIds[sessionId] ?: return
        if (sessionId in sharedSessionIds) {
            syncRepository.clearBakePhoto(recordId)
        }
    }

    fun stepBack(id: Long) {
        val nowElapsed = BakingClock.elapsed()
        val nowWallClock = BakingClock.wallClock()
        _sessions.update { list -> list.map { if (it.id == id) it.retreat(nowElapsed, nowWallClock) else it } }
        rememberActiveBake(id)
        restartTimer(id)
    }

    fun togglePause(id: Long) {
        val nowElapsed = BakingClock.elapsed()
        _sessions.update { list -> list.map { if (it.id == id) it.togglePause(nowElapsed) else it } }
        // Пауза сдвигает конец шага — снимок обязан узнать об этом сразу, иначе
        // после ребута выпечка вернётся с чужим сроком.
        rememberActiveBake(id)
        // И шторка тоже: с Cycle 16 тик на паузе молчит, значит слово «пауза»
        // (и снятие его) доносит сюда сам переход, а не следующая секунда.
        publishProgress()
    }

    /** Завершает и убирает ИМЕННО эту сессию — остальные активные продолжают идти нетронутыми. */
    fun exitSession(id: Long) {
        stopTimer(id)
        forgetActiveBake(id)
        _sessions.update { list -> list.filterNot { it.id == id } }
        _remainingSeconds.update { it - id }
        remainingFlows.remove(id)
        // Выпечка закрыта — её ключи в журнале больше ни к чему, а уже
        // показанные уведомления о шагах надо снять. До Cycle 12 они
        // оставались висеть: «время вышло» от выпечки, которой давно нет.
        notificationLedger.forgetSession(id).forEach { key -> notifier.cancelByKey(key) }
        publishProgress()
        bakeRecordIds.remove(id)
        bakeCompletedAt.remove(id)
    }

    /**
     * Бросить выпечку до готовности — учитывается в [cancelledCount] (см.
     * InkBlot) и оставляет «След от кружки» на развороте этого рецепта
     * (DESIGN-V4.md Cycle 9, CoffeeRing): счётчик прерываний пишется в
     * madre_prefs и, в отличие от клякс, переживает перезапуск — высохший
     * кофейный круг со страницы уже не смыть.
     */
    fun cancelSession(id: Long) {
        val s = session(id)
        if (s?.isCompleted == false) {
            _cancelledCount.update { it + 1 }
            val prefs = getApplication<Application>()
                .getSharedPreferences("madre_prefs", android.content.Context.MODE_PRIVATE)
            val key = CoffeeRing.prefsKey(s.recipe.id)
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        }
        exitSession(id)
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
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        // Часы книги остановились — значит, обновлять строку хода выпечки
        // больше некому. Оставить её висеть с застывшими цифрами было бы
        // ровно тем обманом, ради которого и заводился сервис.
        madreApp.activeBakes.clear()
        BakingProgressService.stop(getApplication())
        // БД НЕ закрываем — она живёт в Application (баг v3 #1 закрыт архитектурно).
        super.onCleared()
    }
}
