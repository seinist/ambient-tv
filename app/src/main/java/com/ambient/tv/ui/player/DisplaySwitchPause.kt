package com.ambient.tv.ui.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.math.abs

/** How playback is held while HDMI renegotiates (Kodi: "Delay after change of refresh rate"). */
internal enum class DisplaySwitchHold { NONE, PAUSE, MUTE }

internal enum class DisplaySwitchOutcome { NOT_HELD, RESUMED, RESUMED_AFTER_TIMEOUT, USER_TOOK_OVER, CANCELLED }

internal object DisplaySwitchPause {
    /** Upper bound for the DisplayManager confirmation before the configured delay starts anyway. */
    const val CONFIRM_TIMEOUT_MS = 6_000L
    /** Earlier positions restart the title so the first seconds are not lost to the resync. */
    const val RESTART_BELOW_MS = 3_000L
    /** A position jump larger than this while held is a user seek, not decoder drift. */
    const val USER_SEEK_TOLERANCE_MS = 1_500L
    const val POLL_MS = 100L
}

/** Pause only for a real mode change; live without timeshift cannot hold its position, so it is muted. */
internal fun displaySwitchHoldFor(targetKey: String?, currentKey: String?, delayMs: Int, isLive: Boolean, timeshift: Boolean): DisplaySwitchHold = when {
    delayMs <= 0 || targetKey == null || targetKey == currentKey -> DisplaySwitchHold.NONE
    isLive && !timeshift -> DisplaySwitchHold.MUTE
    else -> DisplaySwitchHold.PAUSE
}

/**
 * Null keeps the current position; live streams are never rewound. A title paused right at its
 * start restarts at 0 only while it is still there: a resume position applied after the pause
 * (pausedAt read before the start seek landed) must not be thrown away.
 */
internal fun displaySwitchResumeSeekMs(pausedAtMs: Long, isLive: Boolean, currentMs: Long = pausedAtMs): Long? =
    if (!isLive && pausedAtMs < DisplaySwitchPause.RESTART_BELOW_MS && currentMs < DisplaySwitchPause.RESTART_BELOW_MS) 0L else null

/**
 * Whether the held player was taken over by the user. Only an explicit play does that for live
 * TV: its position moves on internal reopens (zero-copy switch, timeshift live edge). For VOD a
 * jump counts as a user seek, unless the engine reopened the stream itself in between.
 */
internal fun displaySwitchUserTookOver(
    wantsToPlay: Boolean, isLive: Boolean, pausedAtMs: Long, positionMs: Long, reopened: Boolean,
): Boolean = wantsToPlay ||
    (!isLive && !reopened && abs(positionMs - pausedAtMs) > DisplaySwitchPause.USER_SEEK_TOLERANCE_MS)

/** The window covering an app-requested HDMI change must outlast confirmation plus delay. */
internal fun displaySwitchTransitionExtraMs(delayMs: Int): Long =
    if (delayMs <= 0) 0L else DisplaySwitchPause.CONFIRM_TIMEOUT_MS + delayMs

/** Engine surface the hold needs; [PlaybackHandle] in production, a fake in tests. */
internal interface DisplaySwitchPlayer {
    fun wantsToPlay(): Boolean
    fun positionMs(): Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setOutputMuted(muted: Boolean)
    /** Bumped by the engine whenever it reopens the stream on its own (decoder switch, fallback). */
    fun reopenGeneration(): Int = 0
}

internal fun PlaybackHandle.asDisplaySwitchPlayer(): DisplaySwitchPlayer {
    val handle = this
    return object : DisplaySwitchPlayer {
        override fun wantsToPlay() = handle.wantsToPlay()
        override fun positionMs() = handle.status().positionMs
        override fun play() = handle.play()
        override fun pause() = handle.pause()
        override fun seekTo(positionMs: Long) = handle.seekTo(positionMs)
        override fun setOutputMuted(muted: Boolean) = handle.setOutputMuted(muted)
        override fun reopenGeneration() = handle.reopenGeneration()
    }
}

