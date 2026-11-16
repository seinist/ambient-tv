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

    internal fun present(request: WeatherPlayerRequest, refreshSerial: Int = 0) {
        if (mutable.value.request == request && refreshedSerial == refreshSerial &&
            (mutable.value.loading || mutable.value.result != null)) return
        loadJob?.cancel()
        val serial = ++generation
        refreshedSerial = refreshSerial
        mutable.value = WeatherPlayerData(request, loading = true)
        loadJob = viewModelScope.launch {
            try {
                val cached = mapRepository.cached(request.provider, request.viewport)
                if (generation == serial && mutable.value.request == request && cached.image != null) {
                    mutable.value = mutable.value.copy(result = cached)
                }
                val result = mapRepository.load(request.provider, request.viewport,
                    request.networkAllowed, force = refreshSerial > 0)
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
