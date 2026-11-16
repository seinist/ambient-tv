package com.ambient.tv.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapRepository
import com.ambient.tv.data.weather.WeatherMapResult
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.data.weather.WeatherRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The fullscreen weather timeline is separate from video/audio playback and its service. */
internal data class WeatherPlayerRequest(
    val location: WeatherLocation,
    val provider: WeatherMapProvider,
    val viewport: WeatherMapViewport,
    val networkAllowed: Boolean,
)

internal data class WeatherPlayerData(
    val request: WeatherPlayerRequest? = null,
    val result: WeatherMapResult? = null,
    val loading: Boolean = false,
)

/** Periodic checks respect each provider's published manifest cadence. */
internal fun weatherPlayerRefreshIntervalMillis(provider: WeatherMapProvider): Long =
    if (provider.daily) 60 * 60_000L else 5 * 60_000L

/** A new source or zoom is not itself a manual force-refresh. */
internal fun weatherPlayerManualRefresh(
    previous: WeatherPlayerRequest?,
    previousSerial: Int,
    next: WeatherPlayerRequest,
    nextSerial: Int,
): Boolean = previous == next && previousSerial != nextSerial

/** The player only loads a manifest/latest frame; the Media3 resolver loads future images on demand. */
@HiltViewModel
class WeatherMapPlayerViewModel @Inject constructor(
    val mapRepository: WeatherMapRepository,
    weatherRepository: WeatherRepository,
) : ViewModel() {
    val settings = weatherRepository.settings
    private val mutable = MutableStateFlow(WeatherPlayerData())
    internal val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var refreshedSerial = -1
    private var lastAttemptCompletedAt = 0L

    internal fun present(request: WeatherPlayerRequest, refreshSerial: Int = 0) {
        val previous = mutable.value
        val manualRefresh = weatherPlayerManualRefresh(previous.request, refreshedSerial, request, refreshSerial)
        val sameRequest = previous.request == request
        val withinRefreshInterval = System.currentTimeMillis() - lastAttemptCompletedAt in
            0 until weatherPlayerRefreshIntervalMillis(request.provider)
        if (sameRequest && !manualRefresh &&
            (previous.loading || (previous.result != null && withinRefreshInterval))) return

        loadJob?.cancel()
        val serial = ++generation
        refreshedSerial = refreshSerial
        // Keep the currently rendered map and Media3 surface alive while refreshing the same
        // extent. A new provider/zoom must never display an old response as the new one.
        mutable.value = WeatherPlayerData(request, result = previous.result.takeIf { sameRequest }, loading = true)
        loadJob = viewModelScope.launch {
            try {
                val cached = mapRepository.cached(request.provider, request.viewport)
                if (generation == serial && mutable.value.request == request && cached.image != null) {
                    mutable.value = mutable.value.copy(result = cached)
                }
                val result = mapRepository.load(request.provider, request.viewport,
                    request.networkAllowed, force = manualRefresh)
                if (generation != serial || mutable.value.request != request) return@launch
                val previous = mutable.value.result
                mutable.value = mutable.value.copy(
                    result = if (result.image == null && previous?.image != null)
                        previous.copy(error = result.error) else result,
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == serial && mutable.value.request == request) {
                    mutable.value = mutable.value.copy(loading = false,
                        result = (mutable.value.result ?: WeatherMapResult()).copy(error = WeatherMapError.UNAVAILABLE))
                }
            } finally {
                if (generation == serial) {
                    lastAttemptCompletedAt = System.currentTimeMillis()
                    mutable.value = mutable.value.copy(loading = false)
                    loadJob = null
                }
            }
        }
    }

    internal fun pauseRequests() {
        generation++
        loadJob?.cancel()
        loadJob = null
        mutable.value = mutable.value.copy(loading = false)
    }

    override fun onCleared() {
        pauseRequests()
        super.onCleared()
    }
}
