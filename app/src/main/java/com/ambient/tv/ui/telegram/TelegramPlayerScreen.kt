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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.telegram.TelegramMedia
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
) : ViewModel() {
    private val mutableMedia = MutableStateFlow<TelegramMedia?>(null)
    val media = mutableMedia.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun load(chatId: Long, messageId: Int) {
        mutableError.value = null
        mutableMedia.value = null
        viewModelScope.launch {
            try {
                val selected = repository.media(chatId, messageId)
                if (selected == null) mutableError.value = "Telegram media is unavailable"
                else if (!selected.playableOnTelegram) mutableError.value =
                    "This item is protected or requires an external player"
                else mutableMedia.value = selected
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "Telegram media could not be opened"
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
                viewModel.repository, viewModel)
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
) {
    val context = LocalContext.current
    val metadata = remember(media.mediaKey) { media.playerMetadata() }
    val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
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
    PlaybackSessionContent(metadata = metadata, onStopped = onBack) {
        LaunchedEffect(media.mediaKey, repository) {
            if (media.kind == TelegramMediaKind.PHOTO) return@LaunchedEffect
            try {
                PlaybackEngineFactory.create(
                    context = context,
                    streamUrl = "telegram://media/${media.chatId}/${media.messageId}",
                    startPositionMs = 0L,
                    metadata = metadata,
                    dataSourceFactory = TelegramVideoDataSource.Factory(repository, media),
                )
            } catch (error: Exception) {
                onFailure(error.message ?: "Telegram playback failed")
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
                    model = media.thumbnailUrl,
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
                    onOpenSettings = { panel = UnifiedPlayerPanel.SETTINGS },
                    onOpenSleep = { panel = UnifiedPlayerPanel.SLEEP },
                ),
                visible = controlsVisible,
            )
            PublishPlayerPanel(
                PlayerPanelModel(
                    scrimAlpha = 0.32f,
                    projectMBrowser = null,
                    audioSession = BackgroundPlaybackSession.currentAudioSession(),
                    hasVideo = metadata.hasVideo,
                    isLive = false,
                    scalingKey = "fit",
                    aspectKey = "auto",
                    zoomFactor = 1f,
                    speed = 1f,
                    onScalingChanged = null,
                    onAspectChanged = null,
                    onZoomChanged = null,
                    onSpeedChanged = null,
                    audioOptions = emptyList(),
                    selectedAudio = "",
                    onAudioSelected = {},
                    subtitleOptions = emptyList(),
                    selectedSubtitle = "",
                    onSubtitleSelected = {},
                    sleepTimer = BackgroundPlaybackSession.sleepTimer,
                    onSleepChanged = BackgroundPlaybackSession::setSleepTimer,
                    infoLines = listOf(
                        "Source" to "Monogram",
                        "Channel" to media.channel,
                        "Media ID" to media.mediaKey,
                        "Size" to "${media.fileSize} bytes",
                        "Backend" to "Media3",
                    ),
                    rendererKey = "media3",
                    showRenderer = false,
                    showVideo = metadata.hasVideo,
                    showVisualizer = !metadata.hasVideo,
                ),
            )
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
