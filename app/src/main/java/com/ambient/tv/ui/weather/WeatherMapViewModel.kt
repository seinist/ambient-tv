package com.ambient.tv.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapRepository
import com.ambient.tv.data.weather.WeatherMapResult
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.notifications.OperationFeedbackReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

internal data class WeatherMapRequest(
    val location: WeatherLocation,
    val kind: WeatherMapKind,
    val provider: WeatherMapProvider?,
    val viewport: WeatherMapViewport,
    val networkAllowed: Boolean,
) {
    val scope: String get() = "weather:map:${kind.name}:${provider?.id}:${location.id}:${location.latitude}:${location.longitude}:${viewport.key}"
}

internal data class WeatherMapUiState(
    val request: WeatherMapRequest? = null,
    val result: WeatherMapResult? = null,
    val loading: Boolean = false,
    val frameLoading: Boolean = false,
    val selectedFrame: Int = -1,
    val playing: Boolean = false,
)

internal data class WeatherMapPolicy(
    val networkAllowed: Boolean,
    val location: WeatherLocation?,
    val radarProvider: String,
    val satelliteProvider: String,
) {
    fun matches(request: WeatherMapRequest): Boolean {
        val placeMatches = location?.let {
            it.id == request.location.id && it.latitude == request.location.latitude && it.longitude == request.location.longitude
        } == true
        val ids = when (request.kind) {
            WeatherMapKind.RADAR -> radarProvider
            WeatherMapKind.SATELLITE -> satelliteProvider
        }
        return placeMatches && request.provider?.let {
            it in WeatherMapProvider.selectedMany(ids, request.kind)
        } == true
    }
}

internal interface WeatherMapLoader {
    suspend fun cached(provider: WeatherMapProvider, viewport: WeatherMapViewport): WeatherMapResult
    suspend fun load(provider: WeatherMapProvider, viewport: WeatherMapViewport, networkAllowed: Boolean, force: Boolean): WeatherMapResult
    suspend fun frame(provider: WeatherMapProvider, viewport: WeatherMapViewport, series: WeatherMapSeries, index: Int, networkAllowed: Boolean): WeatherMapResult
}

private class RepositoryWeatherMapLoader(private val repository: WeatherMapRepository) : WeatherMapLoader {
    override suspend fun cached(provider: WeatherMapProvider, viewport: WeatherMapViewport) = repository.cached(provider, viewport)
    override suspend fun load(provider: WeatherMapProvider, viewport: WeatherMapViewport, networkAllowed: Boolean, force: Boolean) =
        repository.load(provider, viewport, networkAllowed, force)
    override suspend fun frame(provider: WeatherMapProvider, viewport: WeatherMapViewport, series: WeatherMapSeries, index: Int, networkAllowed: Boolean) =
        repository.frame(provider, viewport, series, index, networkAllowed)
}

