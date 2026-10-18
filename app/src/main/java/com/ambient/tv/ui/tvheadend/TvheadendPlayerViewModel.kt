package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.initializeTimeshiftStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

import androidx.lifecycle.SavedStateHandle
import com.ambient.tv.livetv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ambient.tv.util.suspendResult
import androidx.lifecycle.ViewModel
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import androidx.lifecycle.viewModelScope
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhPlaybackSource
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.connectivity.PlaybackServerRecovery
import com.ambient.tv.connectivity.PlaybackServerUnreachable
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.navigation.Destinations

enum class TvheadendPlaybackErrorKind {
    SERVER_UNAVAILABLE,
    CHANNEL_UNAVAILABLE,
    CHANNEL_NOT_FOUND,
    RECORDING_UNAVAILABLE,
    GENERIC,
}

sealed interface TvheadendPlayerState {
    data object Loading : TvheadendPlayerState
    data object Stopped : TvheadendPlayerState
    data class Ready(
        val source: TvhPlaybackSource,
        val channel: TvhChannel? = null,
        val nowEvent: TvhEpgEvent? = null,
        val nextEvent: TvhEpgEvent? = null,
        val playingRecording: LiveTvRecording? = null,
        val artworkUrl: String? = null,
        val artworkFallbackUrls: List<String> = emptyList(),
        val playbackBackendKey: String = "auto",
        val sourceWidth: Int? = null,
        val sourceHeight: Int? = null,
        val sourceHdrStandard: com.ambient.tv.ui.player.HdrStandard? = null,
        val manualRendererKey: String? = null,
        val recoveryRendererKey: String? = null,
        val rendererOrigin: LiveRendererOrigin = LiveRendererOrigin.DEFAULT,
        val audioVisualizerModeKey: String = "projectm",
        val audioPassthroughEnabled: Boolean = false,
        val projectmPresetIntervalSeconds: Int = 60,
        val projectmTransitionSeconds: Int = 2,
        val projectmQualityKey: String = "balanced",
        val projectmPresetCategoryKey: String = "all",
    ) : TvheadendPlayerState
    data class Error(
        val message: String,
        val kind: TvheadendPlaybackErrorKind = TvheadendPlaybackErrorKind.GENERIC,
        val channelName: String? = null,
    ) : TvheadendPlayerState
}

