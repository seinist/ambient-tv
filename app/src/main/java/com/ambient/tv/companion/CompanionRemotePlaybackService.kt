package com.ambient.tv.companion

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.HandheldActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString

/** A remote session: no local player, audio focus, wakelock, or idle receiver. */
@AndroidEntryPoint
class CompanionRemotePlaybackService : Service() {
    @Inject lateinit var manager: CompanionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private var observed: CompanionFrame? = null
    private var volumeProvider: VolumeProvider? = null
    private var volumeMaximum = -1
    private var idleTimer: Job? = null
    private var artworkKey = ""
    private var artwork: android.graphics.Bitmap? = null

    private fun command(type: String, position: Long = 0) { manager.playbackCommand(type, observed, position) }
    override fun onCreate() {
        super.onCreate()
        manager.remoteServiceActive = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.companion_remote_notification), NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "AmbientCompanionRemote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(if (observed?.primaryPlaybackAction == "retry") "retry" else "play")
                override fun onPause() = command("pause")
                override fun onStop() = command("stop")
                override fun onSeekTo(pos: Long) = command("seek", pos)
                override fun onSkipToNext() = command("next")
                override fun onSkipToPrevious() = command("previous")
            })
        }
        val notification = notification(manager.state.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION, notification)
        scope.launch {
            manager.state.collect { state ->
                val frame = state.currentPlayback()
                if (frame == null) {
                    observed = null; session.isActive = false
                    session.setMetadata(null)
                    session.setPlaybackState(PlaybackState.Builder().setActions(0).setState(PlaybackState.STATE_NONE, 0, 0f).build())
                    artworkKey = ""; artwork = null
                    if (volumeProvider != null) {
                        volumeProvider = null; volumeMaximum = -1
                        session.setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_FIXED, 0, 0) {})
                    }
                    if (!state.online || state.target == null) { idleTimer?.cancel(); stopSelf() }
                    else {
                        if (idleTimer == null) idleTimer = scope.launch {
                            delay(20_000)
                            if (manager.state.value.currentPlayback() == null) stopSelf()
                        }
                        // Clear old media actions/artwork even when no further status update arrives.
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
                    }
                    return@collect
                }
                idleTimer?.cancel(); idleTimer = null
                observed = frame
                updateSession(frame)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val expected = intent?.getStringExtra("receiver")
        if (expected != null && expected != manager.state.value.target?.fingerprint) return START_NOT_STICKY
        when (intent?.action) {
            DISCONNECT -> { manager.disconnect(); stopSelf() }
            "play", "pause", "stop", "next", "previous", "retry" -> {
                val commandIntent = intent
                val shown = commandIntent.getStringExtra("frame")?.let { runCatching { companionJson.decodeFromString<CompanionFrame>(it) }.getOrNull() }
                if (shown != null) manager.playbackCommand(requireNotNull(commandIntent.action), shown)
            }
        }
        return START_NOT_STICKY
    }
    private fun updateSession(frame: CompanionFrame) {
        if (artworkKey != frame.artwork) {
            artworkKey = frame.artwork
            artwork = decodeCompanionArtwork(frame.artwork)
        }
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, frame.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, frame.subtitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, frame.duration)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork).build())
        var actions = if (frame.capabilities.isEmpty() || "stop" in frame.capabilities) PlaybackState.ACTION_STOP else 0L
        if (frame.capabilities.isEmpty() || "play" in frame.capabilities) actions = actions or PlaybackState.ACTION_PLAY
        if ("retry" in frame.capabilities) actions = actions or PlaybackState.ACTION_PLAY
        if (frame.capabilities.isEmpty() || "pause" in frame.capabilities) actions = actions or PlaybackState.ACTION_PAUSE
        if ("play" in frame.capabilities && "pause" in frame.capabilities) actions = actions or PlaybackState.ACTION_PLAY_PAUSE
        if (frame.seekable && (frame.capabilities.isEmpty() || "seek" in frame.capabilities)) actions = actions or PlaybackState.ACTION_SEEK_TO
        if ("next" in frame.capabilities) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if ("previous" in frame.capabilities) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(when (frame.phase) {
            "playing" -> PlaybackState.STATE_PLAYING
            "loading", "buffering" -> PlaybackState.STATE_BUFFERING
            "error" -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_PAUSED
        }, frame.position, if (frame.playing) 1f else 0f, SystemClock.elapsedRealtime()).build())
        val maximum = frame.volumeMax.takeIf { "volume" in frame.capabilities } ?: 0
        if (maximum != volumeMaximum) {
            volumeMaximum = maximum
            volumeProvider = object : VolumeProvider(if (maximum > 0) VolumeProvider.VOLUME_CONTROL_ABSOLUTE else VolumeProvider.VOLUME_CONTROL_FIXED, maximum, frame.volume ?: 0) {
                override fun onSetVolumeTo(volume: Int) { if (maximum > 0) command("volume", volume.coerceIn(0, maximum).toLong()) }
                override fun onAdjustVolume(direction: Int) {
                    val current = observed?.volume ?: return
                    if (maximum > 0 && direction != 0) command("volume", (current + direction.coerceIn(-1, 1)).coerceIn(0, maximum).toLong())
                }
            }
            session.setPlaybackToRemote(requireNotNull(volumeProvider))
        }
        volumeProvider?.setCurrentVolume(frame.volume ?: 0)
        session.isActive = true
    }
    private fun notification(state: CompanionState): Notification {
        val open = PendingIntent.getActivity(this, 310, Intent(this, HandheldActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse("ambienttv://now-playing"))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 311, Intent(this, CompanionRemotePlaybackService::class.java).setAction(DISCONNECT)
            .setData(android.net.Uri.parse("ambienttv://companion-disconnect/${state.target?.fingerprint.orEmpty()}"))
            .putExtra("receiver", state.target?.fingerprint),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session.setSessionActivity(open)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.currentPlayback()?.title?.ifBlank { state.target?.name.orEmpty() } ?: state.target?.name.orEmpty())
            .setContentText(listOfNotNull(state.target?.name, state.currentPlayback()?.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "))
            .setContentIntent(open).setDeleteIntent(disconnect).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
        val frame = state.currentPlayback()
        val actions = buildList {
            if (frame != null) {
                if ("previous" in frame.capabilities) add(Triple("previous", R.string.companion_previous, android.R.drawable.ic_media_previous))
                frame.primaryPlaybackAction?.let { action ->
                    add(Triple(action, when (action) { "retry" -> R.string.home_retry; "pause" -> R.string.player_pause; else -> R.string.player_play },
                        if (action == "pause") android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play))
                }
                if ("next" in frame.capabilities) add(Triple("next", R.string.companion_next, android.R.drawable.ic_media_next))
            }
        }
        actions.forEachIndexed { i, (action, label, icon) ->
            val pending = PendingIntent.getService(this, 320 + i, Intent(this, CompanionRemotePlaybackService::class.java)
                .setAction(action).setData(android.net.Uri.parse(companionCommandIdentity(state.target?.fingerprint.orEmpty(), frame)))
                .putExtra("receiver", state.target?.fingerprint)
                .putExtra("frame", companionJson.encodeToString(CompanionFrame(type = "state", media = frame?.media,
                    playbackSessionId = frame?.playbackSessionId ?: 0, receiverId = state.target?.fingerprint.orEmpty(),
                    active = true, capabilities = frame?.capabilities.orEmpty(), duration = frame?.duration ?: 0))),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), getString(label), pending).build())
        }
        builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.companion_disconnect), disconnect).build())
        builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*actions.indices.take(3).toIntArray()))
        return builder.build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel(); observed = null
        session.isActive = false; session.release()
        manager.remoteServiceStopped()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "companion_remote_playback"
        private const val NOTIFICATION = 310
        private const val DISCONNECT = "com.ambient.tv.COMPANION_DISCONNECT"
    }
}
