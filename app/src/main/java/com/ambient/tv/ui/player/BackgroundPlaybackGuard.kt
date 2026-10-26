package com.ambient.tv.ui.player

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import timber.log.Timber

/**
 * Applies [backgroundPlaybackAction] when the whole app leaves the screen.
 *
 * Follows the app's process lifecycle, not a single Activity: a configuration change, a language
 * change (recreate) or the app's own sign-in screen never count as leaving. Screens of other apps
 * that the app opens itself (file picker, installer, browser sign-in) are announced through
 * [markExternalFlow] and do not end playback either.
 */
object BackgroundPlaybackGuard : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var installed = false
    private var foreground = true
    @Volatile private var externalFlowMarkedAt: Long? = null
    private var stopPending = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val delayedStop = Runnable {
        stopPending = false
        releaseWakeLock()
        if (!foreground) stopNow("video paused in the background for ${BACKGROUND_VIDEO_STOP_MS / 1000} s")
    }

    private val deferredDecision = Runnable {
        if (!foreground) apply(screenOn = screenOn(), externalFlow = false)
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            // In the foreground the app's own stop follows and decides with the screen already off.
            if (!foreground) apply(screenOn = false, externalFlow = false)
        }
    }

    fun install(application: Application) {
        if (installed) return
        installed = true
        appContext = application
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(this)
        ContextCompat.registerReceiver(application, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Call right before the app opens another app's screen and expects to come back to it. */
    fun markExternalFlow() {
        externalFlowMarkedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        externalFlowMarkedAt = null
        cancelPending()
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        val external = externalFlowActive(externalFlowMarkedAt, SystemClock.elapsedRealtime())
        externalFlowMarkedAt = null
        apply(screenOn = screenOn(), externalFlow = external)
    }

    /** Closing the app (recents): stop everything, music included. */
    fun stopForAppClosed() {
        cancelPending()
        stopNow("app closed")
    }

    private fun apply(screenOn: Boolean, externalFlow: Boolean) {
        handler.removeCallbacks(deferredDecision)
        val context = appContext ?: return
        val snapshot = BackgroundPlaybackSession.snapshot.value
        val metadata = snapshot.metadata
        val action = backgroundPlaybackAction(
            active = snapshot.active,
            isLive = metadata?.isLive == true,
            // Without metadata (a source hand-off) assume a picture: pausing is the safe side.
            hasVideo = metadata?.hasVideo != false,
            television = com.ambient.tv.ui.mobile.isTelevision(context),
            screenOn = screenOn,
            externalFlow = externalFlow,
        )
        if (action != BackgroundPlaybackAction.NONE) {
            Timber.i("App in background: %s (live=%s video=%s screenOn=%s)", action, metadata?.isLive, metadata?.hasVideo, screenOn)
        }
        when (action) {
            BackgroundPlaybackAction.NONE, BackgroundPlaybackAction.KEEP -> Unit
            BackgroundPlaybackAction.DEFER -> handler.postDelayed(deferredDecision, EXTERNAL_FLOW_LIMIT_MS)
            BackgroundPlaybackAction.STOP -> {
                cancelPending()
                stopNow("app in background")
            }
            BackgroundPlaybackAction.PAUSE_THEN_STOP -> {
                BackgroundPlaybackSession.pause()
                if (!stopPending) {
                    stopPending = true
                    // A paused player holds no wake lock; keep the CPU up so the stop is not late.
                    acquireWakeLock(context)
                    handler.postDelayed(delayedStop, BACKGROUND_VIDEO_STOP_MS)
                }
            }
        }
    }

    private fun stopNow(reason: String) {
        if (!BackgroundPlaybackSession.snapshot.value.active) return
        Timber.i("Stopping playback: %s", reason)
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("session", "background stop: $reason")
        BackgroundPlaybackSession.stop()
    }

    private fun cancelPending() {
        handler.removeCallbacks(delayedStop)
        handler.removeCallbacks(deferredDecision)
        stopPending = false
        releaseWakeLock()
    }

    private fun screenOn(): Boolean =
        appContext?.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun acquireWakeLock(context: Context) {
        val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ambient TV:BackgroundStop")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        lock?.acquire(BACKGROUND_VIDEO_STOP_MS + 10_000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }
}
