package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.notifications.FeedingReminderPlanner
import com.polinalinen.madre.notifications.FeedingReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Cycle 3: реальное состояние закваски вместо захардкоженных profile/phase/history,
 * которые раньше жили прямо в MadreNavHost. Bootstrap неявного User+Config —
 * см. SourdoughRepository.getOrCreateDefaultConfig().
 */
class SourdoughViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as MadreApplication).sourdoughRepository
    private val syncRepository = (app as MadreApplication).syncRepository
    private val reminderScheduler = FeedingReminderScheduler(app)

    private val _config = MutableStateFlow<SourdoughConfigEntity?>(null)
    val config: StateFlow<SourdoughConfigEntity?> = _config.asStateFlow()

    val history: StateFlow<List<FeedingEntity>> = _config
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { configId ->
            if (configId == null) flowOf(emptyList()) else repository.observeHistory(configId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val bootstrapped = repository.getOrCreateDefaultConfig()
            _config.value = bootstrapped
            // Держим конфиг актуальным реактивно: после кормления lastFeedingMillis
            // меняется в БД, Room сам пришлёт новое значение сюда без ручного refetch.
            //
            // Cycle 11: сюда же сходятся ВСЕ причины перепланировать напоминание —
            // новое кормление, другой интервал, выключенные напоминания. Одна
            // точка вместо трёх вызовов вразнобой: что бы ни поменялось в
            // конфиге, план пересчитывается от актуальных значений.
            repository.observeConfig(bootstrapped.userId).collect { latest ->
                if (latest != null) {
                    _config.value = latest
                    rescheduleReminder(latest)
                }
            }
        }
    }

    /**
     * Cycle 11: интервал из колофона. Значения — ключи profileForInterval()
     * (12/24/48/72/168); перепланирование придёт реактивно из observeConfig.
     */
    fun setIntervalHours(hours: Int) {
        val configId = _config.value?.id ?: return
        viewModelScope.launch { repository.setIntervalHours(configId, hours) }
    }

    /**
     * Cycle 14: переименовать закваску. Запись идёт в тот же конфиг, который
     * читают дневник, колофон и планировщик напоминаний, — поэтому новое имя
     * доезжает во все три места одним путём, реактивно из observeConfig.
     */
    fun setStarterName(name: String) {
        val configId = _config.value?.id ?: return
        viewModelScope.launch { repository.setName(configId, name) }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        val configId = _config.value?.id ?: return
        viewModelScope.launch { repository.setRemindersEnabled(configId, enabled) }
    }

    private fun rescheduleReminder(config: SourdoughConfigEntity) {
        val plan = FeedingReminderPlanner.plan(
            remindersEnabled = config.remindersEnabled,
            intervalHours = config.intervalHours,
            lastFeedingMillis = config.lastFeedingMillis,
            nowMillis = System.currentTimeMillis(),
        )
        reminderScheduler.apply(config.id, plan, config.name)
    }

    /**
     * Cycle 11: кормление теперь может нести фотокарточку (камера или галерея).
     * Заметка — пусто трактуем как null (см. FeedingEntity.notes). photoPath
     * приходит уже как абсолютный путь в filesDir — никаких content URI
     * наружу не утекает, чтобы не получить IllegalArgumentException при
     * SecurityException через сутки, когда процесс перезапустится.
     */
    fun feed(
        flourGrams: Int,
        waterGrams: Int,
        location: StorageLocation,
        note: String?,
        photoPath: String? = null,
    ) {
        val configId = _config.value?.id ?: return
        viewModelScope.launch {
            val feeding = FeedingEntity(
                sourdoughConfigId = configId,
                flourGrams = flourGrams,
                waterGrams = waterGrams,
                storageLocation = location,
                notes = note,
                photoPath = photoPath,
            )
            val feedingId = repository.addFeeding(feeding)
            // Cycle 5: кормление уходит в общую книгу фоном (retry без сети).
            // Заметка и место хранения — личное, наружу только мука/вода/время.
            // photoPath не шарим — личное.
            syncRepository.shareFeedingStat(feedingId, flourGrams, waterGrams, feeding.timestampMillis)
        }
    }
}
