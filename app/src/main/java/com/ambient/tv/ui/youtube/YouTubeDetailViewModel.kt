package com.ambient.tv.ui.youtube

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class YouTubeDetailState(
    val loading: Boolean = true,
    val metadata: YouTubeMetadata? = null,
    val error: String? = null,
    val offline: Boolean = false,
    val channel: com.ambient.tv.youtube.YouTubeChannelInfo? = null,
    /** Signed in: the page offers the rating buttons. */
    val signedIn: Boolean = false,
    val likeStatus: com.ambient.tv.youtube.YouTubeLikeStatus = com.ambient.tv.youtube.YouTubeLikeStatus.NONE,
)

@HiltViewModel
class YouTubeDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val engine: SmartTubeEngine,
    private val cache: com.ambient.tv.youtube.YouTubeContentCache,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
) : ViewModel() {
    private val videoId = requireNotNull(savedStateHandle.get<String>("videoId"))
    private val _uiState = MutableStateFlow(YouTubeDetailState())
    val uiState: StateFlow<YouTubeDetailState> = _uiState.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    init {
        load()
        viewModelScope.launch {
            engine.subscriptionChanges.collect { changes ->
                val channel = _uiState.value.channel ?: return@collect
                val updated = channel.withSubscriptionChanges(changes)
                if (updated != channel) _uiState.value = _uiState.value.copy(channel = updated)
            }
        }
        viewModelScope.launch {
            availability.changes.collect {
                val offline = !availability.hasNetwork()
                val previous = _uiState.value
                _uiState.value = previous.copy(offline = offline)
            }
        }
    }
    fun retryOffline() { if (_uiState.value.offline && availability.hasNetwork() && loadJob?.isActive != true) refresh() }
    private var ratingJob: kotlinx.coroutines.Job? = null

    /** Like/dislike like SmartTube: pressing the active rating again removes it. */
    fun rate(rating: com.ambient.tv.youtube.YouTubeLikeStatus) {
        val state = _uiState.value
        if (!state.signedIn || ratingJob?.isActive == true) return
        val from = state.likeStatus
        val to = if (from == rating) com.ambient.tv.youtube.YouTubeLikeStatus.NONE else rating
        _uiState.value = state.copy(likeStatus = to)
        ratingJob = viewModelScope.launch {
            suspendResult { engine.setLikeStatus(videoId, from, to) }.onFailure {
                _uiState.value = _uiState.value.copy(likeStatus = from)
                com.ambient.tv.notifications.notifyResult("youtube:rating:$videoId", "youtube", state.metadata?.title, success = false,
                    kind = com.ambient.tv.notifications.NoticeKind.LIBRARY, failureMessage = com.ambient.tv.R.string.youtube_rating_failed)
            }
        }
    }

    fun toggleSubscription() {
        val channel = _uiState.value.channel?.takeIf { it.subscribed != null } ?: return
        viewModelScope.launch {
            engine.toggleSubscription(channel) { subscribed ->
                _uiState.value = _uiState.value.copy(channel = _uiState.value.channel?.copy(subscribed = subscribed))
            }
        }
    }
    fun load() = load(forceRefresh = false)
    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = _uiState.value.metadata == null, error = null)
            cache.restore()
            val revision = cache.revision()
            if (_uiState.value.metadata == null) cache.metadata(videoId)?.let {
                _uiState.value = YouTubeDetailState(false, it, offline = !availability.hasNetwork())
            }
            suspendResult { engine.metadata(videoId, forceRefresh = forceRefresh) }.onSuccess {
                if (cache.revision() == revision) {
                    cache.putMetadata(it, revision)
                    val signedIn = suspendResult { engine.isSignedIn() }.getOrDefault(false)
                    // Signed out the subscription is unknown: the page offers the channel, not the toggle.
                    val channel = engine.channelOf(it)?.let { channel -> if (signedIn) channel else channel.copy(subscribed = null) }
                    _uiState.value = YouTubeDetailState(false, it, channel = channel, signedIn = signedIn,
                        likeStatus = engine.likeOverride(it.videoId) ?: it.likeStatus)
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(loading = false, offline = true, error = it.message)
            }
        }
    }
}
