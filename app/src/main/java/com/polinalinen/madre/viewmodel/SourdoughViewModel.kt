package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
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
            repository.observeConfig(bootstrapped.userId).collect { latest ->
                if (latest != null) _config.value = latest
            }
        }
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
