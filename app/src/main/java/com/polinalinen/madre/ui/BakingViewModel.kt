package com.polinalinen.madre.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.polinalinen.madre.model.*
import com.polinalinen.madre.service.TimerHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BakingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Debug speed multiplier. 1 = normal, 1000 = 8h in 29sec */
        var speedMultiplier: Int = 1
            private set
    }

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _session = MutableStateFlow<BakingSession?>(null)
    val session: StateFlow<BakingSession?> = _session.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _devMode = MutableStateFlow(false)
    val devMode: StateFlow<Boolean> = _devMode.asStateFlow()

    private var timerJob: Job? = null

    init {
        loadRecipes()
    }

    private fun loadRecipes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = getApplication<Application>()
                    .assets
                    .open("recipes.json")
                    .bufferedReader()
                    .use { it.readText() }

                val db = Gson().fromJson(json, RecipeDatabase::class.java)
                _recipes.value = db.recipes
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки рецептов: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun toggleDevMode() {
        _devMode.value = !_devMode.value
        speedMultiplier = if (_devMode.value) 1000 else 1
    }

    fun selectRecipe(recipe: Recipe) {
        val newSession = BakingSession(recipe = recipe)
        _session.value = newSession
        startStepTimer(newSession)
    }

    fun advanceStep() {
        val current = _session.value ?: return
        timerJob?.cancel()

        val next = current.advance()
        _session.value = next

        if (!next.isCompleted) {
            startStepTimer(next)
        } else {
            _remainingSeconds.value = 0
        }
    }

    /** Skip current step entirely (dev only) */
    fun skipCurrentStep() {
        advanceStep()
    }

    fun togglePause() {
        val current = _session.value ?: return
        val updated = current.togglePause()
        _session.value = updated

        if (!updated.isPaused) {
            startStepTimer(updated)
        } else {
            timerJob?.cancel()
        }
    }

    fun exitSession() {
        timerJob?.cancel()
        _session.value = null
        _remainingSeconds.value = 0
    }

    private fun startStepTimer(session: BakingSession) {
        timerJob?.cancel()
        val step = session.currentStep
        val realTotalSeconds = step.durationMinutes * 60L
        val acceleratedSeconds = realTotalSeconds / speedMultiplier

        _remainingSeconds.value = realTotalSeconds // Show real time on display

        if (step.type == StepType.WAIT && realTotalSeconds > 0) {
            timerJob = viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                val acceleratedDurationMs = acceleratedSeconds * 1000L

                while (isActive) {
                    val current = _session.value ?: break
                    if (current.isPaused) break

                    val elapsedMs = System.currentTimeMillis() - startedAt
                    val acceleratedRemaining = ((acceleratedDurationMs - elapsedMs) / 1000).coerceAtLeast(0)
                    // Display shows real remaining time scaled by progress
                    val progress = if (acceleratedDurationMs > 0) elapsedMs.toFloat() / acceleratedDurationMs else 1f
                    val displayRemaining = ((realTotalSeconds * (1f - progress))).toLong().coerceAtLeast(0)
                    _remainingSeconds.value = displayRemaining

                    if (acceleratedRemaining <= 0) {
                        try {
                            val context = getApplication<Application>()
                            TimerHelper.showStepCompleteNotification(context, current.currentStep.title)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        break
                    }

                    delay(1000)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
