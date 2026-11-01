@file:Suppress("DEPRECATION")

package com.ambient.tv.ui.player

import com.ambient.tv.ui.common.requestHeaders
import coil3.request.allowHardware
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import com.ambient.tv.car.CarAudioCatalog
import com.ambient.tv.car.CarAudioSelection
import com.ambient.tv.car.toBrowserItem
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.spotify.SpotifyPlaybackState
import com.ambient.tv.ui.navigation.Destinations
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

@AndroidEntryPoint
class PlaybackService : MediaBrowserServiceCompat() {
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
    private val carSpotifySelection = CarSpotifySelection()
    private val carSpotifySelected: Boolean get() = carSpotifySelection.active
    private var carSpotifyStartGeneration = -1L
    private val sponsorSkip = BackgroundSponsorSkipPolicy()
    private var carMusicQueue: List<String> = emptyList()
    private var carLiveQueue: List<String> = emptyList()
    private var foregroundStarted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
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
    private var lastMetadata: Triple<String?, String?, Long>? = null
    private var artworkBitmap: Bitmap? = null
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
        mediaSession = MediaSession(this, "AmbientTvPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (carSpotifySelected) spotify.play() else BackgroundPlaybackSession.userPlay()
                }
                override fun onPause() {
                    resumeOnFocus = false
                    if (carSpotifySelected) spotify.pause() else BackgroundPlaybackSession.userPause()
                }
                override fun onStop() {
                    if (carSpotifySelected) {
                        carSpotifySelection.clear()
                        spotify.stop()
                    } else BackgroundPlaybackSession.stop()
                }
                override fun onSkipToPrevious() { skipCarAudio(next = false) }
                override fun onSkipToNext() { skipCarAudio(next = true) }
                override fun onSeekTo(pos: Long) {
                    if (carSpotifySelected) { spotify.seek(pos); return }
                    if (BackgroundPlaybackSession.snapshot.value.metadata?.source == "radio") return
                    BackgroundPlaybackSession.userSeek()
                    val offset = BackgroundPlaybackSession.snapshot.value.metadata?.timelineOffsetMs ?: 0
                    BackgroundPlaybackSession.seekTo((pos - offset).coerceAtLeast(0))
                }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    mediaId?.let(::requestCarAudioPlayback)
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val search = query?.trim()?.takeIf { it.isNotEmpty() } ?: return
                    carPlaybackJob?.cancel()
                    carPlaybackJob = scope.launch {
                        try {
                            carCatalog.firstForVoiceSearch(search)?.let { startCarAudio(it) }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.w(error, "Android Auto voice search failed")
                        }
                    }
                }
            })
            isActive = true
        }
        // The very same platform session used by notifications and Bluetooth is now also
        // discoverable by Android Auto. Never create a competing second player/session.
        setSessionToken(requireNotNull(MediaSessionCompat.fromMediaSession(this, mediaSession)).sessionToken)
        audioManager = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ambient TV:Playback").apply { setReferenceCounted(false) }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        // A browser-only bind must not start a foreground service. The explicit playback
        // start goes through onStartCommand and meets the Android 12+ foreground deadline there.
        // Synchronize focus on play/pause immediately, not at the next polling tick.
        scope.launch {
            BackgroundPlaybackSession.snapshot.collect { state ->
                if (carSpotifySelected && state.active && state.generation != carSpotifyStartGeneration) {
                    carSpotifySelection.clear()
                }
                if (!carSpotifySelected) updatePlatformState(state)
                else if (!state.active) {
                    // Spotify has its own Connect foreground service. A browser connection
                    // must not keep an obsolete radio/media foreground notification alive.
                    if (wakeLock.isHeld) wakeLock.release()
                    abandonFocus()
                    if (foregroundStarted) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        foregroundStarted = false
                    }
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                    lastNotification = null
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
                        duration, playback.playing,
                    )?.let(BackgroundPlaybackSession::seekTo)
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
                    if (!idle.active && reportQueue.isEmpty && !reporting && stopSelfResult(lastStartId)) {
                        retiredGeneration = idle.generation
                        break
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
        // A started playback service must enter foreground promptly. Merely browsing from
        // the head unit binds this service without starting a notification or FGS.
        if (!foregroundStarted) {
            val initial = notification(BackgroundPlaybackSession.snapshot.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(
                NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            ) else startForeground(NOTIFICATION, initial)
            foregroundStarted = true
        }
        if (intent?.action in setOf(ACTION_TOGGLE, ACTION_STOP, ACTION_PREVIOUS, ACTION_NEXT)) BackgroundPlaybackSession.registerUserInteraction()
        when (intent?.action) {
            ACTION_TOGGLE -> BackgroundPlaybackSession.userToggle()
            ACTION_STOP -> BackgroundPlaybackSession.stop()
            ACTION_PREVIOUS -> playRadioAdjacent(next = false)
            ACTION_NEXT -> playRadioAdjacent(next = true)
            ACTION_EXTEND_SLEEP -> BackgroundPlaybackSession.extendSleepTimer()
        }
        return START_NOT_STICKY // Never restart an unknown stream after process death.
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
                "youtube" -> youtube.updateHistory(metadata.mediaId, position)
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
        val station = if (next) radioQueue.next() else radioQueue.previous()
        if (station != null) scope.launch {
            radioPlaybackLauncher.play(station)
                .onFailure { Timber.w(it, "Radio station change failed") }
        }
    }


    /** Platform MediaBrowserService: Android Auto binds here; the normal media notification
     * keeps using exactly the same platform MediaSession token. */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // Do not expose a user's private radio/music library to arbitrary installed apps.
        // Android Auto and other trusted media controllers qualify; our own UID is allowed.
        val owned = packageManager.getPackagesForUid(clientUid)?.contains(clientPackageName) == true
        val trusted = if (owned) runCatching {
            getSystemService(android.media.session.MediaSessionManager::class.java)
                .isTrustedForMediaControl(
                    android.media.session.MediaSessionManager.RemoteUserInfo(clientPackageName, -1, clientUid),
                )
        }.getOrDefault(false) else false
        return if (owned && (clientUid == applicationInfo.uid || trusted)) BrowserRoot(
            CarAudioIds.ROOT,
            Bundle().apply { putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true) },
        ) else null
    }

    override fun onLoadChildren(
        parentId: String,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        if (!CarAudioIds.isFolder(parentId)) { result.sendResult(null); return }
        result.detach()
        scope.launch {
            val entries = try {
                carCatalog.children(parentId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Android Auto browse failed for %s", parentId)
                emptyList()
            }
            result.sendResult(entries.map { it.toBrowserItem() }.toMutableList())
        }
    }

    override fun onLoadItem(
        itemId: String,
        result: MediaBrowserServiceCompat.Result<MediaBrowserCompat.MediaItem>,
    ) {
        result.detach()
        scope.launch {
            val entry = runCatching { carCatalog.entry(itemId) }.getOrNull()
            result.sendResult(entry?.toBrowserItem())
        }
    }

    /** Android Auto's actual browsable search results, not only one voice-selected station. */
    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val found = try {
                carCatalog.search(query)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Android Auto media search failed")
                emptyList()
            }
            result.sendResult(found.map { it.toBrowserItem() }.toMutableList())
        }
    }

    private fun requestCarAudioPlayback(itemId: String) {
        carPlaybackJob?.cancel()
        carPlaybackJob = scope.launch {
            try {
                carCatalog.resolve(itemId)?.let { startCarAudio(it) }
                    ?: Timber.w("Android Auto rejected unavailable/non-audio item")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Android Auto playback selection failed")
                mediaSession.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
                    .setState(PlaybackState.STATE_ERROR, 0L, 0f).build())
            }
        }
    }

    private suspend fun startCarAudio(selection: CarAudioSelection) {
        when (selection) {
            is CarAudioSelection.Radio -> {
                if (carSpotifySelected) spotify.pause()
                carSpotifySelection.clear()
                carMusicQueue = emptyList()
                carLiveQueue = emptyList()
                radioPlaybackLauncher.play(selection.station, selection.queue)
                    .onFailure { Timber.w(it, "Android Auto radio playback failed") }
                carCatalog.invalidate(CarAudioIds.RADIO_RECENT)
                notifyChildrenChanged(CarAudioIds.RADIO_RECENT)
            }
            is CarAudioSelection.Spotify -> {
                carSpotifyStartGeneration = BackgroundPlaybackSession.snapshot.value.generation
                val start = carSpotifySelection.begin()
                carMusicQueue = emptyList()
                carLiveQueue = emptyList()
                mediaSession.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
                    .setState(PlaybackState.STATE_BUFFERING, 0L, 0f).build())
                spotify.playUri(selection.uri) { success ->
                    // Run on this service's lifetime, never against a released MediaSession.
                    scope.launch {
                        // The repository already rolls back the previous background engine on
                        // failure. A late result must not replace a newer source or stop selection.
                        if (success) carSpotifySelection.confirm(start)
                        else if (carSpotifySelection.rollbackFailed(start)) {
                            if (carSpotifySelected) publishCarSpotify(spotify.playback.value)
                            else updatePlatformState(BackgroundPlaybackSession.snapshot.value)
                        }
                    }
                }
            }
            is CarAudioSelection.Library -> {
                if (carSpotifySelected) spotify.pause()
                carSpotifySelection.clear()
                val item = selection.item
                val url = item.streamUrl ?: return
                carMusicQueue = selection.queue
                carLiveQueue = emptyList()
                repository.preparePlayback(item.ratingKey, 0)
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
            is CarAudioSelection.Live -> {
                if (carSpotifySelected) spotify.pause()
                carSpotifySelection.clear()
                carMusicQueue = emptyList()
                carLiveQueue = selection.queue
                val stream = selection.stream
                val channel = selection.channel
                val playbackId = carLiveQueue.firstOrNull {
                    CarAudioIds.liveAudioSpec(it)?.second == channel.uuid
                } ?: return
                PlaybackEngineFactory.create(
                    context = this,
                    streamUrl = stream.url,
                    startPositionMs = 0L,
                    streamHeaders = stream.headers,
                    mimeType = if (android.net.Uri.parse(stream.url).path.orEmpty().endsWith(".m3u8", true))
                        androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
                    metadata = PlaybackMetadata(
                        route = Destinations.LIVE_TV,
                        title = channel.name,
                        subtitle = channel.numberLabel.orEmpty(),
                        source = selection.system,
                        mediaId = playbackId,
                        hasVideo = false,
                        isLive = true,
                        artworkUrl = channel.iconUrl,
                        durationMs = 0L,
                    ),
                    audioOnly = true,
                    liveStream = true,
                )
            }
        }
    }

    private fun skipCarAudio(next: Boolean) {
        if (carSpotifySelected) {
            if (next) spotify.next() else spotify.previous()
            return
        }
        val current = BackgroundPlaybackSession.snapshot.value.metadata
        if (current?.source == "radio") {
            playRadioAdjacent(next)
            return
        }
        val liveIndex = carLiveQueue.indexOf(current?.mediaId)
        val liveTarget = if (next) liveIndex + 1 else liveIndex - 1
        if (liveIndex >= 0 && liveTarget in carLiveQueue.indices) {
            requestCarAudioPlayback(carLiveQueue[liveTarget])
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
        if (!carSpotifySelected) return
        val track = state.current
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track?.name.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artists?.joinToString(", ") { it.name }.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track?.durationMs ?: state.durationMs)
            .build())
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(when {
                state.playing -> PlaybackState.STATE_PLAYING
                state.error != null -> PlaybackState.STATE_ERROR
                else -> PlaybackState.STATE_PAUSED
            }, state.positionMs, if (state.playing) 1f else 0f)
            .build())
    }

    // Media-session notifications are exempt from Android 13's notification permission.
    @android.annotation.SuppressLint("NotificationPermission")
    private fun updatePlatformState(state: BackgroundPlaybackSession.Snapshot) {
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
            mediaSession.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
                .setState(PlaybackState.STATE_STOPPED, 0L, 0f).build())
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            lastNotification = null
            lastMetadata = null
            return
        }
        val metadata = state.metadata
        val duration = metadata?.durationMs?.takeIf { it > 0 } ?: state.status.durationMs
        ensureArtwork(metadata)
        val metadataKey = Triple(metadata?.title, metadata?.subtitle, duration)
        if (lastMetadata != metadataKey) {
            publishMetadata(metadata, duration)
            lastMetadata = metadataKey
            // A station's ICY title can change while generation/status stay unchanged.
            // Force MediaStyle to rebuild so lock screen and notification follow MediaSession.
            lastNotification = null
        }
        val hasPreviousRadio = metadata?.source == "radio" && radioQueue.previous() != null
        val hasNextRadio = metadata?.source == "radio" && radioQueue.next() != null
        val carIndex = carMusicQueue.indexOf(metadata?.mediaId)
        val carMusic = metadata?.source != "radio" && carIndex >= 0
        val liveIndex = carLiveQueue.indexOf(metadata?.mediaId)
        val carLive = metadata?.source in setOf("iptv", "tvheadend") && liveIndex >= 0
        val hasPrevious = hasPreviousRadio || (carMusic && carIndex > 0) || (carLive && liveIndex > 0)
        val hasNext = hasNextRadio || (carMusic && carIndex < carMusicQueue.lastIndex) ||
            (carLive && liveIndex < carLiveQueue.lastIndex)
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(platformPlaybackActions(metadata?.source, hasPrevious, hasNext, carMusic || carLive))
            .setState(when {
                !state.active || state.status.ended -> PlaybackState.STATE_STOPPED
                state.status.error != null -> PlaybackState.STATE_ERROR
                state.playing -> PlaybackState.STATE_PLAYING
                state.wantsToPlay -> PlaybackState.STATE_BUFFERING
                else -> PlaybackState.STATE_PAUSED
            }, state.status.positionMs + (metadata?.timelineOffsetMs ?: 0), if (state.playing) 1f else 0f)
            .build())
        // Position updates go to MediaSession; avoid rebuilding a notification twice a second.
        val notificationKey = Triple(state.generation, state.status.copy(positionMs = 0, durationMs = 0),
            BackgroundPlaybackSession.sleepWarningDeadline?.let { sleepWarningMinutesLeft(it, now) })
        if (lastNotification != notificationKey) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
            lastNotification = notificationKey
        }
    }

    private fun publishMetadata(metadata: PlaybackMetadata?, duration: Long) {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, metadata?.title.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, metadata?.subtitle.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        artworkBitmap?.let { art ->
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun ensureArtwork(metadata: PlaybackMetadata?) {
        val url = metadata?.artworkUrl?.takeIf { it.isNotBlank() }
            ?: metadata?.artworkFallbackUrls?.firstOrNull { it.isNotBlank() }
        val key = listOf(url.orEmpty(), metadata?.artworkHeaders?.entries?.sortedBy { it.key }.orEmpty().joinToString())
            .joinToString("|")
        if (key == artworkRequestKey) return
        artworkRequestKey = key
        artworkBitmap = null
        artworkJob?.cancel()
        if (url.isNullOrBlank()) return
        val headers = metadata?.artworkHeaders.orEmpty()
        artworkJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadNotificationArtwork(url, headers) }
            if (artworkRequestKey != key) return@launch
            artworkBitmap = bitmap
            // Refresh metadata + notification so MediaStyle can show the cover on phones.
            lastMetadata = null
            lastNotification = null
            updatePlatformState(BackgroundPlaybackSession.snapshot.value)
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

    private fun notification(state: BackgroundPlaybackSession.Snapshot): Notification {
        val target = if (isTelevision(this)) MainActivity::class.java else HandheldActivity::class.java
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, target).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val radioPrevious = state.metadata?.source == "radio" && radioQueue.previous() != null
        val radioNext = state.metadata?.source == "radio" && radioQueue.next() != null
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.metadata?.title ?: getString(R.string.app_name))
            .setContentText(state.status.error ?: state.metadata?.subtitle ?: getString(R.string.background_playback))
            .setContentIntent(open)
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
        builder.setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken)
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
        BackgroundPlaybackGuard.stopForAppClosed()
        super.onTaskRemoved(rootIntent)
    }

    // MediaBrowserServiceCompat owns onBind: avoid a second ad-hoc browser binder.

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

internal fun platformPlaybackActions(
    source: String?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    carAudioQueue: Boolean = false,
): Long {
    var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
    if (source != "radio") actions = actions or PlaybackState.ACTION_SEEK_TO
    // Ordinary video stays seek-only; selected car music and radio provide a queue.
    if (source == "radio" || carAudioQueue) {
        if (hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
    }
    return actions
}
