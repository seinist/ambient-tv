@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.ambient.tv.ui.player.PlayerBufferingSpinner
import com.ambient.tv.ui.player.waitingForPlaybackPicture
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
import com.ambient.tv.data.telegram.toMediaItem
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
    /**
     * Queue of the page the player was started from. Opened without one (Home hero, search, a
     * link), the post's channel becomes the queue, so Next and Next Up still work.
     */
    var queueToken: Long? = savedStateHandle.get<Long>("queue")?.takeIf { it >= 0L }
        private set
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
        val metadata = mutableMedia.value?.playerMetadata()
        if (!com.ambient.tv.ui.player.currentPlayerRendererAvailability(metadata).canSelect(requested)) return
        if (makeGlobal) preferences.playbackBackendKey = requested
        TitleVideoSettingsStore.rememberRenderer(context, metadata,
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
        // Like YouTube: a switch still loading when the player goes away must not linger.
        transitionToken?.let(BackgroundPlaybackSession::cancelSourceReplacement)
        transitionToken = null
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
        // A manual retry or another post ends a pending countdown (the countdown clears it first).
        floodRetryJob?.cancel()
        floodRetryJob = null
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
                    val token = queueToken
                    val following = if (direction == 0 || token == null) null else {
                        val key = telegramMediaKey(targetChat, targetMessage)
                        if (direction > 0) playbackContext.nextLoaded(token, key)
                            ?: playbackContext.next(token, key)
                        else playbackContext.previousLoaded(token, key)
                    }?.let { telegramMediaIds(it.ratingKey) }
                    if (following == null) {
                        if (direction > 0) {
                            // Autoplay or Next ran past the last playable post: the queue ended.
                            // End the started switch here, not only from the screen: a minimized
                            // or background player would otherwise stay "replacing" forever.
                            transitionToken?.let(BackgroundPlaybackSession::cancelSourceReplacement)
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
            } catch (wait: com.ambient.tv.data.telegram.TelegramFloodWaitException) {
                fail(context.getString(R.string.monogram_error_flood_wait, wait.seconds))
                retryAfterFloodWait(wait.seconds)
            } catch (lost: com.ambient.tv.data.telegram.TelegramSessionLostException) {
                fail(context.getString(R.string.monogram_error_session_lost, lost.type))
            } catch (error: Exception) {
                fail(error.message ?: context.getString(R.string.monogram_error_media_open))
            }
        }
    }

    private var floodRetryJob: Job? = null

    /** Counts a long FLOOD_WAIT down on screen, then opens the post again by itself. */
    private fun retryAfterFloodWait(seconds: Int) {
        floodRetryJob?.cancel()
        floodRetryJob = viewModelScope.launch {
            for (left in seconds downTo 1) {
                mutableError.value = context.getString(R.string.monogram_error_flood_wait, left)
                kotlinx.coroutines.delay(1_000)
            }
            floodRetryJob = null
            retry()
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
            startChannelQueue(key)
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

    /** No page queue: this post's channel, in channel order, continues the playback. */
    private fun startChannelQueue(key: String) {
        val (chat, _) = telegramMediaIds(key) ?: return
        successorJob?.cancel()
        successorJob = viewModelScope.launch {
            val section = "channel:$chat"
            val page = try {
                repository.browsePage(section)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            val items = page.items.map(TelegramMedia::toMediaItem)
            val token = playbackContext.start(
                scope = "player:$section", origin = items, selected = key,
                media = page.items.associateBy(TelegramMedia::mediaKey),
                section = section, cursor = page.nextCursor,
            ) ?: return@launch
            if (mutableMedia.value?.mediaKey != key) return@launch
            queueToken = token
            savedStateHandle["queue"] = token
            updateQueue(key)
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
        // Loading the post: a spinner, never the "module missing" panel for a moment.
        if (selected == null && failure == null && available) {
            // FullscreenSpinner is only the indicator: centre it on the screen.
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.ambient.tv.ui.common.FullscreenSpinner()
            }
        } else if (selected == null || failure != null) {
            // Like the spinner: centred on the whole screen (FLOOD_WAIT countdown, errors).
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BrowseStatusPanel(
                    title = stringResource(R.string.telegram_error),
                    body = failure ?: stringResource(R.string.settings_telegram_native_missing),
                    kind = if (failure == null) BrowseStatusKind.INFO else BrowseStatusKind.ERROR,
                    actionLabel = stringResource(R.string.back),
                    onAction = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
    /** The Media3 player of this post: its audio spectrum drives projectM for audio posts. */
    var media3Player by remember(media.mediaKey, renderer) {
        mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null)
    }
    val projectMBrowser = com.ambient.tv.ui.common.projectm.rememberProjectMBrowser(preferences.projectmPresetCategoryKey)
    val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    val presentation by BackgroundPlaybackSession.videoPresentation.collectAsState()
    val controls = rememberPlayerControlsVisible()
    var controlsVisible by controls
    val osd = rememberPlayerOsdController()
    val focus = rememberPlayerEngineFocus()
    val playFocus = rememberPlayerPlayFocus()
    val panelNavigation = rememberPlayerPanelNavigation()
    var panel by panelNavigation

    // Same rule as the other players: Back stops playback, except where playback may continue
    // in the background (phone audio, or "Back minimizes" switched on).
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val backMinimizes = com.ambient.tv.ui.player.rememberBackMinimizesPlayback()
    BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        if (controlsVisible && !handheldUi) {
            controlsVisible = false
            runCatching { focus.requestFocus() }
        } else if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(
                audioOnly = media.kind == TelegramMediaKind.AUDIO, handheld = handheldUi,
                minimizeOnBack = backMinimizes())) onBack()
        else BackgroundPlaybackSession.stop()
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
                        val player = PlaybackEngineFactory.create(
                            context = context,
                            streamUrl = "telegram://media/${media.chatId}/${media.messageId}",
                            startPositionMs = startPositionMs,
                            audioPassthroughEnabled = audioPassthrough,
                            metadata = metadata,
                            dataSourceFactory = TelegramVideoDataSource.Factory(repository, media),
                        )
                        // Media3 ends a failed read in STATE_IDLE with an error and nothing else:
                        // without this the screen stood at 0:00 without OSD and without a reason.
                        media3Player = player
                        player.addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                // The outgoing listener can still receive an error during a renderer hand-off.
                                if ((BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.Media3PlaybackHandle)?.player !== player) return
                                // Give a failed picture effect the same recovery as the other Media3 hosts.
                                if (com.ambient.tv.ui.player.Media3ImageProcessing.recoverFromEffectFailure(player, error)) return
                                com.ambient.tv.diagnostics.DiagnosticLog.error("monogram", "playback failed", error)
                                onFailure(telegramPlaybackErrorMessage(context, error) ?: playbackFailed)
                            }
                        })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onFailure(error.message ?: playbackFailed)
                    }
                }
            }
        }
        var surfaceFocused by remember { mutableStateOf(false) }
        val itemControls = com.ambient.tv.ui.player.LocalPlaybackItemControls.current
        Box(
            Modifier.fillMaxSize()
                // The persistent Media3 SurfaceView is rendered beneath this navigation
                // destination. Opaque player chrome would otherwise hide the video.
                .background(if (media.kind == TelegramMediaKind.VIDEO) Color.Transparent else Color.Black)
                .focusRequester(focus)
                .onFocusChanged { surfaceFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    // OK on the bare surface with the OSD up pauses; on an OSD button it is the button's.
                    if (event.type == KeyEventType.KeyDown && surfaceFocused && controlsVisible &&
                        event.key in setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)) {
                        BackgroundPlaybackSession.userToggle()
                        return@onPreviewKeyEvent true
                    }
                    // Everything else: the shared remote handling of all players, so Left/Right
                    // seek and Up/Down jump with the OSD hidden instead of only opening it.
                    val photo = media.kind == TelegramMediaKind.PHOTO
                    val owns = snapshot.metadata?.mediaId == media.mediaKey
                    val now = if (owns) snapshot.status.positionMs else 0L
                    val length = (if (owns) snapshot.status.durationMs.takeIf { it > 0 } else null) ?: metadata.durationMs
                    com.ambient.tv.ui.player.handlePlayerOsdKey(
                        event.key, event.type == KeyEventType.KeyDown,
                        com.ambient.tv.ui.player.OsdKeyState(
                            com.ambient.tv.ui.player.OsdContent.VIDEO,
                            controlsVisible && !osd.seekFeedback && !osd.closing,
                            panel != UnifiedPlayerPanel.NONE, osd.seekFeedback,
                            canSeekForward = !photo && length > 0, closing = osd.closing,
                        ),
                        com.ambient.tv.ui.player.OsdSeekSteps(BackgroundPlaybackSession.seekJumpMs(10_000L)),
                        object : com.ambient.tv.ui.player.OsdKeyTarget {
                            override fun isPlaying() = snapshot.playing
                            override fun togglePlayback() { BackgroundPlaybackSession.userToggle() }
                            override fun play() { BackgroundPlaybackSession.userPlay() }
                            override fun pause() { BackgroundPlaybackSession.userPause() }
                            override fun stop() { BackgroundPlaybackSession.stop() }
                            override fun seek(deltaMs: Long, revealOsd: Boolean) {
                                if (photo || !owns || length <= 0) { controlsVisible = true; osd.reveal(); return }
                                BackgroundPlaybackSession.userSeek()
                                BackgroundPlaybackSession.seekTo((now + deltaMs).coerceIn(0, length))
                                osd.interact()
                                if (revealOsd) { controlsVisible = true; osd.reveal() } else osd.showSeekFeedback()
                            }
                            override fun showOsd() { controlsVisible = true; osd.reveal() }
                            override fun hideSeekFeedback() { controlsVisible = true; osd.reveal() }
                            override fun restart() { itemControls?.previous?.invoke() }
                            override fun next() { itemControls?.next?.invoke() }
                            override fun previousTrack() { itemControls?.previous?.invoke() }
                            override fun zap(delta: Int) = Unit
                        },
                    )
                }.focusable(),
        ) {
            // Audio posts: projectM or the spectrum, as in the other players (Media3 only).
            val audioPlayer = media3Player
            if (media.kind == TelegramMediaKind.AUDIO && audioPlayer != null) {
                val mode = preferences.audioVisualizerModeKey
                when {
                    mode == "projectm" && com.ambient.tv.ui.common.projectm.ProjectMAvailability.isAvailable ->
                        com.ambient.tv.ui.common.projectm.ProjectMVisualizer(
                            analyzer = PlaybackEngineFactory.audioSpectrum(audioPlayer),
                            intervalSeconds = preferences.projectmPresetIntervalSeconds,
                            transitionSeconds = preferences.projectmTransitionSeconds,
                            qualityKey = preferences.projectmQualityKey,
                            categoryKey = preferences.projectmPresetCategoryKey,
                            showControls = controlsVisible,
                            browser = projectMBrowser,
                            modifier = Modifier.fillMaxSize(),
                        )
                    mode != "off" -> com.ambient.tv.ui.common.AudioPlaybackVisualizer(
                        Modifier.fillMaxSize(), PlaybackEngineFactory.audioSpectrum(audioPlayer))
                }
            }
            if (media.kind == TelegramMediaKind.PHOTO) {
                com.ambient.tv.ui.common.CrossfadeBackdropImage(
                    model = fullPhotoUrl ?: media.thumbnailUrl,
                    contentDescription = media.title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // The post may already be loaded while its stream/renderer is still opening.
                // Use the same delayed spinner as the other players, including rebuffering,
                // without showing it during a user pause or on a finished video.
                PlayerBufferingSpinner(
                    visible = monogramNeedsBufferingSpinner(
                        requestedMediaKey = media.mediaKey,
                        requestedRenderer = renderer,
                        activeMediaKey = snapshot.metadata?.mediaId,
                        activeRenderer = snapshot.backend,
                        replacingSource = snapshot.replacingSource,
                        wantsToPlay = snapshot.wantsToPlay,
                        status = snapshot.status,
                    ),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            val ownsSession = snapshot.metadata?.mediaId == media.mediaKey
            val position = if (ownsSession) snapshot.status.positionMs else 0L
            val duration = if (ownsSession) snapshot.status.durationMs.takeIf { it > 0 }
                ?: metadata.durationMs else metadata.durationMs
            val playing = ownsSession && snapshot.playing
            // Touch screens: a tap shows/hides the OSD, double taps seek, as in the other players.
            com.ambient.tv.ui.common.PlayerTouchLayer(
                onToggleControls = {
                    if (panel != UnifiedPlayerPanel.NONE) panelNavigation.close()
                    else if (osd.closing) osd.reveal() else controlsVisible = !controlsVisible
                },
                onTogglePlayback = {
                    BackgroundPlaybackSession.userToggle()
                    controlsVisible = true
                },
                onSeekBack = if (media.kind == TelegramMediaKind.PHOTO) null else ({
                    if (ownsSession && duration > 0) BackgroundPlaybackSession.seekTo(
                        (position - BackgroundPlaybackSession.seekJumpMs(10_000L)).coerceIn(0, duration))
                    controlsVisible = true
                }),
                onSeekForward = if (media.kind == TelegramMediaKind.PHOTO) null else ({
                    if (ownsSession && duration > 0) BackgroundPlaybackSession.seekTo(
                        (position + BackgroundPlaybackSession.seekJumpMs(10_000L)).coerceIn(0, duration))
                    controlsVisible = true
                }),
            )
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
                    projectMBrowser = if (media.kind == TelegramMediaKind.AUDIO) projectMBrowser else null,
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
                        formatDate = { ms -> com.ambient.tv.ui.common.relativePublishedLabel(ms, withTime = true) },
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
                    subtitle = media.channel,
                    // Monogram already provides a real post timestamp. Use the same localized,
                    // relative date formatting as its cards and details, beside the channel
                    // below the title, matching SmartTube's OSD.
                    subtitleTrailingText = media.publishedAtMs.takeIf { it > 0L }?.let {
                        com.ambient.tv.ui.common.relativePublishedLabel(it)
                    },
                    description = media.summary,
                    artworkUrl = media.cardImageUrl ?: media.thumbnailUrl,
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
                    // Telegram posts carry no stream metadata: show what the engine decodes.
                    if (media.kind != TelegramMediaKind.PHOTO) com.ambient.tv.ui.player.SessionFormatBadges(
                        com.ambient.tv.ui.player.rememberSessionFormat(media.mediaKey))
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
                            imageUrl = channel.avatarUrl,
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

/**
 * The selected Monogram post/renderer may not own the shared playback session yet:
 * opening its authenticated stream (or switching source/backend) is still loading.
 * Once acquired, use the same play-intent-based buffering rule as the other renderers.
 */
internal fun monogramNeedsBufferingSpinner(
    requestedMediaKey: String,
    requestedRenderer: String,
    activeMediaKey: String?,
    activeRenderer: String,
    replacingSource: Boolean,
    wantsToPlay: Boolean,
    status: com.ambient.tv.ui.player.PlaybackStatus,
): Boolean {
    if (activeMediaKey != requestedMediaKey ||
        activeRenderer != requestedRenderer || replacingSource) return true
    return !status.ended && status.error == null &&
        waitingForPlaybackPicture(wantsToPlay, status.playing)
}

/** The Telegram cause behind a Media3 error (e.g. FLOOD_WAIT), readable for the error panel. */
internal fun telegramPlaybackErrorMessage(
    context: android.content.Context,
    error: Throwable,
): String? {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    causes.firstNotNullOfOrNull { it as? com.ambient.tv.data.telegram.TelegramFloodWaitException }?.let {
        return context.getString(R.string.monogram_error_flood_wait, it.seconds)
    }
    return causes.lastOrNull { !it.message.isNullOrBlank() && it !is java.io.IOException }?.message
        ?: causes.lastOrNull { !it.message.isNullOrBlank() }?.message
}
