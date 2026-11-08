@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import com.ambient.tv.ui.common.requestHeaders
import coil3.request.allowHardware
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.ambient.tv.R
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.MainActivity
import com.ambient.tv.ui.mobile.HandheldActivity
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubePlayback
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.car.CarAudioIds
import com.ambient.tv.data.telegram.playerMetadata
import com.ambient.tv.car.CarAudioCatalog
import com.ambient.tv.car.CarAudioSelection
import com.ambient.tv.car.PlaybackLibraryCallback
import com.ambient.tv.car.applyCarRadioMetadata
import com.ambient.tv.car.carLiveMediaId
import com.ambient.tv.car.carLiveMetadataMatches
import com.ambient.tv.car.carLiveNowPlayingMetadata
import com.ambient.tv.car.playerMetadata
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.data.radio.localizedLanguageNames
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.spotify.SpotifyPlaybackState
import com.ambient.tv.ui.navigation.Destinations
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.ByteArrayOutputStream

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var files: com.ambient.tv.data.files.FilesRepository
    @Inject lateinit var repository: MediaRepository
    @Inject lateinit var youtube: SmartTubeEngine
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var launcher: com.ambient.tv.launcher.LauncherPublisher
    @Inject lateinit var radioPlaybackLauncher: com.ambient.tv.ui.radio.RadioPlaybackLauncher
    @Inject lateinit var radioQueue: com.ambient.tv.ui.radio.RadioPlaybackQueue
    @Inject internal lateinit var carCatalog: CarAudioCatalog
    @Inject lateinit var spotify: SpotifyRepository
    // Resolved only for a Telegram playback: other sources never touch the repository.
    @Inject lateinit var telegram: javax.inject.Provider<com.ambient.tv.data.telegram.TelegramRepository>
    private var carPlaybackJob: Job? = null
    private var carSelectionVersion = 0L
    private var carPlaybackResult: SettableFuture<Void>? = null
    private var carSelectionItemId: String? = null
    private var carSelectionGeneration: Long? = null
    private var carPlaybackError: String? = null
    private var destroying = false
    private val carSpotifySelection = CarSpotifySelection()
    private val carSpotifySelected: Boolean get() = carSpotifySelection.active
    private var carSpotifyStartGeneration = -1L
    private data class PendingSpotifyStart(val request: CarSpotifySelection.Request, val operation: Job)
    private var pendingSpotifyStart: PendingSpotifyStart? = null
    private val sponsorSkip = BackgroundSponsorSkipPolicy()
    private var carMusicQueue: List<String> = emptyList()
    private var carLiveQueue: List<String> = emptyList()
    private var carLiveMetadataJob: Job? = null
    /** Car item IDs of the browsed Telegram audio posts; the playing post is found by mediaKey. */
    private var carTelegramQueue: List<String> = emptyList()
    private var foregroundStarted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var sessionPlayer: SessionPlaybackPlayer
    private lateinit var libraryCallback: PlaybackLibraryCallback
    private var observedPlaybackGeneration: Long? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wakeLockRenewedAt = 0L
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var focusRetries = 0
    private var focusRetryPending = false
    private var lastStartId = 0
    private var lastReport = 0L
    private var lastNotification: Triple<Long, PlaybackStatus, Int?>? = null
    private var notificationMetadata: Triple<String?, String?, Long>? = null
    private var artworkBitmap: Bitmap? = null
    private var artworkData: ByteArray? = null
    private var artworkRequestKey: String? = null
    private var artworkJob: Job? = null
    private val markedWatched = mutableSetOf<Long>()
    private val watchedFailureNotified = mutableSetOf<Long>()
    private var lastRecentChannel: Pair<String, String>? = null
    private val reports = Channel<Unit>(Channel.CONFLATED)
    private val reportQueue = PlaybackReportQueue<BackgroundPlaybackSession.Snapshot>()
    private var reporting = false
    private var reportInFlight: PlaybackReportQueue.Entry<BackgroundPlaybackSession.Snapshot>? = null
    private val positions = PlaybackPositionMemory()
    private var lastReportState: Triple<Long, Boolean, Boolean>? = null
    private var focusEpoch = 0L
    private var retiredGeneration: Long? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (PlaybackOutputTransition.suppressNoisy(context)) {
                Timber.i("Keeping play intent during requested TV output reconfiguration")
                return
            }
            resumeOnFocus = false
            BackgroundPlaybackSession.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.background_playback), NotificationManager.IMPORTANCE_LOW))
        sessionPlayer = SessionPlaybackPlayer(Looper.getMainLooper(), object : SessionPlaybackPlayer.Actions {
            override fun onPauseRequested() {
                libraryCallback.cancelPendingSelections()
                // Backends start when selected. Cancel an unfinished handoff before pausing
                // so a delayed Spotify/radio response cannot briefly start audio after Pause.
                cancelCarSelection()
            }
            override fun play() {
                carPlaybackError = null
                if (carSpotifySelected) spotify.play() else BackgroundPlaybackSession.userPlay()
            }
            override fun pause() {
                libraryCallback.cancelPendingSelections()
                resumeOnFocus = false
                if (carSpotifySelected) spotify.pause() else BackgroundPlaybackSession.userPause()
            }
            override fun stop() = stopSessionPlayback()
            override fun seekTo(positionMs: Long) = seekSessionPlayback(positionMs)
            override fun skip(next: Boolean) = skipCarAudio(next)
            override fun select(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
                selectCarMediaItems(mediaItems, startIndex, startPositionMs)
        })
        libraryCallback = PlaybackLibraryCallback(carCatalog, scope, getString(R.string.app_name),
            onSelectionRequested = { cancelCarSelection() },
        ) { session, controller ->
            // Media3 validates package/UID ownership before passing real controllers here.
            // Its notification controller is internal; all other callers need media-control trust.
            session.isMediaNotificationController(controller) || controller.isTrusted ||
                controller.uid == applicationInfo.uid
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, libraryCallback)
            .setId("AmbientTvPlayback")
            .setSessionActivity(openPlaybackIntent())
            .build()
        audioManager = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ambient TV:Playback").apply { setReferenceCounted(false) }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        addSession(mediaSession)
        // A browser-only bind must not start a foreground service. The explicit playback
        // start goes through onStartCommand and meets the Android 12+ foreground deadline there.
        // Synchronize focus on play/pause immediately, not at the next polling tick.
        scope.launch {
            BackgroundPlaybackSession.snapshot.collect { state ->
                if (state.active && observedPlaybackGeneration != state.generation) {
                    // A source chosen in the app also supersedes a still-resolving Auto request.
                    libraryCallback.cancelPendingSelections()
                    if (carPlaybackJob?.isActive == true && carSelectionGeneration != state.generation &&
                        !matchesCarSelection(state)) cancelCarSelection()
                    observedPlaybackGeneration = state.generation
                    carPlaybackError = null
                }
                if (carSpotifySelected && state.active && state.generation != carSpotifyStartGeneration) {
                    carSpotifySelection.clear()
                }
                if (!carSpotifySelected) updateSessionState(state)
                else if (!state.active) {
                    // Spotify has its own Connect foreground service. A browser connection
                    // must not keep an obsolete radio/media foreground notification alive.
                    if (wakeLock.isHeld) wakeLock.release()
                    abandonFocus()
                    clearPlaybackNotification()
                }
            }
        }
        scope.launch {
            spotify.playback.collect { state ->
                if (carSpotifySelected) publishCarSpotify(state)
            }
        }
        scope.launch {
            for (signal in reports) {
                while (true) {
                    val queued = reportQueue.take() ?: break
                    reporting = true
                    reportInFlight = queued
                    try {
                        withTimeout(8_000) { report(queued.value, queued.stopped) }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        Timber.w("Playback progress reporting timed out")
                        if (queued.stopped) queued.value.metadata?.let { notifyNotSaved(it) }
                    } finally {
                        reporting = false
                        reportInFlight = null
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                BackgroundPlaybackSession.tick()
                val playback = BackgroundPlaybackSession.snapshot.value
                // Remembered for every source and renderer, also while playing on in the
                // background after the player screen was left.
                playback.metadata?.takeIf { !playback.replacingSource }?.let {
                    positions.record(playback.generation, playback.status.positionMs,
                        playback.status.positionMs + it.timelineOffsetMs)
                }
                val liveMetadata = playback.metadata?.takeIf { it.isLive && it.historyScope != null }
                val recentChannel = liveMetadata?.historyScope?.let { it to liveMetadata.mediaId }
                if (playback.playing && playback.status.error == null && recentChannel != null && recentChannel != lastRecentChannel) {
                    preferences.recordWatchedTvChannel(recentChannel.first, recentChannel.second)
                    lastRecentChannel = recentChannel
                } else if (!playback.active || liveMetadata == null) lastRecentChannel = null
                val youtubeState = playback.metadata?.resumeState as? YouTubePlayback
                if (playback.metadata?.sponsorSkipEnabled == true && preferences.youtubeSponsorBlockEnabled &&
                    youtubeState?.isLive == false) {
                    // The service is the sole SponsorBlock skipper (including background/PiP).
                    // Never seek to the exact end of a DASH/HLS stream or repeat the same segment.
                    val duration = playback.metadata.durationMs.takeIf { it > 0 }
                        ?: playback.status.durationMs
                    sponsorSkip.nextSeek(
                        playback.generation, youtubeState.sponsorSegments, playback.status.positionMs,
                        duration, playback.playing, preferences.youtubeSponsorBlockEffectiveModes,
                    )?.let { BackgroundPlaybackSession.seekTo(it, automatic = true) }
                }
                for (finished in BackgroundPlaybackSession.takeCompleted()) enqueueReport(finished, stopped = true)
                val state = BackgroundPlaybackSession.snapshot.value
                if (!state.active) {
                    // Allow a same-frame renderer/source replacement to acquire its next engine.
                    delay(500)
                    // A short session may start and stop during the grace period. Drain its
                    // final position before deciding that the service has nothing left to save.
                    for (finished in BackgroundPlaybackSession.takeCompleted()) enqueueReport(finished, stopped = true)
                    val idle = BackgroundPlaybackSession.snapshot.value
                    if (!idle.active && carPlaybackJob?.isActive != true &&
                        reportQueue.isEmpty && !reporting && lastStartId != 0 && stopSelfResult(lastStartId)) {
                        retiredGeneration = idle.generation
                        lastStartId = 0
                        // A connected browser can keep this instance bound after stopSelfResult.
                        // Keep polling so a later selection also ticks and saves its progress.
                    }
                } else {
                    val reportState = Triple(state.generation, state.playing, state.status.ended || state.status.error != null)
                    if (!state.replacingSource && (lastReportState != reportState || SystemClock.elapsedRealtime() - lastReport >= 10_000)) {
                        enqueueReport(state, stopped = state.status.ended || state.status.error != null)
                        lastReport = SystemClock.elapsedRealtime()
                        lastReportState = reportState
                    }
                    delay(500)
                }
            }
        }
    }

    private fun enqueueReport(state: BackgroundPlaybackSession.Snapshot, stopped: Boolean) {
        if (state.replacingSource) return
        if (state.metadata?.isTrailer == true) return
        if (state.metadata?.liveTvSession == null && state.metadata?.source !in setOf("plex", "jellyfin", "emby", "dlna", "youtube", "files", "telegram")) return
        reportQueue.put(state.generation, state, stopped)
        reports.trySend(Unit)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        retiredGeneration = null
        // Actual service starts must meet the foreground deadline, including media-button starts.
        // A browse-only connection never calls onStartCommand or displays this notification.
        ensureForeground(BackgroundPlaybackSession.snapshot.value)
        super.onStartCommand(intent, flags, startId) // Media3 dispatches platform media-button intents.
        if (intent?.action in setOf(ACTION_TOGGLE, ACTION_STOP, ACTION_PREVIOUS, ACTION_NEXT)) {
            BackgroundPlaybackSession.registerUserInteraction()
        }
        when (intent?.action) {
            ACTION_TOGGLE -> if (sessionPlayer.playWhenReady) sessionPlayer.pause() else sessionPlayer.play()
            ACTION_STOP -> sessionPlayer.stop()
            ACTION_PREVIOUS -> skipCarAudio(next = false)
            ACTION_NEXT -> skipCarAudio(next = true)
            ACTION_EXTEND_SLEEP -> BackgroundPlaybackSession.extendSleepTimer()
        }
        if (carSpotifySelected && !BackgroundPlaybackSession.snapshot.value.active) clearPlaybackNotification()
        return START_NOT_STICKY // Never restart an unknown stream after process death.
    }

    private fun ensureForeground(state: BackgroundPlaybackSession.Snapshot) {
        if (foregroundStarted || destroying) return
        val initial = notification(state)
        if (Build.VERSION.SDK_INT >= 29) startForeground(
            NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        ) else startForeground(NOTIFICATION, initial)
        foregroundStarted = true
    }

    /** Own the existing notification, sleep controls and Spotify handoff as one lifecycle. */
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void?> {
        val state = BackgroundPlaybackSession.snapshot.value
        if (!destroying && !carSpotifySelected && startInForegroundRequired &&
            (state.active || sessionPlayer.hasPreparedSelection || sessionPlayer.isSelecting)) {
            ensureForeground(state)
        }
        // State collectors publish our notification. Calling the default implementation would
        // create a second notification and start a second FGS for mirrored Spotify playback.
        return Futures.immediateVoidFuture()
    }

    private fun clearPlaybackNotification() {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        lastNotification = null
        notificationMetadata = null
    }

    private suspend fun report(state: BackgroundPlaybackSession.Snapshot, stopped: Boolean) {
        val metadata = state.metadata ?: return
        if (metadata.isTrailer) return
        val position = positions.finalPosition(state.generation,
            state.requestedTimelinePositionMs ?: (state.status.positionMs + metadata.timelineOffsetMs),
            state.status.positionMs, requestedSeek = state.requestedTimelinePositionMs != null,
            ended = state.status.ended, stopped = stopped)
        val duration = metadata.durationMs.takeIf { it > 0 }
            ?: state.status.durationMs.takeIf { it > 0 }?.plus(metadata.timelineOffsetMs) ?: 0L
        val completed = metadata.hasVideo && !metadata.isLive && playbackIsComplete(
            position, duration, state.status.ended && state.status.error == null, metadata.playbackSegments)
        try {
            metadata.liveTvSession?.let {
                it.report(position, state.playing, stopped)
                return
            }
            when (metadata.source) {
                "plex", "jellyfin", "emby", "dlna" -> {
                    val reported = repository.reportProgress(metadata.mediaId, position,
                        if (stopped) "stopped" else if (state.playing) "playing" else "paused")
                    if (stopped && reported.isFailure) notifyNotSaved(metadata)
                    // Launcher "Continue watching": updated when playback stops, not on every report.
                    // Videos only: a stopped song or audiobook is not something to "watch" next.
                    if (stopped && metadata.source != "dlna" && metadata.hasVideo) launcher.onPlaybackStopped(
                        com.ambient.tv.launcher.LauncherTitle(
                            key = metadata.mediaId,
                            title = metadata.title,
                            subtitle = metadata.subtitle.takeIf { it.isNotBlank() },
                            artworkUrl = metadata.artworkUrl,
                            artworkHeaders = metadata.artworkHeaders,
                            episode = metadata.subtitle.isNotBlank(),
                            landscapeArtwork = metadata.artworkAspectRatio > 1f,
                            durationMs = duration,
                            positionMs = position,
                        ),
                        finished = completed,
                    )
                    if ((completed || (!metadata.hasVideo && duration > 0 && position.toDouble() / duration >= .9)) &&
                        state.generation !in markedWatched) {
                        if (repository.markWatched(metadata.mediaId).isSuccess) markedWatched.add(state.generation)
                        // Retried with every report; announced once per playback.
                        else if (watchedFailureNotified.add(state.generation)) notifyNotSaved(metadata, watched = true)
                    }
                }
                "files" -> files.updateProgress(metadata.mediaId, position, state.status.durationMs.takeIf { it > 0 } ?: metadata.durationMs,
                    ended = if (metadata.hasVideo) completed else state.status.ended && state.status.error == null,
                    streamUrl = (metadata.resumeState as? PlayerUiState.Ready)?.streamUrl)
                // YouTube live clocks are relative to a changing HLS window. Sending them
                // as watch progress creates bogus "continue at saved position" entries.
                "youtube" -> if (!metadata.isLive) {
                    com.ambient.tv.youtube.youtubeProgressReport(
                        positionMs = position,
                        durationMs = duration,
                        ended = state.status.ended,
                        failed = state.status.error != null,
                        explicitSeek = state.requestedTimelinePositionMs != null,
                        completedByNavigation = state.completedByNavigation,
                    )?.let { progress ->
                        youtube.updateHistory(metadata.mediaId, progress.positionMs, duration, progress.completed)
                    }
                }
                // Telegram keeps its resume point on the device and needs only the final position;
                // an unknown one (renderer at 0) was already replaced by the last position seen.
                "telegram" -> if (stopped) telegram.get().rememberResumePosition(metadata.mediaId,
                    if (state.status.ended && state.status.error == null) duration else position, duration)
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Timber.w(error, "Background playback progress report failed")
            if (stopped) notifyNotSaved(metadata)
        }
    }

    /** Server sources only: files and SmartTube keep their history on the device. */
    private fun notifyNotSaved(metadata: PlaybackMetadata, watched: Boolean = false) {
        if (metadata.source !in setOf("plex", "jellyfin", "emby")) return
        com.ambient.tv.notifications.notifyProgressNotSaved(metadata.source, metadata.mediaId, metadata.title, watched)
    }

    private fun playRadioAdjacent(next: Boolean) {
        if (BackgroundPlaybackSession.snapshot.value.metadata?.source != "radio") return
        val station = (if (next) radioQueue.next() else radioQueue.previous()) ?: return
        libraryCallback.cancelPendingSelections()
        launchCarSelection(CarAudioIds.station(station.id)) { version, generation ->
            checkCarSelection(version, generation)
            // Preserve the queue of the currently playing row, including UI-started radio.
            radioPlaybackLauncher.play(station).getOrThrow()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession
    // Legacy browser binds initially carry a synthetic controller with no useful UID. Actual
    // controller authorization belongs to PlaybackLibraryCallback.onConnectAsync/onGetLibraryRoot.

    private fun cancelCarSelection() {
        carSelectionVersion++
        val spotifyStart = pendingSpotifyStart
        pendingSpotifyStart = null
        // The repository launches Spotify work in its own scope. Cancel it and restore the
        // owner synchronously: coroutine cancellation can unwind after the next Pause/Stop.
        spotifyStart?.operation?.cancel()
        val restoredOwner = spotifyStart?.takeIf { carSpotifySelection.rollbackFailed(it.request) }
        if (restoredOwner != null) {
            // An accepted Connect command can outlive its HTTP coroutine. Silence that receiver
            // as well; a stale request must never stop a newer confirmed Spotify selection.
            if (restoredOwner.request.previousActive) spotify.pause() else spotify.stop()
        }
        val result = carPlaybackResult
        val job = carPlaybackJob
        carPlaybackResult = null
        carPlaybackJob = null
        carSelectionItemId = null
        carSelectionGeneration = null
        // Cancel the returned future immediately, even if its coroutine is still unwinding.
        // The adapter can then discard deferred Play before executing the user's Pause/Stop.
        result?.cancel(false)
        job?.cancel()
        if (!destroying && restoredOwner != null) {
            if (carSpotifySelected) publishCarSpotify(spotify.playback.value)
            else updateSessionState(BackgroundPlaybackSession.snapshot.value)
        }
        if (!destroying && !BackgroundPlaybackSession.snapshot.value.active) clearPlaybackNotification()
    }

    private fun matchesCarSelection(state: BackgroundPlaybackSession.Snapshot): Boolean {
        val id = carSelectionItemId ?: return false
        val metadata = state.metadata ?: return false
        return when (metadata.source) {
            "radio" -> CarAudioIds.stationId(id) == metadata.mediaId
            "telegram" -> CarAudioIds.telegramItemId(id) == com.ambient.tv.data.telegram.telegramMediaIds(metadata.mediaId)
            "iptv", "tvheadend" -> id == carLiveMediaId(metadata)
            "plex", "jellyfin", "emby", "dlna", "files" -> CarAudioIds.musicItemId(id) == metadata.mediaId
            else -> false
        }
    }

    private suspend fun checkCarSelection(version: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (version != carSelectionVersion || BackgroundPlaybackSession.snapshot.value.generation != generation) {
            throw CancellationException("Playback selection replaced")
        }
    }

    private fun stopSessionPlayback() {
        val wasSpotifySelected = carSpotifySelected
        libraryCallback.cancelPendingSelections()
        cancelCarSelection()
        resumeOnFocus = false
        carPlaybackError = null
        if (wasSpotifySelected || carSpotifySelected) {
            carSpotifySelection.clear()
            spotify.stop()
        }
        BackgroundPlaybackSession.stop()
        updateSessionState(BackgroundPlaybackSession.snapshot.value)
    }

    private fun seekSessionPlayback(positionMs: Long) {
        if (carSpotifySelected) {
            spotify.seek(positionMs.coerceAtLeast(0L))
            return
        }
        if (!BackgroundPlaybackSession.canSeekFromMediaSession()) return
        BackgroundPlaybackSession.userSeek()
        val offset = BackgroundPlaybackSession.snapshot.value.metadata?.timelineOffsetMs ?: 0L
        BackgroundPlaybackSession.seekTo((positionMs - offset).coerceAtLeast(0L))
    }

    private fun requestCarAudioPlayback(itemId: String, liveQueue: List<String>? = null) {
        libraryCallback.cancelPendingSelections()
        selectCarMediaItems(listOf(MediaItem.Builder().setMediaId(itemId).build()), 0, C.TIME_UNSET, liveQueue)
    }

    /** Real playback starts only on Play. The adapter keeps prepare-only selections silent. */
    private fun selectCarMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        liveQueue: List<String>? = null,
    ): ListenableFuture<Void> {
        if (mediaItems.size != 1 || startIndex !in listOf(0, C.INDEX_UNSET)) {
            return Futures.immediateFailedFuture(IllegalArgumentException("Select one audio item"))
        }
        return launchCarSelection(mediaItems[0].mediaId) { version, generation ->
            val resolved = carCatalog.resolve(mediaItems[0].mediaId)
                ?: throw IllegalArgumentException("Unavailable audio item")
            // Next/Previous retains the originating group even beyond the currently browsed page.
            val selection = if (resolved is CarAudioSelection.Live && liveQueue != null &&
                mediaItems[0].mediaId in liveQueue) resolved.copy(queue = liveQueue)
                else resolved
            checkCarSelection(version, generation)
            startCarAudio(selection, version, generation)
            if (version != carSelectionVersion) throw CancellationException("Playback selection replaced")
            if (startPositionMs != C.TIME_UNSET) seekSessionPlayback(startPositionMs)
        }
    }

    private fun launchCarSelection(
        itemId: String,
        start: suspend (version: Long, generation: Long) -> Unit,
    ): ListenableFuture<Void> {
        cancelCarSelection()
        val version = carSelectionVersion
        val generation = BackgroundPlaybackSession.snapshot.value.generation
        carSelectionItemId = itemId
        carSelectionGeneration = generation
        val result = SettableFuture.create<Void>()
        carPlaybackResult = result
        carPlaybackError = null
        // This is an explicit Play/Next, after prepare-only catalog resolution has completed.
        // Enter foreground before another RPC can consume Android's media-start exemption.
        ensureForeground(BackgroundPlaybackSession.snapshot.value)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                start(version, generation)
                if (version != carSelectionVersion) throw CancellationException("Playback selection replaced")
                if (carSpotifySelected) {
                    publishCarSpotify(spotify.playback.value)
                    if (!BackgroundPlaybackSession.snapshot.value.active) clearPlaybackNotification()
                } else updateSessionState(BackgroundPlaybackSession.snapshot.value)
                result.set(null)
            } catch (cancelled: CancellationException) {
                result.cancel(false)
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Android Auto playback selection failed")
                if (version == carSelectionVersion) {
                    carPlaybackError = if (carSpotifySelected || BackgroundPlaybackSession.snapshot.value.active) null
                        else getString(R.string.player_load_failed)
                    if (carSpotifySelected) publishCarSpotify(spotify.playback.value)
                    else updateSessionState(BackgroundPlaybackSession.snapshot.value)
                }
                result.setException(error)
            } finally {
                if (!destroying && version == carSelectionVersion &&
                    !BackgroundPlaybackSession.snapshot.value.active) clearPlaybackNotification()
            }
        }
        carPlaybackJob = job
        result.addListener({ if (result.isCancelled) job.cancel() }, ContextCompat.getMainExecutor(this))
        job.invokeOnCompletion { cause -> if (cause != null) result.cancel(false) }
        job.start()
        return result
    }

    private suspend fun startCarAudio(selection: CarAudioSelection, version: Long, generation: Long) {
        carLiveMetadataJob?.cancel()
        carLiveMetadataJob = null
        when (selection) {
            is CarAudioSelection.Radio -> {
                if (carSpotifySelected) spotify.pause()
                carSpotifySelection.clear()
                carMusicQueue = emptyList()
                carLiveQueue = emptyList()
                carTelegramQueue = emptyList()
                radioPlaybackLauncher.play(selection.station, selection.queue).getOrThrow()
                carCatalog.invalidate(CarAudioIds.RADIO_RECENT)
                mediaSession.notifyChildrenChanged(CarAudioIds.RADIO_RECENT, Int.MAX_VALUE, null)
            }
            is CarAudioSelection.Spotify -> {
                carSpotifyStartGeneration = BackgroundPlaybackSession.snapshot.value.generation
                val start = carSpotifySelection.begin()
                carMusicQueue = emptyList()
                carLiveQueue = emptyList()
                carTelegramQueue = emptyList()
                sessionPlayer.update(SessionPlaybackState(
                    mediaItem = MediaItem.Builder().setMediaId(selection.uri)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Spotify").setIsPlayable(true).build()).build(),
                    uid = selection.uri,
                    playWhenReady = true,
                    buffering = true,
                ))
                val completed = CompletableDeferred<Boolean>()
                val operation = spotify.playUri(selection.uri) { success -> completed.complete(success) }
                val pending = PendingSpotifyStart(start, operation)
                pendingSpotifyStart = pending
                try {
                    if (completed.await()) carSpotifySelection.confirm(start)
                    else {
                        carSpotifySelection.rollbackFailed(start)
                        throw IllegalStateException("Spotify playback could not start")
                    }
                } catch (cancelled: CancellationException) {
                    carSpotifySelection.rollbackFailed(start)
                    throw cancelled
                } finally {
                    if (pendingSpotifyStart === pending) pendingSpotifyStart = null
                    if (!completed.isCompleted) operation.cancel()
                }
            }
            is CarAudioSelection.Library -> {
                if (carSpotifySelected) spotify.pause()
                carSpotifySelection.clear()
                val item = selection.item
                val url = item.streamUrl ?: return
                carMusicQueue = selection.queue
                carLiveQueue = emptyList()
                carTelegramQueue = emptyList()
                repository.preparePlayback(item.ratingKey, 0)
                checkCarSelection(version, generation)
                val source = when {
                    JellyfinBackend.owns(item.ratingKey) -> "jellyfin"
                    EmbyBackend.owns(item.ratingKey) -> "emby"
                    DlnaKeys.owns(item.ratingKey) -> "dlna"
                    FileKeys.owns(item.ratingKey) -> "files"
                    else -> "plex"
                }
                PlaybackEngineFactory.create(
                    context = this,
                    streamUrl = url,
                    startPositionMs = item.viewOffsetMs?.coerceAtLeast(0L) ?: 0L,
                    metadata = PlaybackMetadata(
                        route = Destinations.player(item.ratingKey),
                        title = item.title,
                        subtitle = item.parentTitle.orEmpty(),
                        source = source,
                        mediaId = item.ratingKey,
                        hasVideo = false,
                        artworkUrl = item.thumbUrl,
                        durationMs = item.durationMs ?: 0L,
                    ),
                    audioOnly = true,
                )
            }
            is CarAudioSelection.Telegram -> {
                if (carSpotifySelected) spotify.pause()
                carSpotifySelection.clear()
                carMusicQueue = emptyList()
                carLiveQueue = emptyList()
                carTelegramQueue = selection.queue
                val media = selection.media
                PlaybackEngineFactory.create(
                    context = this,
                    streamUrl = selection.url,
                    startPositionMs = telegram.get().resumePositionMs(media.mediaKey),
                    metadata = media.playerMetadata().copy(hasVideo = false),
                    audioOnly = true,
                )
            }
            is CarAudioSelection.Live -> {
                if (carSpotifySelected) spotify.pause()
                carSpotifySelection.clear()
                carMusicQueue = emptyList()
                carLiveQueue = selection.queue
                carTelegramQueue = emptyList()
                val stream = selection.stream
                val channel = selection.channel
                val playbackId = carLiveQueue.firstOrNull {
                    CarAudioIds.liveAudioSpec(it)?.second == channel.uuid
                } ?: return
                val player = PlaybackEngineFactory.create(
                    context = this,
                    streamUrl = stream.url,
                    startPositionMs = 0L,
                    streamHeaders = stream.headers,
                    mimeType = com.ambient.tv.ui.tvheadend.liveTvMedia3MimeType(stream.url),
                    metadata = selection.playerMetadata(playbackId, preferences),
                    audioOnly = true,
                    liveStream = true,
                )
                observeCarLiveRadio(player, playbackId)
            }
        }
    }

    /** Enrich a service-started radio without delaying its tune or opening a second stream. */
    private fun observeCarLiveRadio(player: androidx.media3.exoplayer.ExoPlayer, playbackId: String) {
        val generation = BackgroundPlaybackSession.snapshot.value.generation
        val stationName = BackgroundPlaybackSession.snapshot.value.metadata?.title.orEmpty()
        var programmes = emptyList<com.ambient.tv.livetv.LiveTvProgramme>()
        var streamTitle: String? = null
        fun isCurrent(): Boolean {
            val snapshot = BackgroundPlaybackSession.snapshot.value
            return snapshot.active && carLiveMetadataMatches(snapshot.metadata, snapshot.generation, generation, playbackId)
        }
        fun publish() {
            if (!isCurrent()) return
            val metadata = BackgroundPlaybackSession.snapshot.value.metadata ?: return
            val updated = carLiveNowPlayingMetadata(metadata, programmes, System.currentTimeMillis() / 1_000L, streamTitle)
            if (updated != metadata) BackgroundPlaybackSession.refreshMetadata(updated)
        }
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (!isCurrent()) return
                streamTitle = com.ambient.tv.ui.radio.radioMetadataNowPlaying(
                    mediaMetadata.title, mediaMetadata.artist, stationName,
                )
                publish()
            }

            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                if (!isCurrent()) return
                val icy = (0 until metadata.length())
                    .mapNotNull { metadata.get(it) as? androidx.media3.extractor.metadata.icy.IcyInfo }
                    .lastOrNull() ?: return
                streamTitle = com.ambient.tv.ui.radio.radioNowPlayingTitle(icy.title, stationName)
                publish()
            }
        }
        player.addListener(listener)
        listener.onMediaMetadataChanged(player.mediaMetadata)
        carLiveMetadataJob = scope.launch {
            try {
                while (!destroying && isCurrent()) {
                    programmes = com.ambient.tv.util.suspendResult {
                        withContext(Dispatchers.IO) { carCatalog.liveProgrammes(playbackId) }
                    }.getOrDefault(programmes)
                    publish()
                    delay(30_000L)
                }
            } finally {
                player.removeListener(listener)
            }
        }
    }

    private fun carTelegramIndex(mediaKey: String?): Int {
        val ids = mediaKey?.let { com.ambient.tv.data.telegram.telegramMediaIds(it) } ?: return -1
        return carTelegramQueue.indexOf(CarAudioIds.telegramItem(ids.first, ids.second))
    }

    private fun skipCarAudio(next: Boolean) {
        libraryCallback.cancelPendingSelections()
        cancelCarSelection()
        if (carSpotifySelected) {
            if (next) spotify.next() else spotify.previous()
            return
        }
        val current = BackgroundPlaybackSession.snapshot.value.metadata
        if (current?.source == "radio") {
            playRadioAdjacent(next)
            return
        }
        val liveIndex = carLiveQueue.indexOf(carLiveMediaId(current))
        val liveTarget = if (next) liveIndex + 1 else liveIndex - 1
        if (liveIndex >= 0 && liveTarget in carLiveQueue.indices) {
            requestCarAudioPlayback(carLiveQueue[liveTarget], liveQueue = carLiveQueue)
            return
        }
        val telegramIndex = carTelegramIndex(current?.mediaId)
        val telegramTarget = if (next) telegramIndex + 1 else telegramIndex - 1
        if (telegramIndex >= 0 && telegramTarget in carTelegramQueue.indices) {
            requestCarAudioPlayback(carTelegramQueue[telegramTarget])
            return
        }
        val index = carMusicQueue.indexOf(current?.mediaId)
        val target = if (next) index + 1 else index - 1
        if (index >= 0 && target in carMusicQueue.indices) {
            requestCarAudioPlayback(CarAudioIds.musicItem(carMusicQueue[target]))
        }
    }

    /** Spotify owns the actual Connect audio engine; the Auto browser mirrors its state, not a
     * competing decoder. Spotify's own foreground service remains responsible for its audio focus. */
    private fun publishCarSpotify(state: SpotifyPlaybackState) {
        if (!carSpotifySelected || destroying) return
        val track = state.current
        val metadata = MediaMetadata.Builder()
            .setTitle(track?.name.orEmpty())
            .setArtist(track?.artists?.joinToString(", ") { it.name }.orEmpty())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                track?.album?.images?.firstOrNull()?.url?.takeIf { it.startsWith("https://") }
                    ?.let { setArtworkUri(android.net.Uri.parse(it)) }
            }.build()
        val id = track?.id?.let(CarAudioIds::spotifyTrack) ?: CarAudioIds.SPOTIFY_ROOT
        sessionPlayer.update(SessionPlaybackState(
            mediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build(),
            uid = id,
            positionMs = state.positionMs,
            durationMs = (track?.durationMs ?: state.durationMs).takeIf { it > 0 } ?: C.TIME_UNSET,
            playWhenReady = state.playing,
            error = carPlaybackError ?: state.error,
            canSeek = (track?.durationMs ?: state.durationMs) > 0,
            hasPrevious = true,
            hasNext = true,
        ))
    }

    // Media-session notifications are exempt from Android 13's notification permission.
    @android.annotation.SuppressLint("NotificationPermission")
    private fun updateSessionState(state: BackgroundPlaybackSession.Snapshot) {
        if (destroying) return
        val now = SystemClock.elapsedRealtime()
        if (state.playing && (!wakeLock.isHeld || now - wakeLockRenewedAt >= 30_000)) {
            wakeLock.acquire(60_000)
            wakeLockRenewedAt = now
        }
        if (!state.playing && wakeLock.isHeld) wakeLock.release()
        // Media3 already handles audio focus. The native backends use the same policy here.
        // Focus belongs to the listening session, not to a decoder generation. Rebuffering and
        // native renderer/source hand-offs retain it; Stop, user Pause and Media3 release it.
        when (audioFocusAction(state.active, state.backend, state.wantsToPlay, resumeOnFocus, focusRequest != null)) {
            AudioFocusAction.REQUEST -> requestFocus()
            AudioFocusAction.KEEP -> Unit
            AudioFocusAction.ABANDON -> abandonFocus()
        }
        if (!state.active) {
            sessionPlayer.update(SessionPlaybackState(error = carPlaybackError))
            clearPlaybackNotification()
            return
        }
        val metadata = state.metadata
        val duration = metadata?.durationMs?.takeIf { it > 0 }
            ?: state.status.durationMs.takeIf { it > 0 }?.plus(metadata?.timelineOffsetMs ?: 0L)
            ?: C.TIME_UNSET
        ensureArtwork(metadata)
        val metadataKey = Triple(metadata?.title, metadata?.subtitle, duration)
        if (notificationMetadata != metadataKey) {
            notificationMetadata = metadataKey
            lastNotification = null
        }
        val hasPreviousRadio = metadata?.source == "radio" && radioQueue.previous() != null
        val hasNextRadio = metadata?.source == "radio" && radioQueue.next() != null
        val carIndex = carMusicQueue.indexOf(metadata?.mediaId)
        val carMusic = metadata?.source != "radio" && carIndex >= 0
        val liveMediaId = carLiveMediaId(metadata)
        val liveIndex = carLiveQueue.indexOf(liveMediaId)
        val carLive = metadata?.source in setOf("iptv", "tvheadend") && liveIndex >= 0
        val telegramIndex = if (metadata?.source == "telegram") carTelegramIndex(metadata.mediaId) else -1
        val carTelegram = telegramIndex >= 0
        val hasPrevious = hasPreviousRadio || (carMusic && carIndex > 0) || (carLive && liveIndex > 0) ||
            (carTelegram && telegramIndex > 0)
        val hasNext = hasNextRadio || (carMusic && carIndex < carMusicQueue.lastIndex) ||
            (carLive && liveIndex < carLiveQueue.lastIndex) ||
            (carTelegram && telegramIndex < carTelegramQueue.lastIndex)
        val liveProgramme = (metadata?.resumeState as? com.ambient.tv.ui.tvheadend.TvheadendPlayerState.Ready)?.nowEvent
        val radioStation = radioQueue.current()?.takeIf { metadata?.source == "radio" && it.id == metadata.mediaId }
        val radioDescription = liveProgramme?.description?.takeIf(String::isNotBlank)
            ?: liveProgramme?.summary?.takeIf(String::isNotBlank)
            ?: liveProgramme?.subtitle?.takeIf(String::isNotBlank)
            ?: radioStation?.let { station ->
                (listOfNotNull(station.country, station.state) + station.localizedLanguageNames())
                    .filter(String::isNotBlank).distinct().joinToString(" · ").takeIf(String::isNotBlank)
            }
        val itemMetadata = MediaMetadata.Builder()
            .setTitle(metadata?.title.orEmpty())
            .setArtist(metadata?.subtitle.orEmpty())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(when {
                metadata?.isLive == true && metadata.hasVideo -> MediaMetadata.MEDIA_TYPE_TV_CHANNEL
                metadata?.isLive == true -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
                metadata?.hasVideo == true -> MediaMetadata.MEDIA_TYPE_MOVIE
                else -> MediaMetadata.MEDIA_TYPE_MUSIC
            })
            .applyCarRadioMetadata(metadata, description = radioDescription,
                genre = radioStation?.tags?.joinToString(", "))
            .apply {
                artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                if (carLive && liveMediaId != null && !metadata?.artworkUrl.isNullOrBlank()) {
                    setArtworkUri(com.ambient.tv.car.CarAudioArtwork.uri(this@PlaybackService, liveMediaId))
                }
            }
            .build()
        // Only opaque catalog/session IDs leave the app; authenticated stream URLs never do.
        val id = when {
            metadata?.source == "radio" -> CarAudioIds.station(metadata.mediaId)
            carLive -> liveMediaId.orEmpty()
            carTelegram -> carTelegramQueue[telegramIndex]
            carMusic -> CarAudioIds.musicItem(metadata?.mediaId.orEmpty())
            else -> "playback:${state.generation}"
        }
        sessionPlayer.update(SessionPlaybackState(
            mediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(itemMetadata).build(),
            uid = state.generation,
            positionMs = state.status.positionMs + (metadata?.timelineOffsetMs ?: 0L),
            durationMs = duration,
            playWhenReady = state.wantsToPlay,
            buffering = state.replacingSource || (state.wantsToPlay && !state.playing && !state.status.ended),
            ended = state.status.ended,
            error = carPlaybackError ?: state.status.error,
            canSeek = BackgroundPlaybackSession.canSeekFromMediaSession(),
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            isLive = metadata?.isLive == true,
        ))
        // Position updates go to MediaSession; avoid rebuilding a notification twice a second.
        val notificationKey = Triple(state.generation, state.status.copy(positionMs = 0, durationMs = 0),
            BackgroundPlaybackSession.sleepWarningDeadline?.let { sleepWarningMinutesLeft(it, now) })
        if (lastNotification != notificationKey) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
            lastNotification = notificationKey
        }
    }

    private fun ensureArtwork(metadata: PlaybackMetadata?) {
        val url = metadata?.artworkUrl?.takeIf { it.isNotBlank() }
            ?: metadata?.artworkFallbackUrls?.firstOrNull { it.isNotBlank() }
        val key = listOf(url.orEmpty(), metadata?.artworkHeaders?.entries?.sortedBy { it.key }.orEmpty().joinToString())
            .joinToString("|")
        if (key == artworkRequestKey) return
        artworkRequestKey = key
        artworkBitmap = null
        artworkData = null
        lastNotification = null
        artworkJob?.cancel()
        if (url.isNullOrBlank()) return
        val headers = metadata?.artworkHeaders.orEmpty()
        artworkJob = scope.launch {
            val artwork = withContext(Dispatchers.IO) {
                loadNotificationArtwork(url, headers)?.let { bitmap ->
                    val bytes = ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                        output.toByteArray()
                    }
                    bitmap to bytes
                }
            }
            if (artworkRequestKey != key || destroying) return@launch
            artworkBitmap = artwork?.first
            artworkData = artwork?.second
            // Encoded artwork is shared through Media3 without leaking authenticated image URLs.
            lastNotification = null
            if (!carSpotifySelected) updateSessionState(BackgroundPlaybackSession.snapshot.value)
        }
    }

    private suspend fun loadNotificationArtwork(url: String, headers: Map<String, String>): Bitmap? {
        return runCatching {
            val request = ImageRequest.Builder(this)
                .data(url)
                .size(512)
                .allowHardware(false)
                .requestHeaders(headers)
                .build()
            val result = imageLoader.execute(request)
            // Coil 3 hands back an Image; toBitmap() returns its own bitmap or draws it into one.
            (result as? SuccessResult)?.image?.toBitmap()
        }.onFailure { Timber.w(it, "Playback notification artwork failed") }.getOrNull()
    }

    private fun openPlaybackIntent(): PendingIntent {
        val target = if (isTelevision(this)) MainActivity::class.java else HandheldActivity::class.java
        return PendingIntent.getActivity(this, 0,
            Intent(this, target).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(state: BackgroundPlaybackSession.Snapshot): Notification {
        fun action(action: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val radioPrevious = state.metadata?.source == "radio" && radioQueue.previous() != null
        val radioNext = state.metadata?.source == "radio" && radioQueue.next() != null
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.metadata?.title ?: getString(R.string.app_name))
            .setContentText(state.status.error ?: state.metadata?.subtitle ?: getString(R.string.background_playback))
            .setContentIntent(openPlaybackIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (radioPrevious) addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this@PlaybackService, android.R.drawable.ic_media_previous),
                    getString(R.string.companion_previous), action(ACTION_PREVIOUS, 4)).build())
                addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this@PlaybackService, if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                    getString(if (state.playing) R.string.player_pause else R.string.player_play), action(ACTION_TOGGLE, 1)).build())
                if (radioNext) addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this@PlaybackService, android.R.drawable.ic_media_next),
                    getString(R.string.companion_next), action(ACTION_NEXT, 5)).build())
                addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this@PlaybackService, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.player_stop), action(ACTION_STOP, 2)).build())
                // Sleep pre-warning: extend from the phone's notification or lock screen.
                BackgroundPlaybackSession.sleepWarningDeadline?.let { deadline ->
                    setContentText(getString(R.string.sleep_warning_message_touch,
                        sleepWarningMinutesLeft(deadline, SystemClock.elapsedRealtime())))
                    addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this@PlaybackService, android.R.drawable.ic_menu_recent_history),
                        getString(R.string.sleep_extend_action), action(ACTION_EXTEND_SLEEP, 3)).build())
                }
            }
        val playIndex = if (radioPrevious) 1 else 0
        val compact = buildList {
            if (radioPrevious) add(0)
            add(playIndex)
            if (radioNext) add(playIndex + 1)
        }.take(3).toIntArray()
        builder.setStyle(Notification.MediaStyle().setMediaSession(mediaSession.platformToken)
            .setShowActionsInCompactView(*compact))
        artworkBitmap?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    /**
     * Audio focus for the native backends (Media3 does its own).
     *
     * A refused request must never end playback for good: that is what made background playback
     * stop for good when another app held focus for a moment during a screen or window change.
     * The request accepts delayed focus, so the system grants it later through the listener, and
     * an outright refusal is retried for a while instead of leaving a silent, paused session.
     */
    private fun requestFocus(): Int {
        val epoch = ++focusEpoch
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                if (epoch != focusEpoch) return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Timber.i("Playback paused: audio focus lost permanently")
                        resumeOnFocus = false
                        BackgroundPlaybackSession.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Timber.i("Playback paused: audio focus lost for a moment, resuming when it returns")
                        resumeOnFocus = resumeOnFocus || BackgroundPlaybackSession.snapshot.value.wantsToPlay
                        BackgroundPlaybackSession.pause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                        Timber.i("Audio focus back: resuming playback")
                        resumeOnFocus = false
                        focusRetries = 0
                        BackgroundPlaybackSession.play()
                    }
                }
            }.build()
        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                focusRetries = 0
                resumeOnFocus = false
            }
            // The system keeps this request and grants it through the listener once focus frees.
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                Timber.i("Playback paused: audio focus delayed, waiting for it")
                resumeOnFocus = true
                BackgroundPlaybackSession.pause()
            }
            else -> {
                // Refused outright: nothing will call back, so ask again shortly.
                Timber.i("Playback paused: audio focus refused (attempt %d)", focusRetries + 1)
                focusRequest?.let(audioManager::abandonAudioFocusRequest)
                focusRequest = null
                resumeOnFocus = true
                BackgroundPlaybackSession.pause()
                scheduleFocusRetry()
            }
        }
        return result
    }

    private fun scheduleFocusRetry() {
        if (focusRetryPending || focusRetries >= MAX_FOCUS_RETRIES) return
        focusRetryPending = true
        focusRetries++
        scope.launch {
            kotlinx.coroutines.delay(FOCUS_RETRY_MS)
            focusRetryPending = false
            val state = BackgroundPlaybackSession.snapshot.value
            if (!state.active || state.backend == "media3" || !resumeOnFocus || focusRequest != null) return@launch
            // requestFocus() clears the flag once focus is granted, so keep the intent here.
            val resume = resumeOnFocus
            if (requestFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && resume) {
                BackgroundPlaybackSession.play()
            }
        }
    }

    private fun abandonFocus() {
        ++focusEpoch
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        resumeOnFocus = false
        focusRetries = 0
    }

    override fun onDestroy() {
        destroying = true
        libraryCallback.cancelPendingSelections()
        cancelCarSelection()
        // A new acquisition can arrive between stopSelfResult and this callback. Its service
        // start is already queued; an old idle service must not release the new engine.
        if (retiredGeneration == null || BackgroundPlaybackSession.snapshot.value.generation == retiredGeneration) {
            BackgroundPlaybackSession.stop()
        }
        sendRemainingReports()
        abandonFocus()
        if (wakeLock.isHeld) wakeLock.release()
        artworkJob?.cancel()
        artworkBitmap = null
        unregisterReceiver(noisy)
        mediaSession.release()
        sessionPlayer.release() // Adapter release never owns or destroys a renderer.
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * The system can end this service while a playback is still open (for example paused audio
     * in the background) or before the last stop was sent. Those final positions must not die
     * with [scope]: they are sent from a scope that outlives the service.
     */
    private fun sendRemainingReports() {
        for (finished in BackgroundPlaybackSession.takeCompleted()) enqueueReport(finished, stopped = true)
        val pending = buildList {
            reportInFlight?.takeIf { it.stopped }?.let(::add)
            while (true) add(reportQueue.take() ?: break)
        }
        if (pending.isEmpty()) return
        finalReports.launch {
            for (queued in pending) {
                kotlinx.coroutines.withTimeoutOrNull(8_000) { report(queued.value, queued.stopped) }
            }
        }
    }

    /**
     * Closed from recents: stop everything, music included. The report loop above still sends
     * the stopped position and ends the service once nothing is left to report.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("App closed from recents: stopping playback")
        sessionPlayer.stop()
        BackgroundPlaybackGuard.stopForAppClosed()
        // Our report loop retires the service after the final positions are saved. The Media3
        // default calls pauseAllPlayersAndStopSelf immediately and would preempt that work.
    }

    // MediaLibraryService owns onBind for both modern and legacy media browsers.

    companion object {
        /** Outlives a destroyed service so the final positions can still be sent. */
        private val finalReports = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 410
        const val ACTION_OPEN = "com.ambient.tv.OPEN_PLAYBACK"
        private const val ACTION_TOGGLE = "com.ambient.tv.TOGGLE_PLAYBACK"
        private const val MAX_FOCUS_RETRIES = 10
        private const val FOCUS_RETRY_MS = 1_500L
        private const val ACTION_STOP = "com.ambient.tv.STOP_PLAYBACK"
        private const val ACTION_PREVIOUS = "com.ambient.tv.RADIO_PREVIOUS"
        private const val ACTION_NEXT = "com.ambient.tv.RADIO_NEXT"
        private const val ACTION_EXTEND_SLEEP = "com.ambient.tv.EXTEND_SLEEP_TIMER"
    }
}
