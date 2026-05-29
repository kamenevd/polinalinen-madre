package com.polinalinen.madre.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.polinalinen.madre.model.*
import com.polinalinen.madre.service.TimerHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ActiveSession(
    val id: String,
    val name: String,
    val session: BakingSession,
    val remainingSeconds: Long = 0
) {
    val progressPercent: Int
        get() = (session.progress * 100).toInt()

    val currentStepTitle: String
        get() = session.currentStep.title

    val isTimerRunning: Boolean
        get() = !session.isCompleted && !session.isPaused && session.currentStep.type == StepType.WAIT
}

class BakingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        var speedMultiplier: Int = 1
            private set
    }

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    // Current active session (for the timeline screen)
    private val _currentSessionId = MutableStateFlow<String?>(null)
    private val _sessionsMap = MutableStateFlow<Map<String, ActiveSession>>(emptyMap())

    val activeSessions: StateFlow<List<ActiveSession>> = _sessionsMap.map { map ->
        map.values.filter { !it.session.isCompleted }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentSession = MutableStateFlow<BakingSession?>(null)
    val session: StateFlow<BakingSession?> = _currentSession.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _devMode = MutableStateFlow(false)
    val devMode: StateFlow<Boolean> = _devMode.asStateFlow()

    private val timerJobs = mutableMapOf<String, Job>()

    init {
        loadRecipes()
    }

    private fun loadRecipes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = getApplication<Application>()
                    .assets.open("recipes.json")
                    .bufferedReader().use { it.readText() }
                val db = Gson().fromJson(json, RecipeDatabase::class.java)
                _recipes.value = db.recipes
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки рецептов: ${e.message}"
            }
        }
    }

    fun toggleDevMode() {
        _devMode.value = !_devMode.value
        speedMultiplier = if (_devMode.value) 1000 else 1
    }

    fun selectRecipe(recipe: Recipe, sessionName: String = recipe.name) {
        val id = "${recipe.id}_${System.currentTimeMillis()}"
        val newSession = BakingSession(recipe = recipe)

        val active = ActiveSession(
            id = id,
            name = sessionName,
            session = newSession
        )

        _sessionsMap.value = _sessionsMap.value + (id to active)
        _currentSessionId.value = id
        _currentSession.value = newSession
        startStepTimer(id, newSession)
    }

    fun resumeSession(sessionId: String) {
        val active = _sessionsMap.value[sessionId] ?: return
        _currentSessionId.value = sessionId
        _currentSession.value = active.session
        _remainingSeconds.value = active.remainingSeconds

        // Restart timer if it was a wait step
        if (active.isTimerRunning) {
            startStepTimer(sessionId, active.session)
        }
    }

    fun advanceStep() {
        val current = _currentSession.value ?: return
        val sessionId = _currentSessionId.value ?: return
        timerJobs[sessionId]?.cancel()

        val next = current.advance()
        _currentSession.value = next

        // Update sessions map
        _sessionsMap.value = _sessionsMap.value.toMutableMap().apply {
            this[sessionId] = this[sessionId]?.copy(session = next) ?: return@apply
        }

        if (!next.isCompleted) {
            startStepTimer(sessionId, next)
        } else {
            _remainingSeconds.value = 0
        }
    }

    fun skipCurrentStep() {
        advanceStep()
    }

    fun togglePause() {
        val current = _currentSession.value ?: return
        val sessionId = _currentSessionId.value ?: return
        val updated = current.togglePause()
        _currentSession.value = updated

        _sessionsMap.value = _sessionsMap.value.toMutableMap().apply {
            this[sessionId] = this[sessionId]?.copy(session = updated) ?: return@apply
        }

        if (!updated.isPaused) {
            startStepTimer(sessionId, updated)
        } else {
            timerJobs[sessionId]?.cancel()
        }
    }

    fun exitSession() {
        // Don't remove session, just leave the screen
        _currentSession.value = null
        _currentSessionId.value = null
        _remainingSeconds.value = 0
    }

    fun removeSession(sessionId: String) {
        timerJobs[sessionId]?.cancel()
        timerJobs.remove(sessionId)
        _sessionsMap.value = _sessionsMap.value - sessionId
        if (_currentSessionId.value == sessionId) {
            _currentSession.value = null
            _currentSessionId.value = null
        }
    }

    private fun startStepTimer(sessionId: String, session: BakingSession) {
        timerJobs[sessionId]?.cancel()
        val step = session.currentStep
        val realTotalSeconds = step.durationMinutes * 60L
        val acceleratedSeconds = realTotalSeconds / speedMultiplier

        _remainingSeconds.value = realTotalSeconds

        if (step.type == StepType.WAIT && realTotalSeconds > 0) {
            timerJobs[sessionId] = viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                val acceleratedDurationMs = acceleratedSeconds * 1000L

                while (isActive) {
                    val current = _currentSession.value ?: break
                    if (current.isPaused) break

                    val elapsedMs = System.currentTimeMillis() - startedAt
                    val progress = if (acceleratedDurationMs > 0) elapsedMs.toFloat() / acceleratedDurationMs else 1f
                    val displayRemaining = ((realTotalSeconds * (1f - progress))).toLong().coerceAtLeast(0)

                    _remainingSeconds.value = displayRemaining

                    // Update sessions map
                    _sessionsMap.value = _sessionsMap.value.toMutableMap().apply {
                        this[sessionId] = this[sessionId]?.copy(remainingSeconds = displayRemaining) ?: return@apply
                    }

                    // Update notification
                    try {
                        val context = getApplication<Application>()
                        val name = _sessionsMap.value[sessionId]?.name ?: ""
                        TimerHelper.updateProgressNotification(
                            context, sessionId, name,
                            step.title, displayRemaining, realTotalSeconds
                        )
                    } catch (_: Exception) {}

                    if (progress >= 1f) {
                        try {
                            val context = getApplication<Application>()
                            val name = _sessionsMap.value[sessionId]?.name ?: ""
                            TimerHelper.showStepCompleteNotification(context, "${name}: ${step.title}")
                        } catch (_: Exception) {}
                        break
                    }

                    delay(1000)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJobs.values.forEach { it.cancel() }
    }
}
