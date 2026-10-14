package com.ambient.tv.ui.youtube

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubePlayback
import com.ambient.tv.youtube.YouTubeItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.navigation.Destinations

data class YouTubePlayerState(
    val loading: Boolean = true,
    val playback: YouTubePlayback? = null,
    val nextVideo: YouTubeItem? = null,
    val error: String? = null,
    val liveEvent: com.ambient.tv.youtube.YouTubeLiveEvent? = null,
    val waitingForLive: Boolean = false,
    val successorsLoading: Boolean = false,
)

@HiltViewModel
class YouTubePlayerViewModel @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context, private val savedStateHandle: SavedStateHandle, private val engine: SmartTubeEngine, private val playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability, private val availability: com.ambient.tv.data.browse.SourceAvailability) : ViewModel() {
    internal fun ownsCompanionMedia(media: com.ambient.tv.companion.CompanionMedia?) = media?.kind == "youtube" && media.id == videoId && media.trailer == isTrailer
    private var videoId = requireNotNull(savedStateHandle.get<String>("videoId"))
    val isTrailer: Boolean = savedStateHandle.get<Boolean>("trailer") == true
    private val _uiState = MutableStateFlow(YouTubePlayerState())
    val uiState: StateFlow<YouTubePlayerState> = _uiState.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    private var foreground = true
    private val loadMutex = kotlinx.coroutines.sync.Mutex()

    init { load() }

    private var transitionToken: Long? = null

    fun playNext(nextId: String) {
        if (isTrailer || _uiState.value.loading || nextId == videoId || _uiState.value.nextVideo?.videoId != nextId) return
        transitionToken = BackgroundPlaybackSession.beginItemTransition()
        videoId = nextId
        savedStateHandle["videoId"] = nextId
        load()
    }

    fun retry() {
        if (transitionToken != null) transitionToken = BackgroundPlaybackSession.beginItemTransition()
        load(refresh = true)
    }

    private fun failTransition(message: String) {
        transitionToken?.let {
            BackgroundPlaybackSession.pause()
            BackgroundPlaybackSession.failSourceReplacement(it, message)
        }
    }

    fun toggleWaiting() {
        val waiting = !_uiState.value.waitingForLive
        _uiState.update { it.copy(waitingForLive = waiting, error = null) }
        if (waiting) load(refresh = true) else {
            loadJob?.cancel()
            _uiState.update { it.copy(loading = false) }
        }
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (_uiState.value.waitingForLive) {
            if (active) load(refresh = true) else {
                loadJob?.cancel()
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    private fun load(refresh: Boolean = false) {
        val videoId = videoId
        val requestToken = transitionToken
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var fresh = refresh
            var ready: YouTubePlayback? = null
            while (ready == null) {
                _uiState.update { it.copy(loading = true, error = null) }
                val cached = if (fresh || isTrailer) null else BackgroundPlaybackSession.cached(Destinations.youtubePlayer(videoId)) as? YouTubePlayback
                if (cached == null && !playbackAvailability.network()) {
                    failTransition("Source unavailable")
                    _uiState.update { it.copy(loading = false, waitingForLive = false, error = context.getString(com.ambient.tv.R.string.browse_playback_unavailable)) }
                    return@launch
                }
                // Keep even rapid retries serialized until cancelled blocking calls finish.
                val result = loadMutex.withLock {
                    suspendResult { cached ?: if (fresh) engine.refreshLivePlayback(videoId) else engine.playback(videoId) }
                }
                ready = result.getOrNull()
                if (ready != null) break
                val error = result.exceptionOrNull()
                if (error is com.ambient.tv.youtube.YouTubeLiveOfflineException) {
                    _uiState.update { it.copy(loading = false, liveEvent = error.event, error = null) }
                    if (!_uiState.value.waitingForLive || !foreground) return@launch
                    kotlinx.coroutines.delay(30_000)
                    fresh = true
                } else {
                    availability.notifyBlocked()
                    failTransition(error?.message ?: "YouTube playback failed")
                    _uiState.update { it.copy(loading = false, waitingForLive = false,
                        error = error?.message ?: "YouTube playback failed") }
                    return@launch
                }
            }
            val playback = checkNotNull(ready)
            if (requestToken != null && !BackgroundPlaybackSession.isSourceReplacement(requestToken)) return@launch
            transitionToken = null
            _uiState.value = YouTubePlayerState(false, if (isTrailer) playback.copy(resumeMs = 0) else playback,
                successorsLoading = !isTrailer)
            if (isTrailer) return@launch
            // SponsorBlock is optional; a slow/unavailable service must never hold the decoder.
            launch {
                val segments = suspendResult { engine.playbackSponsorSegments(videoId) }.getOrDefault(emptyList())
                _uiState.update { current ->
                    if (current.playback?.videoId == videoId) current.copy(playback = current.playback.copy(sponsorSegments = segments)) else current
                }
            }
            val detail = suspendResult { engine.metadata(videoId) }.getOrNull()
            val nextVideo = detail
                ?.suggestions
                ?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.firstOrNull { it.videoId != videoId && !it.isLive && !it.isUpcoming }
            _uiState.update { current ->
                val playback = current.playback
                val enriched = if (playback != null && detail != null) {
                    com.ambient.tv.youtube.applyYouTubePlaybackMetadata(playback, detail)
                } else {
                    playback
                }
                if (current.playback?.videoId == videoId) current.copy(playback = enriched, nextVideo = nextVideo, successorsLoading = false) else current
            }
        }
    }
    fun reportPosition(positionMs: Long) { val id = _uiState.value.playback?.videoId ?: return; if (!isTrailer) viewModelScope.launch { suspendResult { engine.updateHistory(id, positionMs) } } }
    override fun onCleared() {
        transitionToken?.let(BackgroundPlaybackSession::cancelSourceReplacement)
        super.onCleared()
    }

}
