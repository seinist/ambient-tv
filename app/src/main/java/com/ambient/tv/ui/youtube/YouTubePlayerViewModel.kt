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
import kotlinx.coroutines.CancellationException
import timber.log.Timber
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
    /** Channel of the playing video for the OSD's channel and subscribe buttons. */
    val channel: com.ambient.tv.youtube.YouTubeChannelInfo? = null,
    /** Previous video in this player destination's navigation, not the account's watch history. */
    val previousVideoId: String? = null,
)

@HiltViewModel
class YouTubePlayerViewModel @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context, private val savedStateHandle: SavedStateHandle, private val engine: SmartTubeEngine, private val playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability, private val availability: com.ambient.tv.data.browse.SourceAvailability, private val preferences: com.ambient.tv.data.local.AppPreferences, private val browseContext: YouTubeBrowsePlaybackContext) : ViewModel() {
    internal fun ownsCompanionMedia(media: com.ambient.tv.companion.CompanionMedia?) = media?.kind == "youtube" && media.id == videoId && media.trailer == isTrailer
    private var videoId = requireNotNull(savedStateHandle.get<String>("videoId"))
    val isTrailer: Boolean = savedStateHandle.get<Boolean>("trailer") == true
    private val browseSession: Long? = savedStateHandle.get<Long>("browseSession")?.takeIf { it >= 0L }
    private val _uiState = MutableStateFlow(YouTubePlayerState())
    val uiState: StateFlow<YouTubePlayerState> = _uiState.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    private var foreground = true
    private val loadMutex = kotlinx.coroutines.sync.Mutex()
    private val savedResumeRequest = com.ambient.tv.ui.player.SavedResumeRequest()
    private val playbackHistory = YouTubePlaybackHistory()

    init {
        load()
        viewModelScope.launch {
            engine.subscriptionChanges.collect { changes ->
                _uiState.update { current -> current.channel?.withSubscriptionChanges(changes)?.let { current.copy(channel = it) } ?: current }
            }
        }
    }

    private var transitionToken: Long? = null

    fun playNext(nextId: String) {
        val state = _uiState.value
        if (isTrailer || state.loading || state.playback?.isLive == true || state.playback?.videoId != videoId ||
            state.nextVideo?.videoId != nextId || !playbackHistory.requestNext(videoId, nextId)) return
        navigateTo(nextId)
    }

    fun playPrevious(previousId: String) {
        val state = _uiState.value
        if (isTrailer || state.loading || state.playback?.isLive == true || state.playback?.videoId != videoId ||
            state.previousVideoId != previousId || !playbackHistory.requestPrevious(videoId, previousId)) return
        navigateTo(previousId)
    }

    private fun navigateTo(nextId: String) {
        savedResumeRequest.skip()
        transitionToken = BackgroundPlaybackSession.beginItemTransition()
        videoId = nextId
        savedStateHandle["videoId"] = nextId
        load()
    }

    fun retry() {
        savedResumeRequest.skip()
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
            var reusedSession = false
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
                if (ready != null) {
                    reusedSession = cached != null
                    break
                }
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
            if (videoId != this@YouTubePlayerViewModel.videoId) return@launch
            if (requestToken != null && !BackgroundPlaybackSession.isSourceReplacement(requestToken)) return@launch
            val revisitingVideo = playbackHistory.startsAtBeginning(videoId)
            if (!isTrailer && !playbackHistory.loaded(YouTubeItem(
                videoId = playback.videoId, title = playback.title, author = playback.author,
                imageUrl = playback.imageUrl, backgroundUrl = playback.backgroundUrl,
                durationMs = playback.durationMs, isLive = playback.isLive,
            ))) return@launch
            if (!isTrailer) browseSession?.let { browseContext.played(it, videoId) }
            transitionToken = null
            val chapterStart = YouTubeStartRequest.take(videoId)
            val rewindSeconds = savedResumeRequest.takeSeconds(preferences.resumeRewindSeconds,
                eligible = !isTrailer && chapterStart == null && !reusedSession && !playback.isLive)
            _uiState.value = YouTubePlayerState(false, when {
                isTrailer -> playback.copy(resumeMs = 0, resumeResolved = true, resumeRewindSeconds = 0)
                revisitingVideo -> playback.copy(resumeMs = 0, resumeResolved = true, resumeRewindSeconds = 0)
                chapterStart != null -> playback.copy(resumeMs = chapterStart, resumeResolved = true, resumeRewindSeconds = 0)
                reusedSession -> playback.copy(resumeResolved = true, resumeRewindSeconds = 0)
                else -> com.ambient.tv.youtube.prepareYouTubeSavedResume(playback, rewindSeconds)
            },
                nextVideo = playbackHistory.nextVideo,
                previousVideoId = playbackHistory.previousVideoId,
                successorsLoading = !isTrailer && playbackHistory.nextVideo == null &&
                    browseSession?.let { browseContext.contains(it, videoId) } == true)
            if (isTrailer) return@launch
            // Next Up uses the original feed/row, including its continuation pages.
            // No metadata suggestion is ever substituted for a missing browse successor.
            if (playbackHistory.nextVideo == null && browseSession != null &&
                browseContext.contains(browseSession, videoId)) {
                launch {
                    val next = try {
                        browseContext.next(browseSession, videoId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Retain the continuation handle: an error is not an exhausted playlist.
                        Timber.w(error, "SmartTube next page failed for the originating browse row")
                        null
                    }
                    _uiState.update { state ->
                        if (state.playback?.videoId == videoId) state.copy(
                            nextVideo = playbackHistory.nextVideo ?: next,
                            successorsLoading = false,
                        ) else state
                    }
                }
            }
            // SponsorBlock is optional; a slow/unavailable service must never hold the decoder.
            launch {
                val segments = suspendResult { engine.playbackSponsorSegments(videoId) }.getOrDefault(emptyList())
                _uiState.update { current ->
                    if (current.playback?.videoId == videoId) current.copy(playback = current.playback.copy(sponsorSegments = segments)) else current
                }
            }
            val detail = suspendResult { engine.metadata(videoId) }.getOrNull()
            val channel = detail?.let(engine::channelOf)
            val signedIn = channel != null && suspendResult { engine.isSignedIn() }.getOrDefault(false)
            _uiState.update { current ->
                val playback = current.playback
                val enriched = if (playback != null && detail != null) {
                    com.ambient.tv.youtube.applyYouTubePlaybackMetadata(playback, detail)
                } else {
                    playback
                }
                if (current.playback?.videoId == videoId) current.copy(
                    playback = enriched,
                    channel = channel?.let { if (signedIn) it else it.copy(subscribed = null) },
                ) else current
            }
        }
    }
    fun toggleSubscription() {
        val channel = _uiState.value.channel?.takeIf { it.subscribed != null } ?: return
        viewModelScope.launch {
            engine.toggleSubscription(channel) { subscribed ->
                _uiState.update { current ->
                    if (current.channel?.channelId == channel.channelId) current.copy(channel = current.channel.copy(subscribed = subscribed)) else current
                }
            }
        }
    }
    fun reportPosition(positionMs: Long) { val id = _uiState.value.playback?.videoId ?: return; if (!isTrailer) viewModelScope.launch { suspendResult { engine.updateHistory(id, positionMs) } } }
    override fun onCleared() {
        transitionToken?.let(BackgroundPlaybackSession::cancelSourceReplacement)
        super.onCleared()
    }

}
