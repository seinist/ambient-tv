package com.ambient.tv.youtube

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.launcher.LauncherIntents
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * "Watch on TV" for the phone's YouTube app: after linking with a code (Settings › YouTube) the
 * phone can send videos to this app and steer them (play, pause, seek, stop, volume), and it sees
 * what plays. Runs through SmartTube's lounge client. Off by default; listens while the app is in
 * the foreground or plays YouTube. Only YouTube playback is steered, nothing else.
 */
@Singleton
class YouTubeTvLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val engine: SmartTubeEngine,
) : DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor { Thread(it, "youtube-link-control").apply { isDaemon = true } }
    @Volatile private var foreground = false
    @Volatile private var running = false
    private var listener: Thread? = null
    private var reporter: ScheduledExecutorService? = null
    @Volatile private var listenerEndedAt = 0L
    /** Bumped on every stop: a listener of an older generation is superseded and must stay silent. */
    @Volatile private var generation = 0
    @Volatile private var listenerSuperseded = false
    private var unwantedSince = 0L

    // Reporting state (reporter thread only).
    private var reportedVideo: String? = null
    private var reportedState = -1
    private var reportedAt = 0L
    @Volatile private var forceReport = false
    /** Position the phone asked for, applied once that video plays: videoId to milliseconds. */
    @Volatile private var pendingSeek: Pair<String, Long>? = null

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { foreground = true; refresh() }
    override fun onStop(owner: LifecycleOwner) { foreground = false; refresh() }

    /** Settings or foreground changed: start or stop listening to match. */
    fun refresh() {
        control.execute {
            val allowed = preferences.youtubeEnabled && preferences.youtubeTvLinkEnabled && engine.available
            val wanted = allowed && (foreground || youtubeSession() != null)
            val now = SystemClock.elapsedRealtime()
            if (wanted) { unwantedSince = 0L; start(); return@execute }
            // Briefly leaving the app (a dialog, a quick app switch) must not tear the lounge connection
            // down: its blocking read cannot be interrupted, so a restart would leave two listeners.
            if (allowed && running) {
                if (unwantedSince == 0L) unwantedSince = now
                if (now - unwantedSince < BACKGROUND_GRACE_MS) return@execute
            }
            unwantedSince = 0L
            stop()
        }
    }

    private fun start() {
        if (running) return
        running = true
        startListener()
        reporter = Executors.newSingleThreadScheduledExecutor { Thread(it, "youtube-link-report").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { report() }.onFailure { e -> Timber.d(e, "YouTube link report") } }, 2, 2, TimeUnit.SECONDS) }
    }

    private fun startListener() {
        // The lounge client's read ignores interrupts until its 60 s timeout: never run two at once.
        if (listener?.isAlive == true) return
        val mine = generation
        listenerSuperseded = false
        listener = Thread({
            runCatching { engine.listenLounge { command -> if (mine == generation) main.post { handle(command) } } }
                .onFailure { Timber.w(it, "YouTube link listener ended") }
            listenerSuperseded = mine != generation
            listenerEndedAt = SystemClock.elapsedRealtime()
        }, "youtube-link-listen").apply { isDaemon = true; start() }
    }

    private fun stop() {
        if (!running) return
        running = false
        generation++
        listener?.interrupt()
        reporter?.shutdownNow(); reporter = null
        // Linked phones see the TV go idle instead of a frozen last state.
        if (reportedVideo != null) runCatching { engine.postLoungeState(0, 0, STATE_IDLE) }
        reportedVideo = null
    }

    /** The running YouTube playback (not trailers), if any. */
    private fun youtubeSession(): BackgroundPlaybackSession.Snapshot? =
        BackgroundPlaybackSession.snapshot.value.takeIf { it.metadata?.source == "youtube" && it.metadata.isTrailer != true }

    private fun handle(command: YouTubeLinkCommand) {
        val session = youtubeSession()
        when (command.type) {
            YouTubeLinkCommand.OPEN_VIDEO -> {
                val videoId = command.videoId?.takeIf(::isYouTubeVideoId) ?: return
                // Opening a player needs the app on screen.
                if (!foreground) return
                if (session?.metadata?.mediaId == videoId) {
                    if (command.positionMs > 0) BackgroundPlaybackSession.seekTo(command.positionMs)
                    BackgroundPlaybackSession.play()
                } else {
                    pendingSeek = if (command.positionMs > 1_000) videoId to command.positionMs else null
                    LauncherIntents.post(Destinations.youtubePlayer(videoId))
                }
                forceReport = true
            }
            YouTubeLinkCommand.SEEK -> if (session != null) BackgroundPlaybackSession.seekTo(command.positionMs.coerceAtLeast(0))
            YouTubeLinkCommand.PLAY -> if (session != null) BackgroundPlaybackSession.play()
            YouTubeLinkCommand.PAUSE -> if (session != null) BackgroundPlaybackSession.pause()
            YouTubeLinkCommand.STOP -> if (session != null) BackgroundPlaybackSession.stop()
            YouTubeLinkCommand.GET_STATE -> forceReport = true
            YouTubeLinkCommand.VOLUME -> setVolume(command.volume)
            YouTubeLinkCommand.CONNECTED, YouTubeLinkCommand.DISCONNECTED -> {
                val name = command.deviceName?.filterNot { it.isISOControl() }?.take(60)?.takeIf { it.isNotBlank() }
                AppNotifications.post(AppNotice(
                    "youtube:link", if (command.type == YouTubeLinkCommand.CONNECTED) R.string.youtube_link_connected else R.string.youtube_link_disconnected,
                    systems = listOf("youtube"), kind = NoticeKind.PLAYBACK, args = listOfNotNull(name ?: context.getString(R.string.youtube_link_phone)),
                    interruptPlayback = false,
                ))
                forceReport = true
            }
            else -> Unit
        }
    }

    private fun setVolume(percent: Int) {
        if (percent !in 0..100) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max + 50) / 100, AudioManager.FLAG_SHOW_UI) }
    }

    /** Every 2 s: report what plays to linked phones (on change, else every 10 s). */
    private fun report() {
        if (!running) return
        // The lounge client gives up on some errors; listen again after a pause.
        if (listener?.isAlive != true) {
            val pause = if (listenerSuperseded) 0L else RELISTEN_MS
            if (SystemClock.elapsedRealtime() - listenerEndedAt > pause) startListener()
        }
        val session = youtubeSession()
        if (!foreground && session == null) { refresh(); return }
        val metadata = session?.metadata
        if (metadata == null) {
            if (reportedVideo != null) { engine.postLoungeState(0, 0, STATE_IDLE); reportedVideo = null }
            return
        }
        val status = session.status
        pendingSeek?.let { (videoId, position) ->
            if (videoId == metadata.mediaId && status.durationMs > 0) {
                pendingSeek = null
                main.post { BackgroundPlaybackSession.seekTo(position) }
            }
        }
        val state = if (status.playing) STATE_PLAYING else STATE_PAUSED
        val now = SystemClock.elapsedRealtime()
        when {
            metadata.mediaId != reportedVideo -> engine.postLoungeNowPlaying(metadata.mediaId, status.positionMs, status.durationMs, state)
            forceReport || state != reportedState || now - reportedAt > PERIODIC_REPORT_MS ->
                engine.postLoungeState(status.positionMs, status.durationMs, state)
            else -> return
        }
        forceReport = false
        reportedVideo = metadata.mediaId
        reportedState = state
        reportedAt = now
    }

    private companion object {
        // SmartTube RemoteControlService states.
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_IDLE = 3
        const val PERIODIC_REPORT_MS = 10_000L
        const val RELISTEN_MS = 30_000L
        const val BACKGROUND_GRACE_MS = 20_000L
    }
}
