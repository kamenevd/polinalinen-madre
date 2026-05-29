package com.polinalinen.madre.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.polinalinen.madre.model.*
import com.polinalinen.madre.service.TimerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BakingViewModel(application: Application) : AndroidViewModel(application) {

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _session = MutableStateFlow<BakingSession?>(null)
    val session: StateFlow<BakingSession?> = _session.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

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
                e.printStackTrace()
            }
        }
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

    fun togglePause() {
        val current = _session.value ?: return
        val updated = current.togglePause()
        _session.value = updated

        if (!updated.isPaused) {
            // Resuming — restart timer with adjusted start time
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
        val totalSeconds = step.durationMinutes * 60L

        _remainingSeconds.value = totalSeconds

        if (step.type == StepType.WAIT && totalSeconds > 0) {
            timerJob = viewModelScope.launch {
                while (isActive) {
                    val current = _session.value ?: break
                    if (current.isPaused) break

                    val elapsed = (System.currentTimeMillis() - current.stepStartedAtMillis) / 1000
                    val remaining = (totalSeconds - elapsed).coerceAtLeast(0)
                    _remainingSeconds.value = remaining

                    if (remaining <= 0) {
                        // Timer complete — send notification
                        sendTimerNotification(current.currentStep.title)
                        break
                    }

                    delay(1000)
                }
            }
        }
    }

    private fun sendTimerNotification(stepTitle: String) {
        val context = getApplication<Application>()
        // Delegate to TimerService for notification
        TimerService.showStepCompleteNotification(context, stepTitle)
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