/** Automatic work belongs to the visible map; a deliberate refresh can finish after it is hidden. */
@HiltViewModel
class WeatherMapViewModel internal constructor(
    private val loader: WeatherMapLoader,
    val feedback: OperationFeedbackReporter,
    policy: Flow<WeatherMapPolicy> = emptyFlow(),
) : ViewModel() {
    @Inject constructor(repository: WeatherMapRepository, preferences: AppPreferences) : this(
        RepositoryWeatherMapLoader(repository), OperationFeedbackReporter(),
        preferences.changes().onStart { emit(Unit) }
            .map {
                val settings = preferences.weatherSettings
                WeatherMapPolicy(settings.enabled, settings.selectedLocation,
                    settings.radarProvider, settings.satelliteProvider)
            }.distinctUntilChanged(),
    )

    private val mutable = MutableStateFlow(WeatherMapUiState())
    internal val state = mutable.asStateFlow()
    private var visible = false
    private var loadJob: Job? = null
    private var frameJob: Job? = null
    private var playbackJob: Job? = null
    private var loadRevision = 0L
    private var frameRevision = 0L
    private var playbackRevision = 0L
    private var manualLoad = false
    private var ticket: OperationFeedbackReporter.Ticket? = null
    private var permitted = true
    private var currentPolicy: WeatherMapPolicy? = null

    init {
        viewModelScope.launch {
            policy.collect { next ->
                val previous = currentPolicy
                val request = mutable.value.request
                val permissionChanged = permitted != next.networkAllowed
                currentPolicy = next
                permitted = next.networkAllowed
                if (request != null && !next.matches(request)) {
                    // Settings can replace the selected place/source while no map is composed.
                    // Discard that old attempt, including its contextual notification ticket.
                    cancelLoad()
                    cancelFrame()
                    stopPlaying()
                    mutable.value = WeatherMapUiState(request)
                } else if (!permitted) {
                    // A manual request can outlive the composition, so observe policy here too.
                    cancelLoad()
                    cancelFrame()
                    stopPlaying()
                    mutable.value = mutable.value.copy(result = (mutable.value.result ?: WeatherMapResult())
                        .copy(error = WeatherMapError.NETWORK_DISABLED))
                } else {
                    // This local policy is known to have recovered; it is not an unverified source outage.
                    if (mutable.value.result?.error == WeatherMapError.NETWORK_DISABLED) {
                        mutable.value = mutable.value.copy(result = mutable.value.result?.copy(error = null))
                    }
                    val selectionBecameCurrent = request != null && previous?.matches(request) == false && next.matches(request)
                    if ((permissionChanged || selectionBecameCurrent) && visible && request?.networkAllowed == true) load(manual = false)
                }
            }
        }
    }

    /** Called by the composition's lifecycle observer, never inferred from focus. */
    internal fun present(request: WeatherMapRequest, active: Boolean) {
        if (mutable.value.request != request) {
            val previous = mutable.value
            cancelLoad()
            cancelFrame()
            stopPlaying()
            // A policy change may retain this exact image, but never an image from a different view.
            val retained = previous.result.takeIf { previous.request?.scope == request.scope }
            mutable.value = WeatherMapUiState(request, retained)
            visible = false
        }
        if (visible == active) return
        visible = active
        if (active) {
            if (loadJob?.isActive != true) load(manual = false)
        } else {
            stopPlaying()
            cancelFrame()
            if (!manualLoad) cancelLoad()
        }
    }

    internal fun dismiss(request: WeatherMapRequest) {
        if (mutable.value.request == request) present(request, active = false)
    }

    internal fun refresh() {
        if (!visible || !permitted || mutable.value.request?.networkAllowed != true || manualLoad) return
        load(manual = true)
    }

    internal fun stopPlaying() {
        playbackRevision++
        playbackJob?.cancel()
        playbackJob = null
        mutable.value = mutable.value.let { it.copy(playing = false,
            frameLoading = if (it.playing) false else it.frameLoading) }
    }

    internal fun step(delta: Int) {
        stopPlaying()
        val count = mutable.value.result?.series?.frames?.size ?: 0
        if (count == 0) return
        selectFrame((mutable.value.selectedFrame + delta).coerceIn(0, count - 1))
    }

    internal fun togglePlayback() {
        if (mutable.value.playing) { stopPlaying(); return }
        val initial = mutable.value
        val request = initial.request ?: return
        val provider = request.provider ?: return
        val series = initial.result?.series ?: return
        if (!visible || initial.loading || series.frames.size < 2 ||
            initial.result.error == WeatherMapError.NO_COVERAGE || currentPolicy?.matches(request) == false) return
        cancelFrame()
        val revision = ++playbackRevision
        mutable.value = mutable.value.copy(playing = true)
        playbackJob = viewModelScope.launch {
            try {
                coroutineScope {
                    if (!currentPlayback(request, revision)) return@coroutineScope
                    // Buffer only after Play, and only two frames ahead. All pending work belongs
                    // to this job, so Pause, a tab/location change or hiding the page cancels it.
                    val ready = LinkedHashMap<Int, WeatherMapResult>()
                    val pending = mutableMapOf<Int, Deferred<WeatherMapResult>>()
                    if (initial.result.image != null && initial.selectedFrame in series.frames.indices) {
                        ready[initial.selectedFrame] = initial.result
                    }
                    fun prepare(index: Int) {
                        if (index in ready || index in pending) return
                        pending[index] = async {
                            try {
                                loader.frame(provider, request.viewport, series, index, request.networkAllowed && permitted)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                WeatherMapResult(series, error = WeatherMapError.UNAVAILABLE)
                            }
                        }
                    }
                    var index = (initial.selectedFrame + 1).mod(series.frames.size)
                    prepare(index)
                    prepare((index + 1) % series.frames.size)
                    while (currentPlayback(request, revision)) {
                        val next = ready[index] ?: run {
                            prepare(index)
                            val frame = pending.remove(index)!!
                            mutable.value = mutable.value.copy(frameLoading = !frame.isCompleted)
                            frame.await()
                        }
                        if (!currentPlayback(request, revision)) break
                        showFrame(next, index)
                        mutable.value = mutable.value.copy(frameLoading = false)
                        if (next.image == null || next.error != null) {
                            mutable.value = mutable.value.copy(playing = false)
                            break
                        }
                        ready[index] = next
                        // Published series contain at most thirteen frames; also bound older caches.
                        if (ready.size > 13) ready.remove(ready.keys.first())
                        index = (index + 1) % series.frames.size
                        prepare(index)
                        prepare((index + 1) % series.frames.size)
                        // The first frame starts as soon as it is ready. Subsequent frames keep
                        // their cadence while the next images load, and full loops reuse pixels.
                        delay(1100)
                    }
                    pending.values.forEach { it.cancel() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (playbackRevision == revision) {
                    mutable.value = mutable.value.copy(playing = false, frameLoading = false)
                    playbackJob = null
                }
            }
        }
    }

    private fun currentPlayback(request: WeatherMapRequest, revision: Long): Boolean =
        visible && mutable.value.playing && mutable.value.request == request && playbackRevision == revision

    private fun load(manual: Boolean) {
        val request = mutable.value.request ?: return
        val provider = request.provider ?: return
        if (currentPolicy?.matches(request) == false) return
        cancelLoad()
        cancelFrame()
        stopPlaying()
        val revision = ++loadRevision
        val attempt = if (manual) feedback.begin("${request.scope}:refresh") else null
        ticket = attempt
        manualLoad = manual
        mutable.value = mutable.value.copy(loading = true)
        loadJob = viewModelScope.launch {
            try {
                val cached = loader.cached(provider, request.viewport)
                if (currentLoad(request, revision) && mutable.value.result?.image == null && cached.image != null) {
                    mutable.value = mutable.value.copy(result = cached)
                }
                if (!currentLoad(request, revision)) return@launch
                // The force flag belongs only to this explicit attempt, never to a later resume/pan.
                val next = loader.load(provider, request.viewport, request.networkAllowed && permitted, force = manual)
                if (!currentLoad(request, revision)) return@launch
                finishLoad(request, next, manual, attempt)
            } catch (cancelled: CancellationException) {
                attempt?.let(feedback::cancel)
                throw cancelled
            } catch (_: Exception) {
                if (currentLoad(request, revision)) {
                    finishLoad(request, WeatherMapResult(error = WeatherMapError.UNAVAILABLE), manual, attempt)
                }
            } finally {
                if (currentLoad(request, revision)) {
                    mutable.value = mutable.value.copy(loading = false)
                    loadJob = null
                    manualLoad = false
                    ticket = null
                }
            }
        }
    }

    private fun finishLoad(request: WeatherMapRequest, next: WeatherMapResult, manual: Boolean, attempt: OperationFeedbackReporter.Ticket?) {
        val previous = mutable.value.result
        val image = next.image
        val error = next.error
        val shown = when {
            image != null -> next.copy(error = error ?: previous?.error.takeIf {
                !manual && image.fromCache && it != WeatherMapError.NETWORK_DISABLED
            })
            previous?.image != null -> previous.copy(error = error)
            else -> next
        }
        val index = shown.image?.let { image -> shown.series?.frames?.indexOfFirst { it.epochMillis == image.frame.epochMillis } } ?: -1
        mutable.value = mutable.value.copy(result = shown, selectedFrame = index, playing = false)
        if (attempt != null) {
            if (error == null) feedback.cancel(attempt)
            else feedback.complete(attempt, weatherMapFeedback(error, request, manual))
        }
    }

    private fun selectFrame(index: Int) {
        val current = mutable.value
        val request = current.request ?: return
        val provider = request.provider ?: return
        if (currentPolicy?.matches(request) == false) return
        val series = current.result?.series ?: return
        val target = series.frames.getOrNull(index) ?: return
        if (!visible || current.loading || current.result.error == WeatherMapError.NO_COVERAGE) return
        if (current.result.image?.frame?.epochMillis == target.epochMillis) {
            mutable.value = current.copy(selectedFrame = index)
            return
        }
        cancelFrame()
        val revision = ++frameRevision
        mutable.value = mutable.value.copy(frameLoading = true)
        frameJob = viewModelScope.launch {
            try {
                val next = loader.frame(provider, request.viewport, series, index, request.networkAllowed && permitted)
                if (mutable.value.request != request || frameRevision != revision || !visible) return@launch
                showFrame(next, index)
                if (next.error != null) stopPlaying()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mutable.value.request == request && frameRevision == revision && visible) {
                    mutable.value = mutable.value.copy(result = mutable.value.result?.copy(error = WeatherMapError.UNAVAILABLE))
                    stopPlaying()
                }
            } finally {
                if (mutable.value.request == request && frameRevision == revision) {
                    mutable.value = mutable.value.copy(frameLoading = false)
                    frameJob = null
                }
            }
        }
    }

    private fun showFrame(next: WeatherMapResult, index: Int) {
        val previous = mutable.value.result
        // A cached frame cannot prove that a failed source recovered; retain the source error.
        mutable.value = if (next.image != null) mutable.value.copy(
            result = next.copy(error = next.error ?: previous?.error), selectedFrame = index,
        ) else mutable.value.copy(result = (previous ?: next).copy(error = next.error ?: WeatherMapError.UNAVAILABLE))
    }

    private fun currentLoad(request: WeatherMapRequest, revision: Long) =
        mutable.value.request == request && loadRevision == revision

    private fun cancelLoad() {
        loadRevision++
        loadJob?.cancel()
        loadJob = null
        ticket?.let(feedback::cancel)
        ticket = null
        manualLoad = false
        mutable.value = mutable.value.copy(loading = false)
    }

    private fun cancelFrame() {
        frameRevision++
        frameJob?.cancel()
        frameJob = null
        mutable.value = mutable.value.copy(frameLoading = false)
    }

    override fun onCleared() {
        cancelLoad()
        cancelFrame()
        stopPlaying()
    }
}
