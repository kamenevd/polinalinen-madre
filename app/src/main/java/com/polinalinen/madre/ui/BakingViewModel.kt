package com.polinalinen.madre.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.polinalinen.madre.model.*
import com.polinalinen.madre.service.SessionPersistence
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

    private val persistence = SessionPersistence

    init {
        loadRecipes()
        restoreSessions()
    }

    private fun restoreSessions() {
        try {
            val saved = persistence.loadAll(getApplication())
            if (saved.isEmpty()) return

            val restoredMap = mutableMapOf<String, ActiveSession>()
            for ((id, s) in saved) {
                val (sessionId, name, session) = persistence.restoreActiveSession(s)
                if (session.isCompleted) continue // skip completed

                val remaining = if (session.currentStep.type == com.polinalinen.madre.model.StepType.WAIT
                    && session.currentStep.durationMinutes > 0 && !session.isPaused) {
                    // Recalculate remaining from stepStartedAtMillis
                    val elapsed = (System.currentTimeMillis() - session.stepStartedAtMillis) / 1000
                    val total = session.currentStep.durationMinutes * 60L
                    (total - elapsed).coerceAtLeast(0)
                } else {
                    persistence.getRemainingSeconds(s)
                }

                restoredMap[id] = ActiveSession(
                    id = id,
                    name = name,
                    session = session,
                    remainingSeconds = remaining
                )
            }
            if (restoredMap.isNotEmpty()) {
                _sessionsMap.value = restoredMap
            }
        } catch (_: Exception) {}
    }

    private fun persistSession(id: String) {
        try {
            val active = _sessionsMap.value[id] ?: return
            persistence.saveSession(
                getApplication(),
                id, active.name, active.session, active.remainingSeconds
            )
        } catch (_: Exception) {}
    }

    private fun removePersistedSession(id: String) {
        try {
            persistence.removeSession(getApplication(), id)
        } catch (_: Exception) {}
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

        // Bugfix 3a: Explicit map update to ensure flow emits
        val updatedMap = _sessionsMap.value + (id to active)
        _sessionsMap.value = updatedMap

        _currentSessionId.value = id
        _currentSession.value = newSession
        startStepTimer(id, newSession)
    }

    fun resumeSession(sessionId: String) {
        val active = _sessionsMap.value[sessionId] ?: return
        _currentSessionId.value = sessionId
        _currentSession.value = active.session

        // Bugfix 3b: If remainingSeconds == 0 and step is WAIT, recalculate from stepStartedAtMillis
        var effectiveRemaining = active.remainingSeconds
        val step = active.session.currentStep
        if (step.type == StepType.WAIT && step.durationMinutes > 0) {
            if (effectiveRemaining <= 0) {
                val elapsedRealMs = System.currentTimeMillis() - active.session.stepStartedAtMillis
                val totalRealSeconds = step.durationMinutes * 60L
                val elapsedRealSeconds = elapsedRealMs / 1000
                effectiveRemaining = (totalRealSeconds - elapsedRealSeconds).coerceAtLeast(0)
            }
        }

        _remainingSeconds.value = effectiveRemaining

        // Update the sessions map with corrected remaining time
        if (effectiveRemaining != active.remainingSeconds) {
            _sessionsMap.value = _sessionsMap.value.toMutableMap().apply {
                this[sessionId] = active.copy(remainingSeconds = effectiveRemaining)
            }
        }

        // Resume timer from where it left off
        if (!active.session.isCompleted && !active.session.isPaused && step.type == StepType.WAIT && step.durationMinutes > 0) {
            resumeStepTimer(sessionId, active.session, effectiveRemaining)
        }
    }

    fun advanceStep() {
        val current = _currentSession.value ?: return
        val sessionId = _currentSessionId.value ?: return
        timerJobs[sessionId]?.cancel()

        val completedStepTitle = current.currentStep.title
        val next = current.advance()
        _currentSession.value = next

        // Update sessions map
        _sessionsMap.value = _sessionsMap.value.toMutableMap().apply {
            this[sessionId] = this[sessionId]?.copy(session = next) ?: return@apply
        }
        persistSession(sessionId)

        if (!next.isCompleted) {
            // Show step complete notification with next step info
            try {
                val context = getApplication<Application>()
                val name = _sessionsMap.value[sessionId]?.name ?: ""
                val nextStepTitle = next.currentStep.title
                TimerHelper.showStepCompleteNotification(
                    context,
                    completedStepTitle = completedStepTitle,
                    nextStepTitle = nextStepTitle,
                    recipeName = name,
                    sessionId = sessionId
                )
            } catch (_: Exception) {}

            startStepTimer(sessionId, next)
        } else {
            _remainingSeconds.value = 0
            // Show final completion notification
            try {
                val context = getApplication<Application>()
                val name = _sessionsMap.value[sessionId]?.name ?: ""
                TimerHelper.showStepCompleteNotification(
                    context,
                    completedStepTitle = completedStepTitle,
                    nextStepTitle = null,
                    recipeName = name,
                    sessionId = sessionId
                )
            } catch (_: Exception) {}
            TimerHelper.cancelProgressNotification(getApplication(), sessionId)
            removePersistedSession(sessionId)
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
        persistSession(sessionId)

        if (!updated.isPaused) {
            // Resume from saved remaining time
            val savedRemaining = _sessionsMap.value[sessionId]?.remainingSeconds ?: 0L
            resumeStepTimer(sessionId, updated, savedRemaining)
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
        removePersistedSession(sessionId)
        if (_currentSessionId.value == sessionId) {
            _currentSession.value = null
            _currentSessionId.value = null
        }
        TimerHelper.cancelProgressNotification(getApplication(), sessionId)
    }

    private fun startStepTimer(sessionId: String, session: BakingSession) {
        val step = session.currentStep

        // Show action notification for ACTION steps
        if (step.type == StepType.ACTION) {
            try {
                val context = getApplication<Application>()
                val name = _sessionsMap.value[sessionId]?.name ?: ""
                TimerHelper.showActionStepNotification(
                    context,
                    recipeName = name,
                    stepTitle = step.title,
                    sessionId = sessionId
                )
            } catch (_: Exception) {}
            _remainingSeconds.value = 0
            return
        }

        // For WAIT steps, start the timer
        val realTotalSeconds = step.durationMinutes * 60L
        resumeStepTimer(sessionId, session, realTotalSeconds)
    }

    private fun resumeStepTimer(sessionId: String, session: BakingSession, remainingAtResume: Long) {
        timerJobs[sessionId]?.cancel()
        val step = session.currentStep
        val realTotalSeconds = step.durationMinutes * 60L

        _remainingSeconds.value = remainingAtResume

        if (step.type == StepType.WAIT && remainingAtResume > 0) {
            val acceleratedRemaining = remainingAtResume / speedMultiplier

            timerJobs[sessionId] = viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                val acceleratedDurationMs = acceleratedRemaining * 1000L

                while (isActive) {
                    val current = _currentSession.value ?: break
                    if (current.isPaused) break

                    val elapsedMs = System.currentTimeMillis() - startedAt
                    val progress = if (acceleratedDurationMs > 0) elapsedMs.toFloat() / acceleratedDurationMs else 1f
                    val displayRemaining = ((remainingAtResume * (1f - progress))).toLong().coerceAtLeast(0)

                    _remainingSeconds.value = displayRemaining

                    // Update sessions map
                    _sessionsMap.value = _sessionsMap.value.toMutableMap().apply {
                        this[sessionId] = this[sessionId]?.copy(remainingSeconds = displayRemaining) ?: return@apply
                    }
                    // Persist every 10 seconds (not every tick to avoid I/O)
                    if (displayRemaining % 10 == 0L) {
                        persistSession(sessionId)
                    }

                    // Update notification (handles both normal and urgent states)
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
                            // Timer completed — show completion notification
                            val nextStepIndex = session.currentStepIndex + 1
                            val nextStepTitle = if (nextStepIndex < session.recipe.timeline.size) {
                                session.recipe.timeline[nextStepIndex].title
                            } else null
                            TimerHelper.showStepCompleteNotification(
                                context,
                                completedStepTitle = step.title,
                                nextStepTitle = nextStepTitle,
                                recipeName = name,
                                sessionId = sessionId
                            )
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
