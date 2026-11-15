package com.ambient.tv.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.weather.WeatherError
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.data.weather.WeatherRequestException
import com.ambient.tv.data.weather.WeatherSettings
import com.ambient.tv.data.weather.weatherCacheKey
import com.ambient.tv.notifications.FeedbackDelivery
import com.ambient.tv.notifications.OperationFeedbackReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
) : ViewModel() {
    val settings = repository.settings
    val state = repository.state
    val feedbackReporter = OperationFeedbackReporter()
    private val mutableSearch = MutableStateFlow(WeatherLocationSearch())
    val search = mutableSearch.asStateFlow()
    private var searchJob: Job? = null
    private var searchTicket: OperationFeedbackReporter.Ticket? = null
    private var searchGeneration = 0L
    private var forecastRetryJob: Job? = null
    private var forecastTicket: OperationFeedbackReporter.Ticket? = null

    init {
        viewModelScope.launch {
            settings.map { it.enabled }
                .distinctUntilChanged().collect { allowed ->
                    if (!allowed) {
                        clearSearch()
                        cancelForecastRetry()
                    }
                }
        }
    }

    /** Only an explicit Retry may report a failure after its inline scope has disappeared. */
    fun retryForecast() {
        val selected = settings.value
        val place = selected.selectedLocation ?: return
        if (!selected.enabled) return
        val identity = weatherCacheKey(place, selected)
        cancelForecastRetry()
        val ticket = feedbackReporter.begin(WEATHER_FORECAST_SCOPE)
        forecastTicket = ticket
        forecastRetryJob = viewModelScope.launch {
            try {
                val result = repository.refresh(force = true)
                val current = settings.value
                val stillSelected = current.selectedLocation?.let { weatherCacheKey(it, current) } == identity
                val error = result?.error
                if (current.enabled && stillSelected && error != null && error != WeatherError.DISABLED) {
                    feedbackReporter.complete(ticket, weatherForecastFeedback(error, result.data != null,
                        subject = place.name, delivery = FeedbackDelivery.ACTION))
                }
            } finally {
                feedbackReporter.cancel(ticket)
                if (forecastTicket == ticket) forecastTicket = null
            }
        }
    }

    private fun cancelForecastRetry() {
        forecastTicket?.let(feedbackReporter::cancel)
        forecastTicket = null
        forecastRetryJob?.cancel()
        forecastRetryJob = null
    }

    /** A visible-page refresh belongs to the caller's lifecycle, including cancellation. */
    suspend fun refreshVisible() { repository.refresh() }

    // Changing settings only changes selection/policy. Visible header, screensaver or forecast
    // consumers observe these settings and own any refresh, including its cancellation.
    fun changeSettings(change: (WeatherSettings) -> WeatherSettings) {
        val current = settings.value
        val next = change(current).normalized()
        val previousKey = current.selectedLocation?.let { weatherCacheKey(it, current) }
        val nextKey = next.selectedLocation?.let { weatherCacheKey(it, next) }
        if (!next.enabled || previousKey != nextKey) cancelForecastRetry()
        if (!next.enabled) clearSearch()
        repository.updateSettings(next)
    }

    fun setEnabled(enabled: Boolean) {
        changeSettings { it.copy(enabled = enabled) }
    }

    fun selectLocation(id: String) {
        changeSettings { it.copy(selectedLocationId = id) }
    }

    fun addLocation(location: WeatherLocation) {
        val current = settings.value
        if (current.locations.none { it.id == location.id } && current.locations.size >= WeatherSettings.MAX_LOCATIONS) return
        changeSettings {
            it.copy(locations = (it.locations.filterNot { saved -> saved.id == location.id } + location), selectedLocationId = location.id)
        }
        clearSearch()
    }

    fun removeLocation(id: String) {
        changeSettings { current ->
            val remaining = current.locations.filterNot { it.id == id }
            current.copy(locations = remaining,
                selectedLocationId = if (current.selectedLocationId == id) remaining.firstOrNull()?.id else current.selectedLocationId)
        }
    }

    fun setTemperatureUnit(unit: String) {
        changeSettings { it.copy(temperatureUnit = unit) }
    }

    fun setWindUnit(unit: String) {
        changeSettings { it.copy(windUnit = unit) }
    }

    fun clearSearch() {
        searchGeneration++
        searchTicket?.let(feedbackReporter::cancel)
        searchTicket = null
        searchJob?.cancel()
        mutableSearch.value = WeatherLocationSearch()
    }

    fun searchLocations(query: String, language: String) {
        searchLocations(query, language, manualRetry = false)
    }

    fun retrySearch(language: String) {
        searchLocations(mutableSearch.value.query, language, manualRetry = true)
    }

    private fun searchLocations(query: String, language: String, manualRetry: Boolean) {
        searchJob?.cancel()
        searchTicket?.let(feedbackReporter::cancel)
        searchTicket = null
        val generation = ++searchGeneration
        val boundedQuery = query.take(100)
        val requestedQuery = boundedQuery.trim()
        val allowed = settings.value.enabled
        val previous = mutableSearch.value
        val loading = requestedQuery.length >= 2 && allowed
        mutableSearch.value = if (manualRetry && previous.query == boundedQuery && previous.failed)
            previous.copy(loading = loading, completed = false)
        else WeatherLocationSearch(query = boundedQuery, loading = loading)
        if (requestedQuery.length < 2 || !allowed) return
        val ticket = if (manualRetry) feedbackReporter.begin(WEATHER_SEARCH_SCOPE) else null
        searchTicket = ticket
        searchJob = viewModelScope.launch {
            try {
                if (!manualRetry) delay(450)
                val locations = repository.searchLocations(requestedQuery, language)
                if (searchGeneration == generation && settings.value.enabled) {
                    mutableSearch.value = WeatherLocationSearch(boundedQuery, locations, completed = true)
                }
            } catch (cancelled: CancellationException) {
                if (searchGeneration == generation) {
                    mutableSearch.value = mutableSearch.value.copy(loading = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (searchGeneration == generation && settings.value.enabled) {
                    val reason = (error as? WeatherRequestException)?.reason ?: WeatherError.UNAVAILABLE
                    mutableSearch.value = WeatherLocationSearch(query = boundedQuery, completed = true, failed = true,
                        error = reason)
                    ticket?.let { feedbackReporter.complete(it, weatherSearchFeedback(reason,
                        subject = requestedQuery, delivery = FeedbackDelivery.ACTION)) }
                }
            } finally {
                ticket?.let(feedbackReporter::cancel)
                if (searchTicket == ticket) searchTicket = null
            }
        }
    }

    override fun onCleared() {
        cancelForecastRetry()
        clearSearch()
        super.onCleared()
    }
}
