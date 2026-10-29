package com.ambient.tv.ui.player

import com.ambient.tv.ui.common.requestHeaders
import coil3.request.allowHardware
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.IBinder
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
class PlaybackService : Service() {
    @Inject lateinit var files: com.ambient.tv.data.files.FilesRepository
    @Inject lateinit var repository: MediaRepository
    @Inject lateinit var youtube: SmartTubeEngine
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var launcher: com.ambient.tv.launcher.LauncherPublisher
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
                override fun onPlay() { BackgroundPlaybackSession.userPlay() }
                override fun onPause() { resumeOnFocus = false; BackgroundPlaybackSession.userPause() }
                override fun onStop() = BackgroundPlaybackSession.stop()
                override fun onSeekTo(pos: Long) {
                    BackgroundPlaybackSession.userSeek()
                    val offset = BackgroundPlaybackSession.snapshot.value.metadata?.timelineOffsetMs ?: 0
                    BackgroundPlaybackSession.seekTo((pos - offset).coerceAtLeast(0))
                }
            })
            isActive = true
        }
        audioManager = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ambient TV:Playback").apply { setReferenceCounted(false) }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        // Always meet the foreground deadline, including engine-initialization failures.
        val notification = notification(BackgroundPlaybackSession.snapshot.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, notification)
        // Synchronize focus on play/pause immediately, not at the next polling tick.
        // Otherwise a native renderer could reclaim focus after another app interrupts it.
        scope.launch { BackgroundPlaybackSession.snapshot.collect { updatePlatformState(it) } }
        scope.launch {
            for (signal in reports) {
                while (true) {
                    val queued = reportQueue.take() ?: break
                    reporting = true
                    try {
                        withTimeout(8_000) { report(queued.value, queued.stopped) }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        Timber.w("Playback progress reporting timed out")
                        if (queued.stopped) queued.value.metadata?.let { notifyNotSaved(it) }
                    } finally {
                        reporting = false
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                BackgroundPlaybackSession.tick()
                val playback = BackgroundPlaybackSession.snapshot.value
                val liveMetadata = playback.metadata?.takeIf { it.isLive && it.historyScope != null }
                val recentChannel = liveMetadata?.historyScope?.let { it to liveMetadata.mediaId }
                if (playback.playing && playback.status.error == null && recentChannel != null && recentChannel != lastRecentChannel) {
                    preferences.recordWatchedTvChannel(recentChannel.first, recentChannel.second)
                    lastRecentChannel = recentChannel
                } else if (!playback.active || liveMetadata == null) lastRecentChannel = null
                val youtubeState = playback.metadata?.resumeState as? YouTubePlayback
                if (playback.playing && preferences.youtubeSponsorBlockEnabled && youtubeState?.isLive == false) {
                    youtubeState.sponsorSegments.firstOrNull { playback.status.positionMs in it }?.let {
                        BackgroundPlaybackSession.seekTo(it.last + 1)
                    }
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
        if (state.metadata?.liveTvSession == null && state.metadata?.source !in setOf("plex", "jellyfin", "emby", "dlna", "youtube", "files")) return
        reportQueue.put(state.generation, state, stopped)
        reports.trySend(Unit)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action in setOf(ACTION_TOGGLE, ACTION_STOP)) BackgroundPlaybackSession.registerUserInteraction()
        when (intent?.action) {
            ACTION_TOGGLE -> BackgroundPlaybackSession.userToggle()
            ACTION_STOP -> BackgroundPlaybackSession.stop()
            ACTION_EXTEND_SLEEP -> BackgroundPlaybackSession.extendSleepTimer()
        }
        return START_NOT_STICKY // Never restart an unknown stream after process death.
    }

    private suspend fun report(state: BackgroundPlaybackSession.Snapshot, stopped: Boolean) {
        val metadata = state.metadata ?: return
        if (metadata.isTrailer) return
        val position = state.requestedTimelinePositionMs ?: (state.status.positionMs + metadata.timelineOffsetMs)
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
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
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
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.metadata?.title ?: getString(R.string.app_name))
            .setContentText(state.status.error ?: state.metadata?.subtitle ?: getString(R.string.background_playback))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                getString(if (state.playing) R.string.player_pause else R.string.player_play), action(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.player_stop), action(ACTION_STOP, 2)).build())
            .apply {
                // Sleep pre-warning: extend from the phone's notification or lock screen.
                BackgroundPlaybackSession.sleepWarningDeadline?.let { deadline ->
                    setContentText(getString(R.string.sleep_warning_message_touch,
                        sleepWarningMinutesLeft(deadline, SystemClock.elapsedRealtime())))
                    addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this@PlaybackService, android.R.drawable.ic_menu_recent_history),
                        getString(R.string.sleep_extend_action), action(ACTION_EXTEND_SLEEP, 3)).build())
                }
            }
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1))
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
     * Closed from recents: stop everything, music included. The report loop above still sends
     * the stopped position and ends the service once nothing is left to report.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("App closed from recents: stopping playback")
        BackgroundPlaybackGuard.stopForAppClosed()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 410
        const val ACTION_OPEN = "com.ambient.tv.OPEN_PLAYBACK"
        private const val ACTION_TOGGLE = "com.ambient.tv.TOGGLE_PLAYBACK"
        private const val MAX_FOCUS_RETRIES = 10
        private const val FOCUS_RETRY_MS = 1_500L
        private const val ACTION_STOP = "com.ambient.tv.STOP_PLAYBACK"
        private const val ACTION_EXTEND_SLEEP = "com.ambient.tv.EXTEND_SLEEP_TIMER"
    }
}
