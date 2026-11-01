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
import kotlinx.coroutines.Job
import androidx.lifecycle.SavedStateHandle
import com.ambient.tv.data.telegram.telegramMediaIds
import com.ambient.tv.data.telegram.telegramMediaKey
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.NextUpCandidate
import com.ambient.tv.ui.player.NextUpConfig
import com.ambient.tv.ui.player.RegisterPlayerItemNavigation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TelegramPlayerViewModel @Inject constructor(
    val repository: TelegramRepository,
    val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
    private val playbackContext: TelegramPlaybackContext,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** Queue of the page the player was started from; null plays only this post. */
    val queueToken: Long? = savedStateHandle.get<Long>("queue")?.takeIf { it >= 0L }
    // The post shown now. Next/Previous change it without a new destination, like YouTube.
    private var chatId: Long = savedStateHandle.get<Long>("chatId") ?: 0L
    private var messageId: Int = savedStateHandle.get<Int>("messageId") ?: 0
    private val mutableQueue = MutableStateFlow(TelegramQueueState())
    val queue = mutableQueue.asStateFlow()
    private val mutableQueueEnded = MutableStateFlow(false)
    /** A Next found no playable post after all: the queue is over, like at the real end. */
    val queueEnded = mutableQueueEnded.asStateFlow()
    private var loadJob: Job? = null
    private var successorJob: Job? = null
    private var transitionToken: Long? = null
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

    /** Opens the destination's post once; re-entering the screen keeps what is playing. */
    fun start() {
        val wanted = telegramMediaKey(chatId, messageId)
        if (loadJob?.isActive == true || mutableMedia.value?.mediaKey == wanted) return
        load(chatId, messageId, direction = 0)
    }

    fun retry() = load(chatId, messageId, direction = 0)

    /** [direction] > 0: Next, < 0: Previous; a post that cannot be played is skipped that way. */
    private fun load(chat: Long, message: Int, direction: Int) {
        rememberPosition()
        mutableError.value = null
        mutableQueueEnded.value = false
        successorJob?.cancel()
        loadJob?.cancel()
        val requestToken = transitionToken
        loadJob = viewModelScope.launch {
            var targetChat = chat
            var targetMessage = message
            try {
                while (true) {
                    val selected = repository.media(targetChat, targetMessage)
                    val playable = selected != null && selected.playableOnTelegram &&
                        (direction == 0 || selected.kind != TelegramMediaKind.PHOTO)
                    if (playable) {
                        show(selected, requestToken)
                        return@launch
                    }
                    val following = if (direction == 0 || queueToken == null) null else {
                        val key = telegramMediaKey(targetChat, targetMessage)
                        if (direction > 0) playbackContext.nextLoaded(queueToken, key)
                            ?: playbackContext.next(queueToken, key)
                        else playbackContext.previousLoaded(queueToken, key)
                    }?.let { telegramMediaIds(it.ratingKey) }
                    if (following == null) {
                        if (direction > 0) {
                            // Autoplay or Next ran past the last playable post: the queue ended.
                            transitionToken = null
                            mutableQueueEnded.value = true
                        } else {
                            fail(context.getString(if (selected == null) R.string.monogram_error_media_unavailable
                                else R.string.monogram_error_media_protected))
                        }
                        return@launch
                    }
                    targetChat = following.first
                    targetMessage = following.second
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error.message ?: context.getString(R.string.monogram_error_media_open))
            }
        }
    }

    private fun fail(message: String) {
        transitionToken?.let {
            BackgroundPlaybackSession.pause()
            BackgroundPlaybackSession.failSourceReplacement(it, message)
        }
        transitionToken = null
        mutableError.value = message
    }

    private fun show(selected: TelegramMedia, requestToken: Long?) {
        // A newer request (or Stop) replaced this switch meanwhile.
        if (requestToken != null && !BackgroundPlaybackSession.isSourceReplacement(requestToken)) return
        transitionToken = null
        chatId = selected.chatId
        messageId = selected.messageId
        savedStateHandle["chatId"] = selected.chatId
        savedStateHandle["messageId"] = selected.messageId
        mutableRenderer.value = PlaybackBackendResolver.resolve(TitleVideoSettingsStore.rendererFor(
            context, selected.playerMetadata(), preferences.playbackBackendKey)).key
        // Still playing in the background: keep the original start so the running
        // session is reattached, not reopened. Otherwise continue where it stopped.
        setStartPosition(selected,
            if (ownsSession(selected)) repository.sessionStartMs(selected.mediaKey)
            else repository.resumePositionMs(selected.mediaKey))
        if (mutableChannel.value?.chatId != selected.chatId) mutableChannel.value = null
        mutableChannelError.value = null
        mutableMedia.value = selected
        updateQueue(selected.mediaKey)
        // Load channel artwork and membership independently from the player start.
        viewModelScope.launch {
            try {
                val loaded = repository.channel(selected.chatId)
                if (mutableMedia.value?.chatId == selected.chatId) mutableChannel.value = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Channel action remains optional. */ }
        }
    }

    /** Neighbours from the page's queue only: no suggestions, no wrap-around. */
    private fun updateQueue(key: String) {
        val token = queueToken?.takeIf { playbackContext.contains(it, key) }
        if (token == null) {
            mutableQueue.value = TelegramQueueState()
            return
        }
        playbackContext.played(token, key)
        val next = playbackContext.nextLoaded(token, key)
        mutableQueue.value = TelegramQueueState(
            previous = playbackContext.previousLoaded(token, key),
            next = next,
            successorsLoading = next == null,
        )
        if (next != null) return
        successorJob = viewModelScope.launch {
            val loaded = try {
                playbackContext.next(token, key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (mutableMedia.value?.mediaKey == key) mutableQueue.value =
                mutableQueue.value.copy(next = loaded, successorsLoading = false)
        }
    }

    fun playNext(nextKey: String) {
        val state = mutableQueue.value
        if (state.next?.ratingKey != nextKey) return
        switchTo(nextKey, direction = 1)
    }

    fun playPrevious() {
        val previous = mutableQueue.value.previous ?: return
        switchTo(previous.ratingKey, direction = -1)
    }

    private fun switchTo(key: String, direction: Int) {
        val (chat, message) = telegramMediaIds(key) ?: return
        if (loadJob?.isActive == true) return // A switch is already on its way.
        // Keep the outgoing picture and OSD until the next post is ready; no Stop in between.
        transitionToken = BackgroundPlaybackSession.beginItemTransition()
        load(chat, message, direction)
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

    fun reportFailure(message: String) {
        mutableError.value = message
    }
}

/** Neighbours of the shown post in the page's queue. */
data class TelegramQueueState(
    val previous: MediaItem? = null,
    val next: MediaItem? = null,
    val successorsLoading: Boolean = false,
)

@Composable
fun TelegramPlayerScreen(
    onBack: () -> Unit,
    onOpenChannel: (Long) -> Unit,
    viewModel: TelegramPlayerViewModel = hiltViewModel(),
) {
    val media by viewModel.media.collectAsState()
    val failure by viewModel.error.collectAsState()
    val queueEnded by viewModel.queueEnded.collectAsState()
    val available = viewModel.repository.available
    LaunchedEffect(viewModel, available) {
        if (available) viewModel.start()
    }
    // Ran past the last playable post: stop and show the page, like the real end of the queue.
    LaunchedEffect(queueEnded) {
        if (queueEnded) {
            BackgroundPlaybackSession.stop()
            onBack()
        }
    }
    RegisterPlayerItemNavigation(viewModel, viewModel::playNext)
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
    val queue by viewModel.queue.collectAsState()
    val preferences = viewModel.preferences
    val metadata = remember(media.mediaKey, queue) {
        val base = media.playerMetadata()
        val next = queue.next
        base.copy(
            // Reopening from the mini player keeps the queue of this page.
            route = Destinations.telegramPlayer(media.chatId, media.messageId, viewModel.queueToken),
            nextUp = next?.let {
                NextUpCandidate(
                    mediaId = it.ratingKey,
                    title = it.title,
                    subtitle = it.parentTitle.orEmpty(),
                    artworkUrl = it.thumbUrl,
                    source = "telegram",
                    mediaType = if (it.type == "track") "audio" else "video",
                    durationMs = it.durationMs,
                )
            },
            successorsLoading = queue.successorsLoading,
            nextUpConfig = NextUpConfig(
                enabled = preferences.nextUpEnabled && viewModel.queueToken != null,
                leadSeconds = preferences.nextUpLeadSeconds,
                autoplay = preferences.nextUpAutoplayCollections,
                stillWatchingEvery = preferences.nextUpStillWatchingEpisodes,
                showOverlay = preferences.nextUpOverlayEnabled,
            ),
        )
    }
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
        onPlayNext = { candidate -> viewModel.playNext(candidate.mediaId) },
        onPlayPrevious = queue.previous?.let { { viewModel.playPrevious() } },
        onAutoplayChanged = { enabled -> preferences.nextUpAutoplayCollections = enabled },
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
                    hasPrevious = queue.previous != null,
                    hasNext = queue.next != null,
                    onPrevious = viewModel::playPrevious,
                    onNext = { queue.next?.let { viewModel.playNext(it.ratingKey) } },
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
            // Details dialog (OSD button Details or the Info key): only what the post carries.
            val locale = context.resources.configuration.locales[0]
            val playerDuration = if (ownsSession) snapshot.status.durationMs.coerceAtLeast(0L) else 0L
            com.ambient.tv.ui.player.PublishPlayerDetail(
                remember(media, channel, locale, playerDuration > 0L && media.durationMs <= 0L) {
                    com.ambient.tv.ui.player.playerDetailForTelegram(
                        media = media,
                        channel = channel,
                        formatDate = { ms ->
                            java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale,
                            ).format(java.util.Date(ms))
                        },
                        formatSize = { bytes -> android.text.format.Formatter.formatShortFileSize(context, bytes) },
                        playerDurationMs = playerDuration,
                    )
                },
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
                        com.ambient.tv.ui.player.PlayerDetailsButton()
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