@HiltViewModel
class TvheadendPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    registry: LiveTvRegistry,
    private val preferences: AppPreferences,
    private val playbackServerRecovery: PlaybackServerRecovery,
    private val companionMapper: com.ambient.tv.companion.CompanionMediaMapper,
    private val playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability,
) : ViewModel() {
    private val repository = registry.repository(savedStateHandle.get<String>("sourceId"))
    val sourceSystem = repository.source.system
    private val sourceId = repository.source.id
    private val historyScope = if (sourceSystem == "tvheadend") preferences.tvheadendHistoryScope else sourceId
    private var ownedSession: LiveTvPlaybackSession? = null
    private var pendingSourceReplacement: Long? = null
    private val streamMutex = Mutex()
    private var loadingJob: Job? = null
    private val kind: String = savedStateHandle["kind"] ?: "channel"
    internal val startsWithLiveChannel: Boolean get() = kind == "channel"
    private val initialId: String = savedStateHandle["id"] ?: ""
    private val companionSource = runCatching {
        companionMapper.fromRoute(Destinations.tvhPlayer(kind, initialId, sourceId)).source
    }.getOrNull()
    internal fun ownsCompanionMedia(media: com.ambient.tv.companion.CompanionMedia?) = media?.kind == "live:$kind" && media.source == companionSource && media.id == (if (kind == "channel") currentChannelUuid else initialId)
    private var currentChannelUuid: String = initialId
    private var channels: List<TvhChannel> = emptyList()
    /** Started from Live TV or the Guide: zapping stays within that view's filtered channels. */
    private val zapOrder: List<String>? =
        if (kind != "channel") null else com.ambient.tv.livetv.LiveTvZapOrder.forPlayer(sourceId, initialId,
            reopened = BackgroundPlaybackSession.cached(Destinations.tvhPlayer(kind, initialId, sourceId)) != null)
    var changingChannel by androidx.compose.runtime.mutableStateOf(false)
        private set
    /** Counts channel changes; the player shows its OSD with the new channel's info for each. */
    var zapVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private var fallbackAttempted = false
    private val localFallbackBackends = mutableSetOf<String>()
    private var videoFailureConfirmed = false
    fun metadata(state: TvheadendPlayerState.Ready) = PlaybackMetadata(
        route = Destinations.tvhPlayer(kind, state.channel?.uuid ?: initialId, sourceId),
        title = state.source.title, subtitle = state.source.subtitle.orEmpty(),
        source = sourceSystem, liveTvSession = state.source.session, mediaId = state.channel?.uuid ?: initialId,
        companionMedia = companionSource?.let { com.ambient.tv.companion.CompanionMedia("live:$kind", state.channel?.uuid ?: initialId, it) },
        artworkUrl = state.artworkUrl ?: state.nowEvent?.imageUrl ?: state.channel?.iconUrl,
        artworkHeaders = repository.authHeaders(),
        artworkFallbackUrls = state.artworkFallbackUrls,
        artworkAspectRatio = if (state.artworkUrl.isNullOrBlank() && state.nowEvent?.imageUrl.isNullOrBlank() && !state.channel?.iconUrl.isNullOrBlank()) 1f else 16f / 9f,
        isLive = state.source.isLive,
        sourceWidth = state.sourceWidth,
        sourceHeight = state.sourceHeight,
        handheldHdrStandard = state.sourceHdrStandard,
        historyScope = historyScope,
        liveTvSourceId = sourceId,
        liveRendererOrigin = state.rendererOrigin.takeIf { state.source.isLive },
        liveProgramStartMs = state.nowEvent?.startEpochSeconds?.times(1_000L),
        liveProgramEndMs = state.nowEvent?.stopEpochSeconds?.times(1_000L),
        durationMs = state.source.durationMs ?: 0, resumeState = state)

    private val _uiState = MutableStateFlow<TvheadendPlayerState>(TvheadendPlayerState.Loading)
    // Declared before init: init registers it with the timeshift buffer.
    private val timeshiftSeekHandler: (Long?) -> Unit = { seekTimeshift(it) }
    val uiState: StateFlow<TvheadendPlayerState> = _uiState.asStateFlow()

    private val _recordingControl = MutableStateFlow(LiveRecordingControl())
    internal val recordingControl = _recordingControl.asStateFlow()
    private var recordingRequestBusy = false

    internal val directRecordingEnabled get() = preferences.liveDirectRecordEnabled

    internal fun applyTimeshiftSetting() {
        val ready = _uiState.value as? TvheadendPlayerState.Ready ?: return
        val channel = ready.channel ?: return
        if (!ready.source.isLive || changingChannel || ready.playingRecording != null) return
        changingChannel = true
        viewModelScope.launch {
            try {
                val source = timeshifted(acquireStream { repository.liveStream(channel) }, channel.uuid)
                val latest = _uiState.value as? TvheadendPlayerState.Ready
                if (latest?.channel?.uuid == channel.uuid) _uiState.value = latest.copy(source = source)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = TvheadendPlayerState.Error(context.getString(com.ambient.tv.R.string.live_timeshift_failed))
            } finally {
                changingChannel = false
            }
        }
    }

    private fun recordCurrentProgramme() {
        val ready = _uiState.value as? TvheadendPlayerState.Ready ?: return
        val event = ready.nowEvent ?: return
        val control = _recordingControl.value
        if (!preferences.liveDirectRecordEnabled || !ready.source.isLive || !control.available || control.eventId != event.eventId ||
            recordingRequestBusy || !event.isLive(System.currentTimeMillis() / 1000)) return
        val stopping = control.scheduled
        recordingRequestBusy = true
        _recordingControl.value = control.copy(busy = true)
        viewModelScope.launch {
            try {
                val result = suspendResult { toggleCurrentProgrammeRecording(repository, event, stopping) }
                if (_recordingControl.value.eventId == event.eventId) {
                    _recordingControl.value = _recordingControl.value.copy(busy = false,
                        scheduled = if (result.isSuccess) !stopping else _recordingControl.value.scheduled)
                }
                android.widget.Toast.makeText(context, context.getString(
                    if (result.isSuccess) {
                        if (stopping) com.ambient.tv.R.string.player_record_stopped else com.ambient.tv.R.string.tvh_recording_started
                    } else if (stopping) com.ambient.tv.R.string.player_record_stop_failed
                    else com.ambient.tv.R.string.player_record_failed), android.widget.Toast.LENGTH_LONG).show()
            } finally {
                recordingRequestBusy = false
            }
        }
    }

    private val recordingClient by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .also { com.ambient.tv.network.CredentialRedirectGuard.install(it) }.build()
    }

    init {
        com.ambient.tv.livetv.LiveTimeshift.seekHandler = timeshiftSeekHandler
        load()
        if (sourceSystem == "tvheadend" && kind == "channel") viewModelScope.launch {
            uiState.map { (it as? TvheadendPlayerState.Ready)?.let { state -> state.channel?.uuid to state.nowEvent } }
                .distinctUntilChanged().collectLatest { selection ->
                    val channelId = selection?.first ?: return@collectLatest
                    val event = selection.second ?: return@collectLatest
                    var attempts = 0
                    while (isActive) {
                        val ready = _uiState.value as? TvheadendPlayerState.Ready ?: return@collectLatest
                        if (ready.playingRecording != null) return@collectLatest
                        val entry = suspendResult { findProgrammeRecording(event, repository.upcomingRecordings(true)) }.getOrNull()
                        if (entry != null && entry.scheduleStatus == "recording") {
                            val result = suspendResult { playGrowingRecording(ready, entry) }
                            if (result.getOrNull() == true) return@collectLatest
                            if (++attempts >= 20 || result.exceptionOrNull() is UnsupportedOperationException) {
                                android.widget.Toast.makeText(context, context.getString(com.ambient.tv.R.string.live_recording_playback_failed),
                                    android.widget.Toast.LENGTH_LONG).show()
                                return@collectLatest
                            }
                        }
                        if (currentChannelUuid != channelId) return@collectLatest
                        delay(3_000L)
                    }
                }
        }
        viewModelScope.launch {
            uiState.map { state -> (state as? TvheadendPlayerState.Ready)
                ?.takeIf { it.source.isLive }?.nowEvent }
                .distinctUntilChanged().collectLatest { event ->
                    _recordingControl.value = LiveRecordingControl()
                    if (event == null) return@collectLatest
                    while (true) {
                        val previous = _recordingControl.value
                        val capabilities = suspendResult { repository.capabilities() }.getOrNull()
                        val available = capabilities != null && canRecordProgramme(capabilities, event, System.currentTimeMillis() / 1000)
                        val timers = if (available) suspendResult { repository.upcomingRecordings(true) }.getOrNull() else null
                        val scheduled = timers?.let { findProgrammeRecording(event, it) != null }
                            ?: previous.scheduled
                        // A request may complete while the backend refresh is in flight.
                        if (!recordingRequestBusy && _recordingControl.value === previous) {
                            _recordingControl.value = LiveRecordingControl(event.eventId, available, scheduled,
                                false, ::recordCurrentProgramme)
                        }
                        delay(30_000L)
                    }
                }
        }
        viewModelScope.launch {
            uiState.map { (it as? TvheadendPlayerState.Ready)?.channel?.uuid }
                .distinctUntilChanged().collectLatest { channelId ->
                    if (channelId == null) return@collectLatest
                    while (true) {
                        val (nowEvent, nextEvent) = loadProgramInfo(channelId)
                        val latest = _uiState.value as? TvheadendPlayerState.Ready
                        if (latest?.channel?.uuid == channelId && latest.playingRecording == null) {
                            val artwork = tvhPanelArtwork(nowEvent?.imageUrl, latest.channel.iconUrl)
                            _uiState.value = latest.copy(nowEvent = nowEvent, nextEvent = nextEvent,
                                artworkUrl = artwork.first, artworkFallbackUrls = artwork.second)
                        }
                        delay(60_000L)
                    }
                }
        }
    }

    private fun load() {
        (BackgroundPlaybackSession.cached(Destinations.tvhPlayer(kind, currentChannelUuid, sourceId)) as? TvheadendPlayerState.Ready)?.let {
            _uiState.value = it.copy(playbackBackendKey = BackgroundPlaybackSession.snapshot.value.backend)
            viewModelScope.launch { channels = runCatching { repository.channels() }.getOrDefault(emptyList()) }
            return
        }
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch {
            if (!playbackAvailability.network()) {
                _uiState.value = TvheadendPlayerState.Error(context.getString(com.ambient.tv.R.string.browse_playback_unavailable))
                return@launch
            }
            _uiState.value = TvheadendPlayerState.Loading
            fallbackAttempted = false
            localFallbackBackends.clear()
            videoFailureConfirmed = false
            com.ambient.tv.ui.player.AudioCodecRejects.clear()
            try {
                when (kind) {
                    "recording" -> {
                        val entry = (repository.finishedRecordings() + repository.failedRecordings()).firstOrNull { it.uuid == initialId }
                        if (entry == null) {
                            _uiState.value = TvheadendPlayerState.Error(
                                message = context.getString(com.ambient.tv.R.string.tvh_recording_not_found),
                                kind = TvheadendPlaybackErrorKind.RECORDING_UNAVAILABLE,
                            )
                        } else {
                            val artwork = tvhPanelArtwork(entry.fanartUrl, entry.imageUrl)
                            _uiState.value = TvheadendPlayerState.Ready(
                                acquireStream { repository.recordingStream(entry) },
                                artworkUrl = artwork.first,
                                artworkFallbackUrls = artwork.second,
                                playbackBackendKey = com.ambient.tv.ui.player.savedPlaybackRenderer(
                                    preferences.tvheadendPlaybackBackendKey,
                                    preferences.titleVideoSettings["$sourceSystem:$initialId"], null,
                                    com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable),
                                audioVisualizerModeKey = preferences.audioVisualizerModeKey,
                                audioPassthroughEnabled = preferences.mediaAudioPassthroughEnabled,
                                projectmPresetIntervalSeconds = preferences.projectmPresetIntervalSeconds,
                                projectmTransitionSeconds = preferences.projectmTransitionSeconds,
                                projectmQualityKey = preferences.projectmQualityKey,
                                projectmPresetCategoryKey = preferences.projectmPresetCategoryKey,
                            ).withSelectedRenderer(restorePosition = false)
                        }
                    }
                    else -> {
                        channels = repository.channels()
                        val channel = channels.firstOrNull { it.uuid == currentChannelUuid }
                        if (channel == null) {
                            _uiState.value = TvheadendPlayerState.Error(
                                message = context.getString(com.ambient.tv.R.string.tvh_channel_not_found),
                                kind = TvheadendPlaybackErrorKind.CHANNEL_NOT_FOUND,
                            )
                        } else {
                            val artwork = tvhPanelArtwork(null, channel.iconUrl)
                            _uiState.value = TvheadendPlayerState.Ready(
                                source = timeshifted(acquireStream { repository.liveStream(channel) }, channel.uuid),
                                channel = channel,
                                artworkUrl = artwork.first,
                                artworkFallbackUrls = artwork.second,
                                playbackBackendKey = preferences.tvheadendPlaybackBackendKey,
                                audioVisualizerModeKey = preferences.audioVisualizerModeKey,
                                audioPassthroughEnabled = preferences.mediaAudioPassthroughEnabled,
                                projectmPresetIntervalSeconds = preferences.projectmPresetIntervalSeconds,
                                projectmTransitionSeconds = preferences.projectmTransitionSeconds,
                                projectmQualityKey = preferences.projectmQualityKey,
                                projectmPresetCategoryKey = preferences.projectmPresetCategoryKey,
                            ).withSelectedRenderer(restorePosition = false)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error;
                val channelName = channels.firstOrNull { it.uuid == currentChannelUuid }?.name
                _uiState.value = TvheadendPlayerState.Error(
                    message = error.message ?: context.getString(com.ambient.tv.R.string.tvh_playback_failed),
                    kind = if (kind == "channel") TvheadendPlaybackErrorKind.SERVER_UNAVAILABLE else TvheadendPlaybackErrorKind.RECORDING_UNAVAILABLE,
                    channelName = channelName,
                )
            }
        }
    }

    private suspend fun acquireStream(open: suspend () -> TvhPlaybackSource): TvhPlaybackSource {
        val job = currentCoroutineContext()[Job]
        return withContext(NonCancellable) { streamMutex.withLock {
            if (job?.isActive == false) throw CancellationException()
            val previous = BackgroundPlaybackSession.snapshot.value.metadata?.liveTvSession
            val replacement = BackgroundPlaybackSession.beginSourceReplacement(
                playWhenReadyOverride = true.takeIf { changingChannel },
            )
            pendingSourceReplacement = replacement
            try {
                suspendResult { previous?.report(0, false, true) }
                if (ownedSession !== previous) suspendResult { ownedSession?.report(0, false, true) }
                ownedSession = null
                if (job?.isActive == false || !BackgroundPlaybackSession.isSourceReplacement(replacement)) throw CancellationException()
                val stream = open()
                if (job?.isActive == false || !BackgroundPlaybackSession.isSourceReplacement(replacement)) {
                    suspendResult { stream.session?.report(0, false, true) }
                    throw CancellationException()
                }
                ownedSession = stream.session
                stream
            } catch (error: Throwable) {
                if (job?.isActive == true && !BackgroundPlaybackSession.isSourceReplacement(replacement) &&
                    !BackgroundPlaybackSession.snapshot.value.active) {
                    _uiState.value = TvheadendPlayerState.Stopped
                }
                BackgroundPlaybackSession.cancelSourceReplacement(replacement)
                throw error
            }
        } }
    }

    /** Live channels play through the on-device timeshift buffer when it is on and the stream is MPEG-TS. */
    private suspend fun timeshifted(source: TvhPlaybackSource, channelUuid: String): TvhPlaybackSource {
        if (!source.isLive || kind != "channel") return source
        preferences.initializeTimeshiftStorage(context)
        val megabytes = if (preferences.liveTimeshiftEnabled) preferences.liveTimeshiftMb else 0
        if (megabytes <= 0) { com.ambient.tv.livetv.LiveTimeshift.stop(); return source }
        val buffer = com.ambient.tv.livetv.LiveTimeshift.start(context, Destinations.tvhPlayer(kind, channelUuid, sourceId),
            source.url, source.headers, megabytes.toLong() * 1024 * 1024) ?: return source
        val (url, anchor) = buffer.liveUrl()
        com.ambient.tv.livetv.LiveTimeshift.setAnchor(anchor)
        // The loopback stream needs no upstream credentials.
        return source.copy(url = url, headers = emptyMap())
    }

    private suspend fun playGrowingRecording(ready: TvheadendPlayerState.Ready, entry: LiveTvRecording): Boolean {
        var buffer: RecordingPlaybackBuffer? = null
        var installed = false
        try {
            val source = repository.recordingStream(entry)
            val opened = withContext(Dispatchers.IO) {
                RecordingPlaybackBuffer.open(recordingClient, source, entry) {
                    runBlocking { repository.upcomingRecordings(true).any { it.uuid == entry.uuid } }
                }.also { buffer = it }.liveUrl()
            }
            currentCoroutineContext().ensureActive()
            val latest = _uiState.value as? TvheadendPlayerState.Ready ?: return false
            if (changingChannel || latest.channel?.uuid != ready.channel?.uuid || latest.source.url != ready.source.url) return false
            val playback = acquireStream { source.copy(url = opened.first, headers = emptyMap(), isLive = true,
                durationMs = null, startPositionMs = 0) }
            LiveTimeshift.useRecording(Destinations.tvhPlayer(kind, currentChannelUuid, sourceId), requireNotNull(buffer), opened.second)
            installed = true
            _uiState.value = latest.copy(source = playback, playingRecording = entry)
            return true
        } finally {
            if (!installed) buffer?.stop()
        }
    }

    private var seekJob: Job? = null

    /** Reopens the local buffer or server recording at a wall-clock moment. */
    fun seekTimeshift(wallMs: Long?) {
        val ready = _uiState.value as? TvheadendPlayerState.Ready ?: return
        val buffer = LiveTimeshift.current ?: return
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            if (wallMs == null && buffer.finished && ready.playingRecording != null) {
                val channel = ready.channel ?: return@launch
                if (changingChannel) return@launch
                changingChannel = true
                try {
                    val source = timeshifted(acquireStream { repository.liveStream(channel) }, channel.uuid)
                    val (now, next) = loadProgramInfo(channel.uuid)
                    _uiState.value = ready.copy(source = source, playingRecording = null, nowEvent = now, nextEvent = next)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    _uiState.value = TvheadendPlayerState.Error(context.getString(com.ambient.tv.R.string.tvh_playback_failed))
                } finally { changingChannel = false }
                return@launch
            }
            val result = suspendResult { withContext(Dispatchers.IO) {
                if (wallMs == null) buffer.liveUrl() else buffer.urlAt(wallMs)
            } }
            val (url, anchor) = result.getOrElse {
                android.widget.Toast.makeText(context, context.getString(com.ambient.tv.R.string.live_recording_seek_failed),
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val latest = _uiState.value as? TvheadendPlayerState.Ready ?: return@launch
            if (LiveTimeshift.current !== buffer || latest.source.url != ready.source.url || changingChannel) return@launch
            if (url == ready.source.url) return@launch
            BackgroundPlaybackSession.beginSourceReplacement(playWhenReadyOverride = true)
            LiveTimeshift.setAnchor(anchor)
            _uiState.value = latest.copy(source = latest.source.copy(url = url))
        }
    }

    override fun onCleared() {
        if (com.ambient.tv.livetv.LiveTimeshift.seekHandler === timeshiftSeekHandler) com.ambient.tv.livetv.LiveTimeshift.seekHandler = null
        // Leaving during tune must retire the pending session immediately. A late response
        // still closes its server stream, but cannot revive playback after navigation away.
        pendingSourceReplacement?.let(BackgroundPlaybackSession::cancelSourceReplacement)
        val session = ownedSession
        if (session != null && BackgroundPlaybackSession.snapshot.value.metadata?.liveTvSession !== session) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { suspendResult { session.report(0, false, true) } }
        }
        super.onCleared()
    }

    private fun tvhPanelArtwork(primary: String?, fallback: String?): Pair<String?, List<String>> {
        val candidates = listOfNotNull(primary, fallback).filter(String::isNotBlank).distinct()
        return candidates.firstOrNull() to candidates.drop(1)
    }

    private suspend fun loadProgramInfo(channelUuid: String): Pair<TvhEpgEvent?, TvhEpgEvent?> {
        val now = System.currentTimeMillis() / 1000L
        val events = suspendResult { repository.channelEpgEvents(channelUuid, startEpochSeconds = now - 60L, windowHours = 8) }
            .getOrDefault(emptyList())
            .filter { it.channelUuid == channelUuid }
            .sortedBy { it.startEpochSeconds }
        val current = events.firstOrNull { it.startEpochSeconds <= now && it.stopEpochSeconds > now }
        val next = events.firstOrNull { it.startEpochSeconds >= (current?.stopEpochSeconds ?: now) }
        return current to next
    }

    fun retry() {
        viewModelScope.launch {
            if (BackgroundPlaybackSession.snapshot.value.metadata?.historyScope == historyScope) {
                BackgroundPlaybackSession.stop()
            }
            runCatching { repository.invalidateAll() }
            load()
        }
    }

    val preferredAudioLanguage: String? get() = preferences.playbackAudioLanguage
    val preferredSubtitleLanguage: String? get() = preferences.preferredSubtitleLanguage
    val subtitlesEnabled: Boolean get() = preferences.subtitlesEnabled
    val forcedSubtitlesEnabled: Boolean get() = preferences.forcedSubtitlesEnabled
    val playbackBackendKey: String get() = preferences.tvheadendPlaybackBackendKey
    val displayRefreshAdjustmentKey: String get() = preferences.displayRefreshAdjustmentKey
    val preferredDisplayModeKey: String get() = preferences.preferredDisplayModeKey
    val displayModeWhitelist: Set<String> get() = preferences.displayModeWhitelist
    val allowDoubleRefreshRate: Boolean get() = preferences.allowDoubleRefreshRate
    val allowThreeTwoPulldown: Boolean get() = preferences.allowThreeTwoPulldown
    val seekJumpMs: Long get() = preferences.bigSeekJumpSeconds * 1000L
    val tvheadendDeinterlaceModeKey: String get() = preferences.tvheadendDeinterlaceModeKey
    val tvheadendDeinterlaceFilterKey: String get() = preferences.tvheadendDeinterlaceFilterKey
    val tvheadendMpvDeinterlaceFilterKey: String get() = preferences.tvheadendMpvDeinterlaceFilterKey
    val mpvVideoOutputModeKey: String get() = preferences.mpvVideoOutputModeKey
    val vlcScalingFilterKey: String get() = preferences.vlcScalingFilterKey

    fun rememberAudioLanguage(language: String?) {
        preferences.audioLanguageKey = language ?: LanguagePreferences.AUTO
    }



    private var serverUnreachableRetryAttempted = false

    fun reportServerUnreachable(reason: String = "") {
        val current = _uiState.value as? TvheadendPlayerState.Ready
        runCatching { BackgroundPlaybackSession.pause() }
        if (serverUnreachableRetryAttempted) {
            runCatching { BackgroundPlaybackSession.stop() }
            _uiState.value = TvheadendPlayerState.Error(
                message = reason.ifBlank { context.getString(com.ambient.tv.R.string.player_server_unreachable) },
                kind = TvheadendPlaybackErrorKind.SERVER_UNAVAILABLE,
                channelName = current?.channel?.name,
            )
            return
        }
        serverUnreachableRetryAttempted = true
        viewModelScope.launch {
            // suspendResult rethrows cancellation, so leaving mid-recheck never stops the global session.
            val recovered = suspendResult { if (sourceSystem == "tvheadend") playbackServerRecovery.tryWakeAndRecheck("tvh:resume") else repository.capabilities(true).available }.getOrDefault(false)
            if (recovered) {
                // Reload the same channel/recording after a brief resume wake.
                fallbackAttempted = false
                load()
            } else {
                runCatching { BackgroundPlaybackSession.stop() }
                _uiState.value = TvheadendPlayerState.Error(
                    message = reason.ifBlank { context.getString(com.ambient.tv.R.string.player_server_unreachable) },
                    kind = TvheadendPlaybackErrorKind.SERVER_UNAVAILABLE,
                    channelName = current?.channel?.name,
                )
            }
        }
    }

    fun requestPlaybackFallback(reason: String) {
        if (changingChannel) return
        if (com.ambient.tv.ui.player.playbackFailureKind(null, null, reason) == com.ambient.tv.ui.player.PlaybackFailureKind.AUDIO) {
            requestAudioFallback(reason)
        } else recoverPlayback(reason, allowServerProfile = videoFailureConfirmed)
    }

    fun requestAudioFallback(reason: String) {
        if (changingChannel) return
        videoFailureConfirmed = false
        recoverPlayback(reason, allowServerProfile = false)
    }

    fun requestVideoFallback(reason: String) {
        if (changingChannel) return
        videoFailureConfirmed = true
        recoverPlayback(reason, allowServerProfile = true)
    }

    private fun recoverPlayback(reason: String, allowServerProfile: Boolean) {
        if (PlaybackServerUnreachable.isNetworkish(null, reason)) {
            reportServerUnreachable(reason)
            return
        }

        val current = _uiState.value as? TvheadendPlayerState.Ready ?: return
        val active = if (current.playbackBackendKey == "auto") "media3" else current.playbackBackendKey
        localFallbackBackends.add(active)
        val local = com.ambient.tv.ui.player.LocalAudioFallbackPolicy.nextBackend(
            active, localFallbackBackends,
            com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable,
            enabled = (com.ambient.tv.ui.player.playbackProfileAudio(BackgroundPlaybackSession.activeHandle())?.options
                ?: (com.ambient.tv.ui.player.TitleVideoSettingsStore.load(context, metadata(current))?.audio
                    ?: com.ambient.tv.ui.player.ResolutionVideoProfileStore.load(context, metadata(current))?.audio
                    ?: preferences.audioSettings).currentForRoute(
                        com.ambient.tv.ui.player.AudioPassthroughPolicy.probeCached(context).profile)).localFallback != "off",
            dynamicRange = "dolby vision".takeIf { current.sourceHdrStandard == com.ambient.tv.ui.player.HdrStandard.DOLBY_VISION },
        )
        if (local != null) {
            _uiState.value = current.copy(recoveryRendererKey = local).withSelectedRenderer(current.source.startPositionMs)
            return
        }
        if (!allowServerProfile) {
            _uiState.value = TvheadendPlayerState.Error(
                message = context.getString(com.ambient.tv.R.string.player_local_audio_failed),
                channelName = current.channel?.name,
            )
            return
        }
        if (kind != "channel") {
            if (reason.isNotBlank()) {
                _uiState.value = TvheadendPlayerState.Error(
                    message = reason,
                    kind = TvheadendPlaybackErrorKind.RECORDING_UNAVAILABLE,
                    channelName = current.channel?.name,
                )
            }
            return
        }
        if (fallbackAttempted) {
            if (reason.isNotBlank()) {
                _uiState.value = TvheadendPlayerState.Error(
                    message = reason,
                    kind = TvheadendPlaybackErrorKind.CHANNEL_UNAVAILABLE,
                    channelName = current.channel?.name,
                )
            }
            return
        }
        val channel = current.channel ?: return
        viewModelScope.launch {
            fallbackAttempted = true
            suspendResult {
                val currentProfile = current.source.streamProfile
                val profiles = repository.streamProfiles()
                val candidates = profiles
                    .filterNot { it.name.equals(currentProfile, ignoreCase = true) }
                    .sortedByDescending { profile ->
                        val text = "${profile.name} ${profile.profileClass.orEmpty()}".lowercase()
                        when {
                            "h264" in text || "avc" in text -> 5
                            "webtv" in text -> 4
                            "matroska" in text -> 3
                            "mpegts" in text || "mpeg-ts" in text -> 2
                            "pass" in text -> 0
                            else -> 1
                        }
                    }
                val selected = candidates.firstOrNull() ?: error(reason.ifBlank { context.getString(com.ambient.tv.R.string.tvh_no_fallback_profile) })
                timeshifted(acquireStream { repository.liveStream(channel, selected.name) }, channel.uuid)
            }.fold(
                onSuccess = { source -> _uiState.value = current.copy(source = source, channel = channel, sourceHdrStandard = null) },
                onFailure = { error ->
                    val message = error.message ?: reason.ifBlank { context.getString(com.ambient.tv.R.string.tvh_playback_failed) }
                    _uiState.value = TvheadendPlayerState.Error(
                        message = message,
                        kind = TvheadendPlaybackErrorKind.CHANNEL_UNAVAILABLE,
                        channelName = channel.name,
                    )
                },
            )
        }
    }

    fun switchPlaybackBackend(key: String, positionMs: Long, makeGlobal: Boolean) {
        if (changingChannel) return
        val normalized = key.takeIf { it in setOf("auto", "media3", "vlc", "mpv") } ?: "auto"
        if (normalized == "mpv" && !com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable) return
        if (makeGlobal) preferences.tvheadendPlaybackBackendKey = normalized
        val current = _uiState.value as? TvheadendPlayerState.Ready ?: return
        // Only this explicit action changes the manual choice. Recovery stays session-local.
        _uiState.value = current.copy(manualRendererKey = normalized, recoveryRendererKey = null)
            .withSelectedRenderer(positionMs)
    }

    private fun TvheadendPlayerState.Ready.withSelectedRenderer(positionMs: Long = source.startPositionMs, restorePosition: Boolean = true): TvheadendPlayerState.Ready {
        val selected = selectLiveRenderer(
            default = preferences.tvheadendPlaybackBackendKey,
            channel = com.ambient.tv.ui.player.TitleVideoSettingsStore.load(context, metadata(this)),
            profiles = preferences.resolutionVideoProfiles,
            widthKey = com.ambient.tv.data.local.VideoResolutionClass.key(sourceWidth),
            live = source.isLive,
            manual = manualRendererKey,
            recovery = recoveryRendererKey,
            dolbyVision = sourceHdrStandard == com.ambient.tv.ui.player.HdrStandard.DOLBY_VISION,
            mpvAvailable = com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable,
        )
        val change = com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(playbackBackendKey) !=
            com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(selected.key)
        val recordingBuffer = LiveTimeshift.current?.takeIf { it.recordingId != null && it.recordingId == playingRecording?.uuid }
        val resumedSource = if (change && restorePosition && recordingBuffer != null) {
            val elapsed = com.ambient.tv.ui.player.rendererSwitchPositionMs(
                BackgroundPlaybackSession.currentStatus()?.positionMs, positionMs,
                com.ambient.tv.ui.player.rendererZeroPositionReady(BackgroundPlaybackSession.activeHandle()))
            val wall = (LiveTimeshift.anchor.value ?: recordingBuffer.oldestWallMs) + elapsed.coerceAtLeast(0L)
            val (url, anchor) = recordingBuffer.urlAt(wall)
            LiveTimeshift.setAnchor(anchor)
            source.copy(url = url, startPositionMs = 0L)
        } else if (!change || !restorePosition) source else source.copy(startPositionMs = if (source.isLive) 0L else
            com.ambient.tv.ui.player.rendererSwitchPositionMs(
                BackgroundPlaybackSession.currentStatus()?.positionMs, positionMs,
                com.ambient.tv.ui.player.rendererZeroPositionReady(BackgroundPlaybackSession.activeHandle())))
        return copy(source = resumedSource, playbackBackendKey = selected.key, rendererOrigin = selected.origin)
    }

    fun applySourceHdrFromRuntime(standard: com.ambient.tv.ui.player.HdrStandard?) {
        if (changingChannel) return
        val current = _uiState.value as? TvheadendPlayerState.Ready ?: return
        if (current.sourceHdrStandard == standard) return
        // Re-evaluate even if geometry was already received. Late Dolby Vision wins over any
        // preset/manual/recovery engine without replacing the user's stored preference.
        _uiState.value = current.copy(sourceHdrStandard = standard).withSelectedRenderer()
    }

    fun applyResolutionProfileFromRuntime(width: Int, height: Int, positionMs: Long) {
        if (changingChannel) return
        val current = _uiState.value as? TvheadendPlayerState.Ready ?: return
        if (width <= 0 || height <= 0 || (current.sourceWidth == width && current.sourceHeight == height)) return
        _uiState.value = current.copy(sourceWidth = width, sourceHeight = height).withSelectedRenderer(positionMs)
    }

    fun zap(delta: Int) {
        if (kind != "channel" || delta == 0 || changingChannel) return
        viewModelScope.launch {
            changingChannel = true
            try {
                if (channels.isEmpty()) {
                    // Without the list zapping cannot move, but the current channel is fine:
                    // keep it playing and say why the key did nothing.
                    channels = runCatching { repository.channels() }.getOrElse { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                            "tvh:channels", com.ambient.tv.R.string.notice_channel_list_failed,
                            phase = com.ambient.tv.notifications.NoticePhase.ERROR,
                            kind = com.ambient.tv.notifications.NoticeKind.LIBRARY, interruptPlayback = false,
                        ))
                        return@launch
                    }
                }
                if (channels.isEmpty()) return@launch
                val ring = zapChannels(channels, zapOrder)
                val currentIndex = ring.indexOfFirst { it.uuid == currentChannelUuid }.let { if (it < 0) 0 else it }
                val nextIndex = ((currentIndex + delta) % ring.size + ring.size) % ring.size
                val channel = ring[nextIndex]
                if (channel.uuid == currentChannelUuid) return@launch
                // Retain the OSD while acquireStream serially retires the upstream session.
                // PlaybackSessionContent suppresses EOF during the replacement.
                currentChannelUuid = channel.uuid
                zapVersion++
                fallbackAttempted = false
                localFallbackBackends.clear()
                videoFailureConfirmed = false
                com.ambient.tv.ui.player.AudioCodecRejects.clear()
                val source = timeshifted(acquireStream { repository.liveStream(channel) }, channel.uuid)
                val artwork = tvhPanelArtwork(null, channel.iconUrl)
                _uiState.value = TvheadendPlayerState.Ready(
                    source = source,
                    channel = channel,
                    artworkUrl = artwork.first,
                    artworkFallbackUrls = artwork.second,
                    playbackBackendKey = preferences.tvheadendPlaybackBackendKey,
                    audioVisualizerModeKey = preferences.audioVisualizerModeKey,
                    audioPassthroughEnabled = preferences.mediaAudioPassthroughEnabled,
                    projectmPresetIntervalSeconds = preferences.projectmPresetIntervalSeconds,
                    projectmTransitionSeconds = preferences.projectmTransitionSeconds,
                    projectmQualityKey = preferences.projectmQualityKey,
                    projectmPresetCategoryKey = preferences.projectmPresetCategoryKey,
                ).withSelectedRenderer(restorePosition = false)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val channelName = channels.firstOrNull { it.uuid == currentChannelUuid }?.name
                _uiState.value = TvheadendPlayerState.Error(
                    message = error.message ?: context.getString(com.ambient.tv.R.string.tvh_channel_change_failed),
                    kind = TvheadendPlaybackErrorKind.CHANNEL_UNAVAILABLE,
                    channelName = channelName,
                )
            } finally {
                changingChannel = false
            }
        }
    }
}

/** The channels to zap through: [order] (the filtered view) where it still matches, else all. */
internal fun zapChannels(channels: List<TvhChannel>, order: List<String>?): List<TvhChannel> {
    if (order == null) return channels
    val byId = channels.associateBy { it.uuid }
    return order.mapNotNull(byId::get).ifEmpty { channels }
}
