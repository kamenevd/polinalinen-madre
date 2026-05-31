package com.polinalinen.madre.sourdough

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class SourdoughUiState(
    val config: SourdoughConfig = SourdoughConfig(),
    val feedings: List<Feeding> = emptyList(),
    val lastFeedingText: String = "Ещё не кормили",
    val lastFeedingAgoText: String = "",
    val nextFeedingText: String = "",
    val isOverdue: Boolean = false,
    val hoursUntilNext: Long = 0,
    val photos: List<String> = emptyList()
)

class SourdoughViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        SourdoughDatabase::class.java,
        "sourdough.db"
    ).build()

    private val dao = db.sourdoughDao()

    private val _uiState = MutableStateFlow(SourdoughUiState())
    val uiState: StateFlow<SourdoughUiState> = _uiState.asStateFlow()

    // Camera: pending feeding ID to attach photo to
    private val _pendingPhotoFeedingId = MutableStateFlow<Int?>(null)
    val pendingPhotoFeedingId: StateFlow<Int?> = _pendingPhotoFeedingId.asStateFlow()

    // Config editing
    private val _isEditingConfig = MutableStateFlow(false)
    val isEditingConfig: StateFlow<Boolean> = _isEditingConfig.asStateFlow()

    init {
        // Ensure default config exists
        viewModelScope.launch {
            val config = dao.getConfig()
            if (config == null) {
                dao.upsertConfig(SourdoughConfig())
            }
            refreshState()
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            val config = dao.getConfig() ?: SourdoughConfig()
            val feedings = dao.getAllFeedings()
            val latest = feedings.firstOrNull()
            val photos = feedings.mapNotNull { it.photoPath }

            val now = System.currentTimeMillis()

            val lastFeedingText = latest?.let {
                formatDate(it.timestamp)
            } ?: "Ещё не кормили"

            val lastFeedingAgoText = latest?.let {
                formatTimeAgo(now - it.timestamp)
            } ?: ""

            val nextFeedingMillis = latest?.let {
                it.timestamp + config.intervalHours * 3_600_000L
            } ?: now

            val hoursUntil = ((nextFeedingMillis - now) / 3_600_000L).coerceAtLeast(0)
            val isOverdue = nextFeedingMillis <= now

            val nextFeedingText = if (latest != null) {
                if (isOverdue) {
                    "Пора кормить!"
                } else {
                    "Следующее: ${formatDate(nextFeedingMillis)}"
                }
            } else ""

            _uiState.update {
                it.copy(
                    config = config,
                    feedings = feedings,
                    lastFeedingText = lastFeedingText,
                    lastFeedingAgoText = lastFeedingAgoText,
                    nextFeedingText = nextFeedingText,
                    isOverdue = isOverdue,
                    hoursUntilNext = hoursUntil,
                    photos = photos
                )
            }
        }
    }

    fun recordFeeding() {
        viewModelScope.launch {
            dao.insertFeeding(Feeding(timestamp = System.currentTimeMillis()))
            refreshState()
            scheduleReminders()
        }
    }

    /** Record feeding from notification — uses current time */
    fun recordFeedingFromNotification() {
        recordFeeding()
    }

    fun updateConfig(name: String, intervalHours: Int) {
        viewModelScope.launch {
            dao.upsertConfig(SourdoughConfig(name = name, intervalHours = intervalHours))
            _isEditingConfig.value = false
            refreshState()
            scheduleReminders()
        }
    }

    fun deleteFeeding(id: Int) {
        viewModelScope.launch {
            // Delete photo file if exists
            val feeding = dao.getAllFeedings().find { it.id == id }
            feeding?.photoPath?.let { path ->
                File(path).delete()
            }
            dao.deleteFeeding(id)
            refreshState()
        }
    }

    fun attachPhoto(feedingId: Int, photoPath: String) {
        viewModelScope.launch {
            val feeding = dao.getAllFeedings().find { it.id == feedingId }
            if (feeding != null) {
                dao.updateFeeding(feeding.copy(photoPath = photoPath))
                refreshState()
            }
        }
    }

    fun requestPhotoForLatest() {
        _pendingPhotoFeedingId.value = _uiState.value.feedings.firstOrNull()?.id
    }

    fun clearPendingPhoto() {
        _pendingPhotoFeedingId.value = null
    }

    fun toggleEditConfig() {
        _isEditingConfig.value = !_isEditingConfig.value
    }

    private fun scheduleReminders() {
        val context = getApplication<Application>()
        SourdoughReminderWorker.schedule(context, _uiState.value.config.intervalHours)
    }

    companion object {
        fun formatDate(millis: Long): String {
            val sdf = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru"))
            return sdf.format(Date(millis))
        }

        fun formatDateShort(millis: Long): String {
            val sdf = SimpleDateFormat("d MMMM, HH:mm", Locale("ru"))
            return sdf.format(Date(millis))
        }

        fun formatTimeAgo(diffMillis: Long): String {
            val hours = diffMillis / 3_600_000L
            val minutes = (diffMillis % 3_600_000L) / 60_000L
            return when {
                hours >= 24 -> {
                    val days = hours / 24
                    val remainHours = hours % 24
                    if (remainHours > 0) "$days дн ${remainHours}ч назад"
                    else "$days дн назад"
                }
                hours > 0 -> {
                    if (minutes > 0) "$hours ч ${minutes} мин назад"
                    else "$hours ч назад"
                }
                minutes > 0 -> "$minutes мин назад"
                else -> "только что"
            }
        }
    }
}