/**
 * Holds playback around one display mode request: pause (or mute), request, wait for the
 * DisplayManager confirmation (bounded), wait the configured delay, then resume.
 * A user play/seek while paused ends the hold without auto-resume. When [stillOwned] is false
 * (title or engine changed) nothing is resumed. Other cancellation resumes immediately.
 */
internal suspend fun holdForDisplaySwitch(
    player: DisplaySwitchPlayer,
    hold: DisplaySwitchHold,
    delayMs: Int,
    isLive: Boolean,
    request: () -> Unit,
    awaitConfirmed: suspend (timeoutMs: Long) -> Boolean,
    stillOwned: () -> Boolean,
): DisplaySwitchOutcome {
    val effective = if (hold == DisplaySwitchHold.PAUSE && !player.wantsToPlay()) DisplaySwitchHold.NONE else hold
    if (effective == DisplaySwitchHold.NONE) {
        request()
        return DisplaySwitchOutcome.NOT_HELD
    }
    var pausedAt = player.positionMs()
    var generation = player.reopenGeneration()
    var userTookOver = false
    var completed = false
    if (effective == DisplaySwitchHold.PAUSE) player.pause() else player.setOutputMuted(true)
    try {
        request()
        val confirmed = coroutineScope {
            val waiting = async {
                val ok = awaitConfirmed(DisplaySwitchPause.CONFIRM_TIMEOUT_MS)
                delay(delayMs.toLong())
                ok
            }
            while (waiting.isActive) {
                val reopened = player.reopenGeneration() != generation
                if (effective == DisplaySwitchHold.PAUSE &&
                    displaySwitchUserTookOver(player.wantsToPlay(), isLive, pausedAt, player.positionMs(), reopened)) {
                    userTookOver = true
                    waiting.cancel()
                    break
                }
                if (reopened) {
                    // The engine restarted the stream itself: its new position is the held one.
                    generation = player.reopenGeneration()
                    pausedAt = player.positionMs()
                }
                delay(DisplaySwitchPause.POLL_MS)
            }
            if (userTookOver) false else waiting.await()
        }
        completed = true
        return when {
            userTookOver -> DisplaySwitchOutcome.USER_TOOK_OVER
            confirmed -> DisplaySwitchOutcome.RESUMED
            else -> DisplaySwitchOutcome.RESUMED_AFTER_TIMEOUT
        }
    } finally {
        withContext(NonCancellable) {
            val owned = stillOwned()
            if (effective == DisplaySwitchHold.MUTE) {
                // Unmute even when the title changed: the engine can be reused by the next title.
                runCatching { player.setOutputMuted(false) }
            } else if (!userTookOver && owned) {
                displaySwitchResumeSeekMs(pausedAt, isLive, player.positionMs())?.let(player::seekTo)
                player.play()
            }
            if (!completed) Timber.i("DisplaySwitchPause cancelled owned=%s", owned)
        }
    }
}

/** Suspends until the physical display reports [targetKey], or false after [timeoutMs]. */
internal suspend fun awaitDisplayMode(activity: Activity?, targetKey: String, timeoutMs: Long): Boolean {
    if (activity == null) return false
    if (DisplayRefreshRateMatcher.currentModeKey(activity) == targetKey) return true
    val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (DisplayRefreshRateMatcher.currentModeKey(activity) == targetKey && continuation.isActive) {
                        manager.unregisterDisplayListener(this)
                        continuation.resume(true)
                    }
                }
            }
            manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            continuation.invokeOnCancellation { manager.unregisterDisplayListener(listener) }
            // The mode can have changed between the first check and the registration.
            if (DisplayRefreshRateMatcher.currentModeKey(activity) == targetKey && continuation.isActive) {
                manager.unregisterDisplayListener(listener)
                continuation.resume(true)
            }
        }
    } ?: false
}
