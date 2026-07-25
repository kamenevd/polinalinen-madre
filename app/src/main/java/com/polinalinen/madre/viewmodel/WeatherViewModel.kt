package com.polinalinen.madre.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.data.remote.WeatherApiFactory
import com.polinalinen.madre.model.WeatherNote
import com.polinalinen.madre.model.WeatherPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * «Погода за окном» (Cycle 7): последняя известная геолокация → Open-Meteo →
 * заметка Мадре на полях первой полосы. Любой сбой — нет разрешения, нет
 * локации, нет сети — молчание, а не ошибка (принцип LibraryNotesViewModel:
 * книга не ругается на внешний мир).
 */
class WeatherViewModel(app: Application) : AndroidViewModel(app) {

    private val api by lazy { WeatherApiFactory.create() }

    private val _note = MutableStateFlow<WeatherNote?>(null)
    val note: StateFlow<WeatherNote?> = _note.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val note = runCatching {
                val location = lastKnownLocation(getApplication()) ?: return@runCatching null
                val current = api.current(location.latitude, location.longitude).current
                WeatherPage.noteFor(
                    temperatureC = current.temperatureC,
                    humidityPercent = current.humidityPercent,
                    weatherCode = current.weatherCode,
                    precipitationMm = current.precipitationMm,
                )
            }.getOrNull()
            if (note != null) _note.value = note
        }
    }

    /**
     * Последняя известная точка любого провайдера — свежий GPS-фикс погоде не
     * нужен, грубой точности по сети достаточно. SecurityException (разрешение
     * отозвали на лету) ловит runCatching выше.
     */
    private fun lastKnownLocation(context: Context): Location? {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).asSequence()
            .filter { manager.allProviders.contains(it) }
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .firstOrNull()
    }
}
