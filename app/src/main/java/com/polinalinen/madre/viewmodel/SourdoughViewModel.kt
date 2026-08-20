package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.SourdoughConfigEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.data.repository.SourdoughRepository
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveRequest
import com.polinalinen.madre.data.repository.SourdoughFeedingSaveResult
import com.polinalinen.madre.notifications.FeedingReminderPlanner
import com.polinalinen.madre.notifications.FeedingReminderScheduler
import com.polinalinen.madre.notifications.MadreNotifier
import com.polinalinen.madre.sourdough.FeedingWeatherSource
import com.polinalinen.madre.sourdough.StarterRenameQueue
import com.polinalinen.madre.sync.SyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

sealed interface FeedingSaveState {
    data object Idle : FeedingSaveState
    data object Saving : FeedingSaveState
    data class Success(val feedingId: Long) : FeedingSaveState
    data class Error(val message: String) : FeedingSaveState
}

/**
 * Cycle 3: реальное состояние закваски вместо захардкоженных profile/phase/history,
 * которые раньше жили прямо в MadreNavHost. Bootstrap неявного User+Config —
 * см. SourdoughRepository.getOrCreateDefaultConfig().
 */
class SourdoughViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: SourdoughRepository = (app as MadreApplication).sourdoughRepository,
    private val syncRepository: SyncRepository = (app as MadreApplication).syncRepository,
    private val reminderScheduler: FeedingReminderScheduler = FeedingReminderScheduler(app),
    private val notifier: MadreNotifier = MadreNotifier(app),
    private val weatherSource: FeedingWeatherSource = FeedingWeatherSource(app),
    private val clock: () -> Long = System::currentTimeMillis,
    private val loadConfig: suspend () -> SourdoughConfigEntity = { repository.getOrCreateDefaultConfig() },
    private val observeConfig: (Long) -> Flow<SourdoughConfigEntity?> =
        { userId -> repository.observeConfig(userId) },
    private val describeWeather: suspend (Long) -> String? = { atMillis -> weatherSource.describe(atMillis) },
    private val saveFeeding: suspend (SourdoughFeedingSaveRequest) -> SourdoughFeedingSaveResult =
        { request -> repository.saveFeeding(request) },
    private val afterInsert: suspend (Long, Int, Int, Long) -> Unit = { feedingId, flour, water, fedAt ->
        notifier.cancelFeedingReminder()
        syncRepository.shareFeedingStat(feedingId, flour, water, fedAt)
    },
) : AndroidViewModel(app) {

    private val weatherTimeoutMillis = 4_000L
    private val saveInProgress = AtomicBoolean(false)

    private val _config = MutableStateFlow<SourdoughConfigEntity?>(null)
    val config: StateFlow<SourdoughConfigEntity?> = _config.asStateFlow()

    val history: StateFlow<List<FeedingEntity>> = _config
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { configId ->
            if (configId == null) flowOf(emptyList()) else repository.observeHistory(configId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Самая свежая посчитанная гидратация — источник для превью формы кормления. */
    val latestFinalHydration: StateFlow<Int?> = history
        .map { it.firstNotNullOfOrNull { it.finalHydrationPercent } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _saveState = MutableStateFlow<FeedingSaveState>(FeedingSaveState.Idle)
    val saveState: StateFlow<FeedingSaveState> = _saveState.asStateFlow()

    /** Имя, набранное до того, как конфиг приехал из Room. */
    private val renameQueue = StarterRenameQueue()

    init {
        viewModelScope.launch {
            val bootstrapped = loadConfig()
            _config.value = bootstrapped
            // Конфиг есть — записываем имя, если его успели набрать раньше.
            renameQueue.flush()?.let { repository.setName(bootstrapped.id, it) }
            // Держим конфиг актуальным реактивно: после кормления lastFeedingMillis
            // меняется в БД, Room сам пришлёт новое значение сюда без ручного refetch.
            //
            // Cycle 11: сюда же сходятся ВСЕ причины перепланировать напоминание —
            // новое кормление, другой интервал, выключенные напоминания. Одна
            // точка вместо трёх вызовов вразнобой: что бы ни поменялось в
            // конфиге, план пересчитывается от актуальных значений.
            observeConfig(bootstrapped.userId).collect { latest ->
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
     *
     * Имя, набранное до того, как конфиг приехал из Room, не теряется: оно
     * ждёт в [renameQueue] и уходит в базу первым делом после загрузки. Раньше
     * такое переименование молча пропадало — поле показывало набранное слово,
     * а в книге оставалось прежнее имя.
     */
    fun setStarterName(name: String) {
        val configId = renameQueue.rename(_config.value?.id, name) ?: return
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
     *
     * Cycle 26: с формы приходят только три массы — оставленная закваска, мука
     * и вода. Гидратация не спрашивается, она считается здесь ([HydrationMath])
     * от последней сохранённой; «Комментарий закваски» собирается из фактов
     * ([FeedingComment]) и ложится в запись снимком, который больше не меняется.
     */
    fun feed(
        retainedStarterGrams: Int,
        flourGrams: Int,
        waterGrams: Int,
        location: StorageLocation,
        note: String?,
        photoPath: String? = null,
    ) {
        if (!saveInProgress.compareAndSet(false, true)) return
        _saveState.value = FeedingSaveState.Saving

        val config = _config.value ?: run {
            _saveState.value = FeedingSaveState.Error("Конфиг закваски пока не инициализирован")
            saveInProgress.set(false)
            return
        }
        val configId = config.id

        viewModelScope.launch {
            try {
                val savedAtMillis = clock()
                // Погода — необязательная приправа: нет разрешения, нет свежей
                // точки или нет сети — комментарий обходится без неё.
                val weather = try {
                    withTimeoutOrNull(weatherTimeoutMillis) {
                        describeWeather(savedAtMillis)
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (_: Throwable) {
                    null
                }
                val request = SourdoughFeedingSaveRequest(
                    configId = configId,
                    intervalHours = config.intervalHours,
                    savedAtMillis = savedAtMillis,
                    retainedStarterGrams = retainedStarterGrams,
                    flourGrams = flourGrams,
                    waterGrams = waterGrams,
                    storageLocation = location,
                    note = note,
                    photoPath = photoPath,
                    weather = weather,
                )
                val result = saveFeeding(request)
                val feedingId = result.feedingId
                _saveState.value = FeedingSaveState.Success(feedingId)
                try {
                    afterInsert(feedingId, flourGrams, waterGrams, savedAtMillis)
                } catch (_: Throwable) {
                    // Sync and notifier are local persistence side effects.
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                _saveState.value = FeedingSaveState.Error(
                    cause.message?.ifBlank { "Ошибка сохранения записи" } ?: "Ошибка сохранения записи",
                )
            } finally {
                if (_saveState.value !is FeedingSaveState.Success && _saveState.value !is FeedingSaveState.Error) {
                    _saveState.value = FeedingSaveState.Idle
                }
                saveInProgress.set(false)
            }
        }
    }

    fun consumeSaveState() {
        _saveState.value = FeedingSaveState.Idle
    }

    /**
     * Clears stale terminal form state before re-entering the feeding form.
     *
     * This does not cancel an in-flight save; it only drops completed
     * Success/Error so stale navigation/pop-back behavior does not rerun.
     */
    fun clearSaveState() {
        when (_saveState.value) {
            is FeedingSaveState.Success,
            is FeedingSaveState.Error -> _saveState.value = FeedingSaveState.Idle
            else -> {}
        }
    }
}
