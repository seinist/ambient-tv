package com.ambient.tv.ui.youtube

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** Always separate from the YouTube title, author and publication date. */
    val origin: YouTubeDetailOrigin? = null,
)

@HiltViewModel
class YouTubeDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val engine: SmartTubeEngine,
    private val cache: com.ambient.tv.youtube.YouTubeContentCache,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val telegramRepository: TelegramRepository,
) : ViewModel() {
    val videoId = requireNotNull(savedStateHandle.get<String>("videoId"))
    private val _uiState = MutableStateFlow(YouTubeDetailState(
        offline = !availability.hasNetwork(),
        origin = youtubeDetailOrigin(savedStateHandle.get<Long>("originChatId"), savedStateHandle.get<Int>("originMessageId")),
    ))
    val uiState: StateFlow<YouTubeDetailState> = _uiState.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    private var originJob: kotlinx.coroutines.Job? = null
    init {
        loadOrigin()
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
    fun retryOffline() {
        if ((_uiState.value.offline || _uiState.value.error != null) && availability.hasNetwork() && loadJob?.isActive != true) refresh()
    }
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
    fun refresh() {
        loadOrigin()
        load(forceRefresh = true)
    }

    private fun loadOrigin() {
        val origin = _uiState.value.origin ?: return
        if (origin.channel != null || originJob?.isActive == true) return
        originJob = viewModelScope.launch {
            updateYouTubeDetailOrigin(origin, _uiState, telegramRepository::media)
        }
    }

    private fun load(forceRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = _uiState.value.metadata == null, error = null)
            // A failed disk read must not prevent fetching the video itself.
            suspendResult { cache.restore() }
            val revision = cache.revision()
            if (_uiState.value.metadata == null) cache.metadata(videoId)?.takeIf { it.videoId == videoId }?.let { cached ->
                _uiState.update { it.copy(loading = false, metadata = cached, offline = !availability.hasNetwork()) }
            }
            suspendResult { engine.metadata(videoId, forceRefresh = forceRefresh) }.onSuccess { metadata ->
                if (cache.revision() == revision) {
                    cache.putMetadata(metadata, revision)
                    // Show metadata before optional account/channel lookups finish. Copy the current
                    // state so an independently completed source-post request is retained.
                    _uiState.update { it.copy(
                        loading = false, metadata = metadata, offline = !availability.hasNetwork(), error = null,
                        likeStatus = engine.likeOverride(metadata.videoId) ?: metadata.likeStatus,
                    ) }
                    val signedIn = suspendResult { engine.isSignedIn() }.getOrDefault(false)
                    // Signed out the subscription is unknown: the page offers the channel, not the toggle.
                    val channel = suspendResult { engine.channelOf(metadata) }.getOrNull()
                        ?.let { if (signedIn) it else it.copy(subscribed = null) }
                    if (cache.revision() == revision) {
                        _uiState.update { it.copy(channel = channel, signedIn = signedIn) }
                    }
                } else {
                    _uiState.update { it.copy(loading = false) }
                }
            }.onFailure { failure ->
                // An unavailable description is not proof that the stream cannot be played.
                _uiState.update { it.copy(loading = false, offline = !availability.hasNetwork(), error = failure.message ?: "") }
            }
        }
    }
}
