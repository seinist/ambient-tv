@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramChannel
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.data.telegram.TelegramVideoDataSource
import com.ambient.tv.data.telegram.playerMetadata
import com.ambient.tv.ui.common.BrowseStatusKind
import com.ambient.tv.ui.common.BrowseStatusPanel
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.OsdPlayer
import com.ambient.tv.ui.player.PersistentPlayerChrome
import com.ambient.tv.ui.player.PersistentPlayerOverlays
import com.ambient.tv.ui.player.PlaybackEngineFactory
import com.ambient.tv.ui.player.rendererSwitchPositionMs
import com.ambient.tv.ui.player.rendererZeroPositionReady
import com.ambient.tv.ui.player.resizeKey
import com.ambient.tv.ui.player.aspectKey
import com.ambient.tv.ui.player.videoResizeMode
import com.ambient.tv.ui.player.videoAspectRatio
import com.ambient.tv.ui.player.TitleVideoSettingsStore
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.ui.player.backend.PlaybackBackendResolver
import com.ambient.tv.ui.player.backend.rememberMpvPlaybackController
import com.ambient.tv.ui.player.backend.rememberVlcPlaybackController
import com.ambient.tv.ui.player.PlaybackSessionContent
import com.ambient.tv.ui.player.PlayerControlsAutoHide
import com.ambient.tv.ui.player.PlayerOsdSpec
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.player.PlayerPanelModel
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.player.PublishPlayerOsd
import com.ambient.tv.ui.player.PublishPlayerPanel
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.rememberPlayerControlsVisible
import com.ambient.tv.ui.player.rememberPlayerEngineFocus
import com.ambient.tv.ui.player.rememberPlayerOsdController
import com.ambient.tv.ui.player.rememberPlayerPlayFocus
import com.ambient.tv.ui.player.rememberPlayerSeekFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TelegramPlayerViewModel @Inject constructor(
    val repository: TelegramRepository,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableRenderer = MutableStateFlow(PlaybackBackendResolver.resolve(preferences.playbackBackendKey).key)
    val renderer = mutableRenderer.asStateFlow()

    /** Same user setting as every other player: passthrough applies to Media3, VLC and MPV. */
    val audioPassthroughEnabled: Boolean get() = preferences.mediaAudioPassthroughEnabled

    /**
     * Start position of the current player session. Changes only together with the media or
     * the renderer, so the session acquisition key stays stable while playing.
     */
    private val mutableStartPosition = MutableStateFlow(0L)
    val startPositionMs = mutableStartPosition.asStateFlow()

    fun chooseRenderer(key: String, makeGlobal: Boolean) {
        val requested = key.takeIf { it in setOf("media3", "vlc", "mpv") } ?: "media3"
        if (requested == "mpv" && !MpvBackendAvailability.isAvailable) return
        if (makeGlobal) preferences.playbackBackendKey = requested
        TitleVideoSettingsStore.rememberRenderer(context, mutableMedia.value?.playerMetadata(),
            if (makeGlobal || key == "auto") null else requested)
        if (requested == mutableRenderer.value) return
        // Capture before the old renderer is released: the new one continues from here.
        mutableMedia.value?.let { media ->
            val position = currentPositionMs(media)
            setStartPosition(media, position)
        }
        mutableRenderer.value = requested
    }

    private fun setStartPosition(media: TelegramMedia, positionMs: Long) {
        mutableStartPosition.value = positionMs.coerceAtLeast(0L)
        repository.noteSessionStart(media.mediaKey, mutableStartPosition.value)
    }

    private fun ownsSession(media: TelegramMedia): Boolean =
        BackgroundPlaybackSession.snapshot.value.metadata?.mediaId == media.mediaKey

    private fun currentPositionMs(media: TelegramMedia): Long {
        if (!ownsSession(media)) return mutableStartPosition.value
        return rendererSwitchPositionMs(
            BackgroundPlaybackSession.currentStatus()?.positionMs,
            BackgroundPlaybackSession.snapshot.value.status.positionMs,
            rendererZeroPositionReady(BackgroundPlaybackSession.activeHandle()),
        )
    }

    /** Remembers where playback stands so reopening the post continues there. */
    fun rememberPosition() {
        val media = mutableMedia.value ?: return
        if (media.kind == TelegramMediaKind.PHOTO || !ownsSession(media)) return
        val status = BackgroundPlaybackSession.snapshot.value.status
        repository.rememberResumePosition(media.mediaKey, currentPositionMs(media),
            status.durationMs.takeIf { it > 0 } ?: media.playerMetadata().durationMs)
    }

    override fun onCleared() {
        rememberPosition()
        super.onCleared()
    }

    private val mutableMedia = MutableStateFlow<TelegramMedia?>(null)
    val media = mutableMedia.asStateFlow()
    private val mutableChannel = MutableStateFlow<TelegramChannel?>(null)
    val channel = mutableChannel.asStateFlow()
    private val mutableChannelBusy = MutableStateFlow(false)
    val channelBusy = mutableChannelBusy.asStateFlow()
    private val mutableChannelError = MutableStateFlow<String?>(null)
    val channelError = mutableChannelError.asStateFlow()

    fun toggleChannelSubscription() {
        val selected = mutableChannel.value?.takeIf { it.subscribed != null } ?: return
        if (mutableChannelBusy.value) return
        mutableChannelBusy.value = true
        mutableChannelError.value = null
        viewModelScope.launch {
            try {
                val wasJoined = selected.subscribed == true
                if (wasJoined) BackgroundPlaybackSession.stop()
                val accepted = if (wasJoined) repository.leaveChannel(selected.chatId)
                    else repository.joinChannel(selected.chatId)
                if (accepted) {
                    mutableChannel.value = selected.copy(subscribed = !wasJoined)
                } else {
                    mutableChannelError.value = context.getString(if (wasJoined)
                        R.string.monogram_leave_failed else R.string.monogram_join_failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableChannelError.value = failure.message
                    ?: context.getString(R.string.monogram_membership_failed)
            } finally {
                mutableChannelBusy.value = false
            }
        }
    }
    private val mutablePhotoUrl = MutableStateFlow<String?>(null)
    val photoUrl = mutablePhotoUrl.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun load(chatId: Long, messageId: Int) {
        rememberPosition()
        mutableError.value = null
        mutableMedia.value = null
        mutableChannel.value = null
        mutableChannelError.value = null
        viewModelScope.launch {
            try {
                val selected = repository.media(chatId, messageId)
                if (selected == null) mutableError.value = context.getString(R.string.monogram_error_media_unavailable)
                else if (!selected.playableOnTelegram) mutableError.value =
                    context.getString(R.string.monogram_error_media_protected)
                else {
                    mutableRenderer.value = PlaybackBackendResolver.resolve(TitleVideoSettingsStore.rendererFor(
                        context, selected.playerMetadata(), preferences.playbackBackendKey)).key
                    // Still playing in the background: keep the original start so the running
                    // session is reattached, not reopened. Otherwise continue where it stopped.
                    setStartPosition(selected,
                        if (ownsSession(selected)) repository.sessionStartMs(selected.mediaKey)
                        else repository.resumePositionMs(selected.mediaKey))
                    mutableMedia.value = selected
                    // Load channel artwork and membership independently from the player start.
                    launch {
                        try {
                            mutableChannel.value = repository.channel(selected.chatId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { /* Channel action remains optional. */ }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: context.getString(R.string.monogram_error_media_open)
            }
        }
    }

    fun loadPhoto(media: TelegramMedia) {
        mutablePhotoUrl.value = null
        viewModelScope.launch {
            try {
                mutablePhotoUrl.value = repository.photoFile(media.chatId, media.messageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the inline preview visible if fetching the original fails.
            }
        }
    }

    fun switchInQueue(current: TelegramMedia, direction: Int) {
        viewModelScope.launch {
            for ((chat, msg) in repository.playbackCandidates(current.mediaKey, direction)) {
                val candidate = runCatching { repository.media(chat, msg) }.getOrNull()
                if (candidate?.playableOnTelegram == true) {
                    load(chat, msg)
                    return@launch
                }
            }
        }
    }

    fun reportFailure(message: String) {
        mutableError.value = message
    }
}

@Composable
fun TelegramPlayerScreen(
    chatId: Long,
    messageId: Int,
    onBack: () -> Unit,
    onOpenChannel: (Long) -> Unit,
    viewModel: TelegramPlayerViewModel = hiltViewModel(),
) {
    val media by viewModel.media.collectAsState()
    val failure by viewModel.error.collectAsState()
    val available = viewModel.repository.available
    LaunchedEffect(chatId, messageId, available) {
        if (available) viewModel.load(chatId, messageId)
    }
    PersistentPlayerChrome(viewModel) {
        val selected = media
        if (selected == null || failure != null) {
            BrowseStatusPanel(
                title = stringResource(R.string.telegram_error),
                body = failure ?: stringResource(R.string.settings_telegram_native_missing),
                kind = if (failure == null) BrowseStatusKind.INFO else BrowseStatusKind.ERROR,
                actionLabel = stringResource(R.string.back),
                onAction = onBack,
            )
        } else {
            TelegramPlaybackContent(selected, onBack, viewModel::reportFailure,
                viewModel.repository, viewModel, onOpenChannel)
        }
    }
}

@Composable
private fun TelegramPlaybackContent(
    media: TelegramMedia,
    onBack: () -> Unit,
    onFailure: (String) -> Unit,
    repository: TelegramRepository,
    viewModel: TelegramPlayerViewModel,
    onOpenChannel: (Long) -> Unit,
) {
    val context = LocalContext.current
    val fullPhotoUrl by viewModel.photoUrl.collectAsState()
    val channel by viewModel.channel.collectAsState()
    val channelBusy by viewModel.channelBusy.collectAsState()
    val channelError by viewModel.channelError.collectAsState()
    LaunchedEffect(media.mediaKey) {
        if (media.kind == TelegramMediaKind.PHOTO) viewModel.loadPhoto(media)
    }
    val metadata = remember(media.mediaKey) { media.playerMetadata() }
    val renderer by viewModel.renderer.collectAsState()
    val startPositionMs by viewModel.startPositionMs.collectAsState()
    val audioPassthrough = viewModel.audioPassthroughEnabled
    val streamUnavailable = stringResource(R.string.monogram_error_stream)
    val rendererUnavailable = stringResource(R.string.monogram_error_renderer)
    val playbackFailed = stringResource(R.string.monogram_error_playback)
    // While this screen is visible, keep the reopen position fresh (playback may continue
    // in the background after leaving the screen).
    LaunchedEffect(media.mediaKey) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            viewModel.rememberPosition()
        }
    }
    // MPV/VLC use Ambient TV's existing seekable localhost media transport.
    // Media3 keeps its direct authenticated DataSource; no relay or extra socket then.
    var loopbackUrl by remember(media.mediaKey, renderer) { mutableStateOf<String?>(null) }
    LaunchedEffect(media.mediaKey, renderer, repository) {
        if (media.kind != TelegramMediaKind.PHOTO && renderer != "media3") {
            try {
                loopbackUrl = repository.playerHttpUrl(media)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error.message ?: streamUnavailable)
            }
        }
    }
    val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    val presentation by BackgroundPlaybackSession.videoPresentation.collectAsState()
    val controls = rememberPlayerControlsVisible()
    var controlsVisible by controls
    val osd = rememberPlayerOsdController()
    val focus = rememberPlayerEngineFocus()
    val playFocus = rememberPlayerPlayFocus()
    val panelNavigation = rememberPlayerPanelNavigation()
    var panel by panelNavigation

    BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        if (controlsVisible) {
            controlsVisible = false
            runCatching { focus.requestFocus() }
        } else onBack()
    }
    PlayerControlsAutoHide(controlsVisible, snapshot.playing,
        panel != UnifiedPlayerPanel.NONE) { shown -> controlsVisible = shown }

    RequestPlayerOverlayFocus(
        requester = playFocus,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = snapshot.playing,
        panelNavigation = panelNavigation,
    )
    PlaybackSessionContent(metadata = metadata, onStopped = onBack,
        acquisitionKey = listOf(media.mediaKey, renderer)) {
        if (media.kind != TelegramMediaKind.PHOTO) {
            when (renderer) {
                "vlc" -> if (loopbackUrl != null) {
                    val controller = rememberVlcPlaybackController(
                        context = context, url = loopbackUrl!!, startPositionMs = startPositionMs,
                        headers = emptyMap(), networkCache = true,
                        initialAudioPassthrough = audioPassthrough,
                    )
                    LaunchedEffect(controller, loopbackUrl) {
                        if (controller == null) onFailure(rendererUnavailable.format("VLC"))
                    }
                }
                "mpv" -> if (loopbackUrl != null) {
                    val controller = rememberMpvPlaybackController(
                        context = context, url = loopbackUrl!!, startPositionMs = startPositionMs,
                        headers = emptyMap(), networkCache = true,
                        audioPassthroughEnabled = audioPassthrough,
                    )
                    LaunchedEffect(controller, loopbackUrl) {
                        if (controller == null) onFailure(rendererUnavailable.format("MPV"))
                    }
                }
                else -> LaunchedEffect(media.mediaKey, renderer, repository) {
                    try {
                        PlaybackEngineFactory.create(
                            context = context,
                            streamUrl = "telegram://media/${media.chatId}/${media.messageId}",
                            startPositionMs = startPositionMs,
                            audioPassthroughEnabled = audioPassthrough,
                            metadata = metadata,
                            dataSourceFactory = TelegramVideoDataSource.Factory(repository, media),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onFailure(error.message ?: playbackFailed)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize()
                // The persistent Media3 SurfaceView is rendered beneath this navigation
                // destination. Opaque player chrome would otherwise hide the video.
                .background(if (media.kind == TelegramMediaKind.VIDEO) Color.Transparent else Color.Black)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            if (controlsVisible) BackgroundPlaybackSession.userToggle()
                            else { controlsVisible = true; osd.reveal() }
                            true
                        }
                        Key.MediaPlayPause -> {
                            BackgroundPlaybackSession.userToggle()
                            true
                        }
                        else -> false
                    }
                }.focusable(),
        ) {
            if (media.kind == TelegramMediaKind.PHOTO) {
                com.ambient.tv.ui.common.CrossfadeBackdropImage(
                    model = fullPhotoUrl ?: media.thumbnailUrl,
                    contentDescription = media.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val ownsSession = snapshot.metadata?.mediaId == media.mediaKey
            val position = if (ownsSession) snapshot.status.positionMs else 0L
            val duration = if (ownsSession) snapshot.status.durationMs.takeIf { it > 0 }
                ?: metadata.durationMs else metadata.durationMs
            val playing = ownsSession && snapshot.playing
            PublishPlayerOsd(
                TelegramOsd(
                    media = media,
                    playing = playing,
                    positionMs = position,
                    durationMs = duration,
                    onToggle = BackgroundPlaybackSession::userToggle,
                    onSeekBy = { delta ->
                        if (ownsSession && duration > 0)
                            BackgroundPlaybackSession.seekTo(
                                (position + delta).coerceIn(0, duration),
                            )
                    },
                    onStop = { BackgroundPlaybackSession.stop(); onBack() },
                    onBack = onBack,
                    hasPrevious = repository.playbackCandidates(media.mediaKey, -1).isNotEmpty(),
                    hasNext = repository.playbackCandidates(media.mediaKey, 1).isNotEmpty(),
                    onPrevious = { viewModel.switchInQueue(media, -1) },
                    onNext = { viewModel.switchInQueue(media, 1) },
                    channel = channel ?: TelegramChannel(media.chatId, media.channel),
                    channelBusy = channelBusy,
                    channelError = channelError,
                    onOpenChannel = {
                        BackgroundPlaybackSession.pause()
                        onOpenChannel(media.chatId)
                    },
                    onToggleSubscription = viewModel::toggleChannelSubscription,
                    onOpenSettings = { panel = UnifiedPlayerPanel.SETTINGS },
                    onOpenSleep = { panel = UnifiedPlayerPanel.SLEEP },
                ),
                visible = controlsVisible,
            )
            // Native track enumeration is expensive: match the other player screens and only
            // request it while the OSD or right panel is visible.
            if (controlsVisible || panel != UnifiedPlayerPanel.NONE) {
                val trackControls = monogramRendererControls(
                    if (ownsSession) BackgroundPlaybackSession.activeHandle() else null)
                PublishPlayerPanel(
                    PlayerPanelModel(
                    scrimAlpha = 0.32f,
                    projectMBrowser = null,
                    audioSession = BackgroundPlaybackSession.currentAudioSession(),
                    hasVideo = metadata.hasVideo,
                    isLive = false,
                    scalingKey = presentation.resizeKey(),
                    aspectKey = presentation.aspectKey(),
                    zoomFactor = presentation.zoom,
                    speed = trackControls.speed,
                    onScalingChanged = { next -> BackgroundPlaybackSession.setVideoPresentation(
                        presentation.copy(resizeMode = videoResizeMode(next))) },
                    onAspectChanged = { next -> BackgroundPlaybackSession.setVideoPresentation(
                        presentation.copy(aspectRatio = videoAspectRatio(next))) },
                    onZoomChanged = { next -> BackgroundPlaybackSession.setVideoPresentation(
                        presentation.copy(zoom = next)) },
                    onSpeedChanged = trackControls.onSpeedChanged,
                    audioOptions = trackControls.audio,
                    selectedAudio = trackControls.selectedAudio,
                    onAudioSelected = trackControls.selectAudio,
                    subtitleOptions = trackControls.subtitles,
                    selectedSubtitle = trackControls.selectedSubtitle,
                    onSubtitleSelected = trackControls.selectSubtitle,
                    sleepTimer = BackgroundPlaybackSession.sleepTimer,
                    onSleepChanged = BackgroundPlaybackSession::setSleepTimer,
                    infoLines = listOf(
                        stringResource(R.string.monogram_info_source) to "Monogram",
                        stringResource(R.string.monogram_info_channel) to media.channel,
                        stringResource(R.string.monogram_info_media_id) to media.mediaKey,
                        stringResource(R.string.monogram_info_size) to
                            android.text.format.Formatter.formatShortFileSize(context, media.fileSize),
                        stringResource(R.string.player_info_backend) to renderer.uppercase(),
                    ),
                    rendererKey = renderer,
                    onRendererSelected = { key, global -> viewModel.chooseRenderer(key, global) },
                    showRenderer = media.kind != TelegramMediaKind.PHOTO,
                    showVideo = metadata.hasVideo,
                    showAudio = media.kind != TelegramMediaKind.PHOTO,
                    showSubtitles = media.kind == TelegramMediaKind.VIDEO,
                    showVisualizer = media.kind == TelegramMediaKind.AUDIO,
                    ),
                )
            }
            PersistentPlayerOverlays(expectedSourceIdentity = media.mediaKey)
        }
    }
}

internal data class TelegramOsd(
    val media: TelegramMedia,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val onToggle: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onStop: () -> Unit,
    val onBack: () -> Unit,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val channel: TelegramChannel,
    val channelBusy: Boolean,
    val channelError: String?,
    val onOpenChannel: () -> Unit,
    val onToggleSubscription: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenSleep: () -> Unit,
) : PlayerOsdSpec {
    override val sourceIdentity: String get() = media.mediaKey

    @Composable
    override fun Content(visible: Boolean, panel: UnifiedPlayerPanel) {
        val osd = rememberPlayerOsdController()
        val playFocus = rememberPlayerPlayFocus()
        val seekFocus = rememberPlayerSeekFocus()
        PlayerOverlayVisibility(visible = visible) {
            ReferencePlayerOverlayBackground()
            ReferencePlayerClock(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 40.dp),
            )
            ReferencePlayerBottomContent(onTogglePlayback = onToggle) {
                ReferencePlayerHeader(
                    title = media.title,
                    subtitle = media.summary,
                    contextLine = media.channel,
                    artworkUrl = media.thumbnailUrl,
                )
                if (durationMs > 0 && media.kind != TelegramMediaKind.PHOTO) {
                    TvPlayerSeekBar(
                        positionMs = positionMs.coerceIn(0L, durationMs),
                        durationMs = durationMs,
                        onSeekBy = onSeekBy,
                        onInteraction = osd::interact,
                        onTogglePlayback = onToggle,
                        onPlay = { if (!playing) BackgroundPlaybackSession.userPlay() },
                        modifier = Modifier.focusRequester(seekFocus),
                    )
                }
                PlayerTechBadgeRow {
                    MetaBadge(systemName(MediaSystem.TELEGRAM), emphasized = true)
                    MetaBadge(media.channel)
                    channelError?.let { MetaBadge(it) }
                }
                PlayerControlBar(
                    player = OsdPlayer.MEDIA,
                    leftContent = {
                        if (hasPrevious) PlayerIconButton(
                            Icons.Filled.SkipPrevious,
                            stringResource(R.string.player_previous_item),
                            onPrevious,
                        )
                        if (media.kind != TelegramMediaKind.PHOTO) PlayerIconButton(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing) R.string.player_pause else R.string.player_play),
                            onToggle, modifier = Modifier.focusRequester(playFocus), primary = true,
                        )
                        if (hasNext) PlayerIconButton(
                            Icons.Filled.SkipNext,
                            stringResource(R.string.player_next),
                            onNext,
                        )
                        PlayerIconButton(
                            Icons.Filled.Stop, stringResource(R.string.player_stop),
                            onStop,
                        )
                    },
                    rightContent = {
                        PlayerExitButton(onBack)
                        PlayerIconButton(
                            Icons.Filled.AccountCircle, channel.title,
                            onOpenChannel,
                            imageUrl = channel.thumbnailUrl,
                        )
                        channel.subscribed?.let { joined ->
                            PlayerIconButton(
                                Icons.Filled.Subscriptions,
                                stringResource(if (joined) R.string.monogram_leave_channel
                                    else R.string.monogram_join_channel),
                                onToggleSubscription,
                                selected = joined,
                                enabled = !channelBusy,
                            )
                        }
                        PlayerSleepTimerButton(onOpenSleep, selected = panel == UnifiedPlayerPanel.SLEEP)
                        PlayerIconButton(
                            Icons.Filled.Settings,
                            stringResource(R.string.player_playback_settings),
                            onOpenSettings,
                            selected = panel != UnifiedPlayerPanel.NONE,
                        )
                    },
                )
            }
        }
    }
}
