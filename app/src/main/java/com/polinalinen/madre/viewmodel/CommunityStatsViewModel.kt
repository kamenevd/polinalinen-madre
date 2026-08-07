package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.remote.PocketBaseFilter
import com.polinalinen.madre.model.CommunityStats
import com.polinalinen.madre.sync.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Общая статистика» на главной (Cycle 5): один запрос к bake_stats при
 * появлении экрана, свой device_id исключён фильтром.
 *
 * Cycle 12: состояние стало честным и различимым. Раньше и «ещё грузим», и
 * «сети нет» выглядели одинаково — `stats == null`, то есть строка «общая
 * книга откроется, когда появится сеть» показывалась в том числе в первую
 * секунду после запуска, когда запрос ещё летел. И повторить неудачную
 * попытку было нечем: refresh() звался только из init.
 *
 * Cycle 17: запрос уходит с токеном — коллекция под входом. «Аккаунта нет»
 * отделено от «сервер не ответил» по той же причине, по которой в Cycle 12
 * разделились «грузим» и «нет сети»: советы человеку разные, и один из них
 * («подождите сеть») при отсутствии аккаунта не сбудется никогда.
 */
class CommunityStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val madreApp = app as MadreApplication
    private val api = madreApp.madreApi

    private val _state = MutableStateFlow<CommunityState>(CommunityState.Loading)
    val state: StateFlow<CommunityState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = CommunityState.Loading
        viewModelScope.launch {
            // Токен лежит в SharedPreferences под Keystore — это диск.
            val token = withContext(Dispatchers.IO) { madreApp.authTokenStore.read() }
                ?.takeIf { it.isNotBlank() }
            if (token == null) {
                _state.value = CommunityState.SignedOut
                return@launch
            }
            runCatching {
                api.listBakeStats(
                    token = token,
                    filter = PocketBaseFilter.excludeDevice(DeviceIdentity.id(getApplication())),
                )
            }.onSuccess { page ->
                _state.value = CommunityState.Loaded(CommunityStats.from(page.items))
            }.onFailure {
                // Не ошибка приложения: общая книга живёт на своём сервере, и
                // без сети её просто не видно. Локальная книга работает вся.
                _state.value = CommunityState.Unreachable
            }
        }
    }
}

/** Что сейчас видно про общую книгу. */
sealed interface CommunityState {
    /** Запрос ещё летит — это не «нет сети». */
    data object Loading : CommunityState

    data class Loaded(val stats: CommunityStats) : CommunityState

    /** Аккаунта нет: общей книги не существует, и ждать её бессмысленно. */
    data object SignedOut : CommunityState

    /** Сервер не ответил; попытку можно повторить руками. */
    data object Unreachable : CommunityState
}
