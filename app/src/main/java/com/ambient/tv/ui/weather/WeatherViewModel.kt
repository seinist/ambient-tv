package com.ambient.tv.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.weather.WeatherError
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.data.weather.WeatherRequestException
import com.ambient.tv.data.weather.WeatherSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WeatherLocationSearch(
    val query: String = "",
    val results: List<WeatherLocation> = emptyList(),
    val loading: Boolean = false,
    val completed: Boolean = false,
    val failed: Boolean = false,
    val error: WeatherError? = null,
)

@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val repository: WeatherRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    val settings = repository.settings
    val state = repository.state
    val networkAllowed = preferences.changes().onStart { emit(Unit) }
        .map { !preferences.localNetworkOnly }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, !preferences.localNetworkOnly)
    private val mutableSearch = MutableStateFlow(WeatherLocationSearch())
    val search = mutableSearch.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings, networkAllowed) { value, allowed -> value.enabled && allowed }
                .distinctUntilChanged().collect { allowed ->
                    if (!allowed) {
                        clearSearch()
                    }
                }
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch { repository.refresh(force) }
    }

    /** A visible-page refresh belongs to the caller's lifecycle, including cancellation. */
    suspend fun refreshVisible() { repository.refresh() }

    fun changeSettings(change: (WeatherSettings) -> WeatherSettings) {
        repository.updateSettings(change(settings.value))
    }

    fun setEnabled(enabled: Boolean) {
        changeSettings { it.copy(enabled = enabled) }
        if (enabled) refresh()
    }

    fun selectLocation(id: String) {
        changeSettings { it.copy(selectedLocationId = id) }
        refresh()
    }

    fun addLocation(location: WeatherLocation) {
        val current = settings.value
        if (current.locations.none { it.id == location.id } && current.locations.size >= WeatherSettings.MAX_LOCATIONS) return
        changeSettings {
            it.copy(locations = (it.locations.filterNot { saved -> saved.id == location.id } + location), selectedLocationId = location.id)
        }
        clearSearch()
        refresh()
    }

    fun removeLocation(id: String) {
        changeSettings { current ->
            val remaining = current.locations.filterNot { it.id == id }
            current.copy(locations = remaining,
                selectedLocationId = if (current.selectedLocationId == id) remaining.firstOrNull()?.id else current.selectedLocationId)
        }
        refresh()
    }

    fun setTemperatureUnit(unit: String) {
        changeSettings { it.copy(temperatureUnit = unit) }
        refresh()
    }

    fun setWindUnit(unit: String) {
        changeSettings { it.copy(windUnit = unit) }
        refresh()
    }

    fun clearSearch() {
        searchJob?.cancel()
        mutableSearch.value = WeatherLocationSearch()
    }

    fun searchLocations(query: String, language: String) {
        searchJob?.cancel()
        val boundedQuery = query.take(100)
        val requestedQuery = boundedQuery.trim()
        val allowed = settings.value.enabled && !preferences.localNetworkOnly
        mutableSearch.value = WeatherLocationSearch(query = boundedQuery,
            loading = requestedQuery.length >= 2 && allowed)
        if (requestedQuery.length < 2 || !allowed) return
        searchJob = viewModelScope.launch {
            delay(450)
            try {
                val locations = repository.searchLocations(requestedQuery, language)
                if (mutableSearch.value.query == boundedQuery) {
                    mutableSearch.value = WeatherLocationSearch(boundedQuery, locations, completed = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (mutableSearch.value.query == boundedQuery) {
                    mutableSearch.value = WeatherLocationSearch(query = boundedQuery, completed = true, failed = true,
                        error = (error as? WeatherRequestException)?.reason ?: WeatherError.UNAVAILABLE)
                }
            }
        }
    }
}
