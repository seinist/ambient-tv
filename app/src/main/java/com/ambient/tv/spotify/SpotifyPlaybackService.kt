package com.ambient.tv.spotify

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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.ambient.tv.R
import com.ambient.tv.spotify.playback.SpotifyPlaybackEngine
import com.ambient.tv.ui.MainActivity
import com.ambient.tv.ui.mobile.HandheldActivity
import com.ambient.tv.ui.mobile.isTelevision
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/** Keeps user-started native Connect playback eligible for Android 17 background audio. */
@AndroidEntryPoint
class SpotifyPlaybackService : Service() {
    @Inject lateinit var engine: SpotifyPlaybackEngine
    @Inject lateinit var repository: SpotifyRepository
    @Inject lateinit var preferences: com.ambient.tv.data.local.AppPreferences
    private val trackWatch = SpotifyTrackWatch()
    private var notPlayingSinceMs: Long? = null
    private var lastWarningMinutes: Int? = null
    /** Spotify as a target of the shared sleep timer (auto-start, daily stop, warning, prompt). */
    private val sleepTarget = object : com.ambient.tv.ui.player.SleepTarget {
        override fun sleepStatus() = trackWatch.status(engine.state.value)
        override fun sleepWantsToPlay() = engine.state.value.playing
        override fun sleepPause() = pause()
        override fun sleepPlay() { repository.play() }
        override fun sleepStop() {
            com.ambient.tv.ui.player.SleepSession.end(this)
            stopPlaybackCompletely()
        }
        override fun sleepEnterStandby() {
            com.ambient.tv.ui.player.TvSleepStandby.request(this@SpotifyPlaybackService)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var idleStop: Job? = null
    private var connectionGrace: Job? = null
    private var everConnected = false
    private var lastNotification: Triple<Boolean, String?, String?>? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = pause()
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, displayName(), NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "SpotifyConnect").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { repository.play() }
                override fun onPause() = pause()
                override fun onStop() { stopPlaybackCompletely() }
                override fun onSeekTo(pos: Long) { repository.seek(pos.coerceAtLeast(0L)) }
                override fun onSkipToNext() { engine.next() }
                override fun onSkipToPrevious() { engine.previous() }
            })
            isActive = true
        }
        // Foreground first: focus and AudioTrack writes need a valid lifecycle on API37.
        startForeground(NOTIFICATION, notification(engine.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        running = true
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { engine.state.collect(::update) }
        scope.launch {
            while (true) {
                delay(1_000)
                sleepTick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> if (engine.state.value.playing) pause() else repository.play()
            STOP -> stopPlaybackCompletely()
            EXTEND_SLEEP -> com.ambient.tv.ui.player.SleepSession.extend()
        }
        return START_NOT_STICKY
    }

    private fun stopPlaybackCompletely() {
        resumeOnFocus = false
        repository.stop()
        abandonFocus()
        stopSelf()
    }

    private fun pause() {
        resumeOnFocus = false
        engine.pause()
        abandonFocus()
    }

    @android.annotation.SuppressLint("NotificationPermission") // Active media-session notification.
    private fun update(state: SpotifyPlaybackState) {
        if (!state.connected) {
            if (everConnected) {
                stopSelf()
            } else if (connectionGrace == null) {
                // startForegroundService() races the asynchronous librespot startup. Do not tear
                // the service down just because the first StateFlow emission is still disconnected.
                connectionGrace = scope.launch {
                    delay(CONNECTION_GRACE_MS)
                    connectionGrace = null
                    if (!engine.state.value.connected) stopSelf()
                }
            }
            return
        }
        everConnected = true
        connectionGrace?.cancel()
        connectionGrace = null
        val sleep = com.ambient.tv.ui.player.SleepSession
        if (trackWatch.update(state)) sleep.itemChanged(sleepTarget)
        if (state.playing && sleep.target !== sleepTarget) {
            sleep.begin(sleepTarget, { preferences }, isLive = false, hadPlayer = false, replacingSource = false)
        }
        if (state.playing) {
            idleStop?.cancel(); idleStop = null
            if (focus == null) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS -> pause()
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                resumeOnFocus = engine.state.value.playing
                                engine.pause()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                                resumeOnFocus = false
                                engine.play()
                            }
                        }
                    }.build()
                focus = request
                if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) pause()
            }
        } else {
            if (!resumeOnFocus) abandonFocus()
            if (isTelevision(this)) {
                // TV is intentionally an always-available Connect receiver. Keeping the already
                // user-started media service alive avoids a later remote PLAY being blocked by
                // background foreground-service start restrictions.
                idleStop?.cancel()
                idleStop = null
            } else if (idleStop == null) {
                // Handheld keeps short interruptions, then releases receiver + media FGS.
                idleStop = scope.launch {
                    delay(IDLE_TIMEOUT_MS)
                    engine.stop()
                    stopSelf()
                }
            }
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO)
            .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionMs, if (state.playing) 1f else 0f).build())
        val title = state.current?.name
        val artist = state.current?.artists?.joinToString { it.name }
        val key = Triple(state.playing, title, artist)
        if (lastNotification != key) {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs).build())
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
            lastNotification = key
        }
    }

    /** Drives the sleep timer while Spotify is its target; a long pause ends the sleep session. */
    @android.annotation.SuppressLint("NotificationPermission")
    private fun sleepTick() {
        val sleep = com.ambient.tv.ui.player.SleepSession
        if (sleep.target !== sleepTarget) return
        val now = android.os.SystemClock.elapsedRealtime()
        val state = engine.state.value
        notPlayingSinceMs = if (state.playing) null else notPlayingSinceMs ?: now
        if (sleep.promptDeadline == null && notPlayingSinceMs?.let { now - it >= SPOTIFY_SLEEP_IDLE_END_MS } == true) {
            sleep.end(sleepTarget)
        } else sleep.tick(sleepTarget)
        val minutes = sleep.warningDeadline?.takeIf { sleep.target === sleepTarget }
            ?.let { com.ambient.tv.ui.player.sleepWarningMinutesLeft(it, now) }
        if (minutes != lastWarningMinutes && state.connected) {
            lastWarningMinutes = minutes
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
        }
    }

    /** The name the user gave Spotify in Settings, so the notification matches the menu. */
    private fun displayName() = com.ambient.tv.data.local.SystemNameOverrides.names["spotify"] ?: "Spotify"

    private fun notification(state: SpotifyPlaybackState): Notification {
        val target = if (isTelevision(this)) MainActivity::class.java else HandheldActivity::class.java
        val open = PendingIntent.getActivity(this, 0, Intent(this, target)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(value: String, id: Int) = PendingIntent.getService(this, id,
            Intent(this, SpotifyPlaybackService::class.java).setAction(value),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.current?.name ?: displayName())
            .setContentText(state.current?.artists?.joinToString { it.name } ?: state.deviceName)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(state.playing)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                getString(if (state.playing) R.string.player_pause else R.string.player_play), action(TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.player_stop), action(STOP, 2)).build())
            .apply {
                // Sleep pre-warning: extend from the notification or lock screen.
                val sleep = com.ambient.tv.ui.player.SleepSession
                sleep.warningDeadline?.takeIf { sleep.target === sleepTarget }?.let { deadline ->
                    setContentText(getString(R.string.sleep_warning_message_touch,
                        com.ambient.tv.ui.player.sleepWarningMinutesLeft(deadline, android.os.SystemClock.elapsedRealtime())))
                    addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this@SpotifyPlaybackService, android.R.drawable.ic_menu_recent_history),
                        getString(R.string.sleep_extend_action), action(EXTEND_SLEEP, 3)).build())
                }
            }
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun abandonFocus() {
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
    }

    override fun onDestroy() {
        running = false
        com.ambient.tv.ui.player.SleepSession.end(sleepTarget)
        connectionGrace?.cancel()
        idleStop?.cancel()
        if (engine.state.value.connected && engine.state.value.playing) engine.pause()
        abandonFocus()
        scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "spotify_playback"
        private const val NOTIFICATION = 411
        private const val TOGGLE = "com.ambient.tv.SPOTIFY_TOGGLE"
        private const val STOP = "com.ambient.tv.SPOTIFY_STOP"
        private const val EXTEND_SLEEP = "com.ambient.tv.SPOTIFY_EXTEND_SLEEP"
        private const val IDLE_TIMEOUT_MS = 10 * 60_000L
        private const val CONNECTION_GRACE_MS = 12_000L
        @Volatile private var running = false

        fun ensureRunning(context: Context): Boolean = running || runCatching {
            ContextCompat.startForegroundService(context, Intent(context, SpotifyPlaybackService::class.java))
            true
        }.getOrElse {
            // No unsolicited cold background start from a remote Connect device.
            Timber.w(it, "Spotify foreground playback service could not start")
            false
        }
    }
}
