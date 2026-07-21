package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _sessions = MutableStateFlow<List<BakingSession>>(emptyList())
    val sessions: StateFlow<List<BakingSession>> = _sessions.asStateFlow()

    /** Секунд до конца текущего шага — отдельное число на каждую активную сессию. */
    private val _remainingSeconds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val remainingSeconds: StateFlow<Map<Long, Long>> = _remainingSeconds.asStateFlow()

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
                bakeHistoryRepository.record(s.recipe.id, s.recipe.name, s.scaleFactor.toInt().coerceAtLeast(1))
            }
        } else {
            restartTimer(id)
        }
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
