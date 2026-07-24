package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.remote.PocketBaseFilter
import com.polinalinen.madre.model.CommunityStats
import com.polinalinen.madre.sync.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * «Общая статистика» на главной (Cycle 5): один запрос к bake_stats при
 * появлении экрана, свой device_id исключён фильтром. Ошибка сети — не
 * ошибка UX: stats остаётся null и секция тихо показывает офлайн-строку.
 */
class CommunityStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as MadreApplication).madreApi

    private val _stats = MutableStateFlow<CommunityStats?>(null)
    val stats: StateFlow<CommunityStats?> = _stats.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                api.listBakeStats(filter = PocketBaseFilter.excludeDevice(DeviceIdentity.id(getApplication())))
            }.onSuccess { page ->
                _stats.value = CommunityStats.from(page.items)
            }
            // onFailure: молчим — PocketBase виден только из домашней сети,
            // вне её главная не должна ни падать, ни ругаться.
        }
    }
}
