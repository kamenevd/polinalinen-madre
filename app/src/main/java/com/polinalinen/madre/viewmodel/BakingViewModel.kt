package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.components.CoffeeRing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val syncRepository = madreApp.syncRepository

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _sessions = MutableStateFlow<List<BakingSession>>(emptyList())
    val sessions: StateFlow<List<BakingSession>> = _sessions.asStateFlow()

    /** Секунд до конца текущего шага — отдельное число на каждую активную сессию. */
    private val _remainingSeconds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val remainingSeconds: StateFlow<Map<Long, Long>> = _remainingSeconds.asStateFlow()

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

    private val timerJobs = mutableMapOf<Long, Job>()
    private var nextSessionId = 1L

    init {
        viewModelScope.launch { _recipes.value = recipeRepository.getRecipes() }
    }

    fun session(id: Long): BakingSession? = _sessions.value.find { it.id == id }

    /** Возвращает id новой сессии — экран таймера должен открыться именно на ней. */
    fun startBaking(recipe: Recipe, scaleFactor: Double): Long {
        val id = nextSessionId++
        _sessions.update { it + BakingSession(id = id, recipe = recipe, scaleFactor = scaleFactor) }
        restartTimer(id)
        return id
    }

    fun advanceStep(id: Long) {
        _sessions.update { list -> list.map { if (it.id == id) it.advance() else it } }
        val s = session(id)
        if (s?.isCompleted == true) {
            stopTimer(id)
            // Формуляр книги и хитмэп на Полке читают именно эту таблицу — пишем
            // один раз, ровно в момент завершения (не раньше, не задним числом).
            viewModelScope.launch {
                bakeRecordIds[id] =
                    bakeHistoryRepository.record(s.recipe.id, s.recipe.name, s.scaleFactor.toInt().coerceAtLeast(1))
            }
            // Cycle 5: та же выпечка уходит в общую книгу (PocketBase) через
            // WorkManager — без сети долетит позже, дубликаты гасятся unique
            // work name по id сессии (кнопка «Поделиться» на Complete-экране
            // использует тот же ключ).
            shareBakeStats(id)
        } else {
            restartTimer(id)
        }
    }

    /**
     * Отправить статистику этой выпечки в общую книгу (Cycle 5). Идемпотентно:
     * unique work name «sync-bake-<sessionId>» + KEEP — повторный вызов, пока
     * запись ещё в очереди, второй записи на сервере не создаст.
     */
    fun shareBakeStats(id: Long) {
        val s = session(id) ?: return
        syncRepository.shareBakeStat(
            sessionKey = id,
            recipeId = s.recipe.id,
            recipeName = s.recipe.name,
            portions = s.scaleFactor.toInt().coerceAtLeast(1),
            bakedAtMillis = System.currentTimeMillis(),
        )
    }

    /**
     * «Старое фото» (Cycle 6 → Cycle 11): вклеить готовый снимок в запись
     * формуляра этой выпечки. На вход приходит АБСОЛЮТНЫЙ путь файла в
     * filesDir — копированием, поворотом и оформлением занимается
     * ui/photo/PhotoAttachment, а content-URI до Room не доходит вовсе.
     *
     * Запись формуляра создаётся асинхронно в advanceStep, но к моменту, когда
     * человек выбрал и оформил кадр, insert давно завершён — bakeRecordIds
     * уже заполнен.
     */
    fun attachBakePhoto(sessionId: Long, absolutePath: String) {
        if (absolutePath.isBlank()) return
        _bakePhotoPaths.update { it + (sessionId to absolutePath) }
        val recordId = bakeRecordIds[sessionId] ?: return
        viewModelScope.launch { bakeHistoryRepository.attachPhoto(recordId, absolutePath) }
    }

    /**
     * Cycle 11: убрать фотокарточку со страницы. Файл в filesDir намеренно не
     * удаляем: та же карточка может быть уже вписана в запись формуляра, и
     * стирать её из-под истории книги эта кнопка не должна.
     */
    fun clearBakePhoto(sessionId: Long) {
        _bakePhotoPaths.update { it - sessionId }
    }

    fun stepBack(id: Long) {
        _sessions.update { list -> list.map { if (it.id == id) it.retreat() else it } }
        restartTimer(id)
    }

    fun togglePause(id: Long) {
        _sessions.update { list -> list.map { if (it.id == id) it.togglePause() else it } }
    }

    /** Завершает и убирает ИМЕННО эту сессию — остальные активные продолжают идти нетронутыми. */
    fun exitSession(id: Long) {
        stopTimer(id)
        _sessions.update { list -> list.filterNot { it.id == id } }
        _remainingSeconds.update { it - id }
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
                if (!s.isPaused && !s.isCompleted) {
                    val elapsed = (System.currentTimeMillis() - s.stepStartedAtMillis) / 1000
                    val total = s.currentStep.durationMinutes * 60L
                    _remainingSeconds.update { it + (id to (total - elapsed).coerceAtLeast(0)) }
                }
                delay(1000)
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
        // БД НЕ закрываем — она живёт в Application (баг v3 #1 закрыт архитектурно).
        super.onCleared()
    }
}
