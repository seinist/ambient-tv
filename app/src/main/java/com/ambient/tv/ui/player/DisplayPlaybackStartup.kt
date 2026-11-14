package com.ambient.tv.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ambient.tv.data.local.AppPreferences
import timber.log.Timber

/**
 * Start VOD in a held state while its decoder discovers the source cadence/size.
 *
 * Otherwise Auto mode matching is only possible *after* the first frames have played:
 * the HDMI change then pauses the title and can replay its opening seconds. Preparing the
 * decoder while paused still exposes formats on Media3, MPV and VLC (VLC probes on Playing
 * and immediately pauses). This is never used for live streams without a stable start.
 */
internal fun shouldHoldInitialDisplayPlayback(
    television: Boolean,
    hasVideo: Boolean,
    isLive: Boolean,
    samePlayback: Boolean,
    wantsToPlay: Boolean,
    modeCount: Int,
    adjustmentKey: String,
    preferredModeKey: String,
): Boolean {
    val canRequestMode = adjustmentKey != AppPreferences.DISPLAY_REFRESH_OFF ||
        (preferredModeKey != "system" &&
            preferredModeKey != AppPreferences.DISPLAY_MODE_AUTO_CONTENT &&
            preferredModeKey != AppPreferences.DISPLAY_MODE_AUTO_DISPLAY)
    return television && hasVideo && !isLive && !samePlayback && wantsToPlay &&
        modeCount > 1 && canRequestMode
}

internal class DisplayStartupLease internal constructor(
    internal val player: PlaybackHandle,
    internal val route: String,
) {
    internal var interrupted = false
    internal var settling = false
}

/**
 * One foreground video owner at a time. The timer is a safety net for streams that never report
 * a usable format: playback must not remain paused forever. Once the matching coroutine claims
 * the lease, its separate bounded HDMI-confirmation timeout owns the rest of the wait.
 */
internal object DisplayPlaybackStartup {
    private const val FORMAT_TIMEOUT_MS = 6_000L
    private val handler = Handler(Looper.getMainLooper())
    private var lease: DisplayStartupLease? = null
    private var timeout: Runnable? = null

    fun arm(context: Context, metadata: PlaybackMetadata, player: PlaybackHandle, samePlayback: Boolean) {
        clear()
        val prefs = videoImagePreferences(context)
        val television = com.ambient.tv.ui.mobile.isTelevision(context)
        if (!television || !metadata.hasVideo || metadata.isLive || samePlayback || !player.wantsToPlay()) return
        if (!shouldHoldInitialDisplayPlayback(
                television = television, hasVideo = metadata.hasVideo, isLive = metadata.isLive,
                samePlayback = samePlayback, wantsToPlay = player.wantsToPlay(),
                modeCount = DisplayRefreshRateMatcher.supportedModes(context).size,
                adjustmentKey = prefs.displayRefreshAdjustmentKey,
                preferredModeKey = prefs.preferredDisplayModeKey,
            )) return

        val held = DisplayStartupLease(player, metadata.route)
        lease = held
        player.pause() // Before the first decoded frame, not after a visible start.
        val fallback = Runnable {
            if (lease === held && !held.settling) {
                Timber.w("DisplayStartup no usable source format within %dms; resuming", FORMAT_TIMEOUT_MS)
                finish(held, resume = true)
            }
        }
        timeout = fallback
        handler.postDelayed(fallback, FORMAT_TIMEOUT_MS)
        Timber.i("DisplayStartup awaiting first source format route=%s", metadata.route)
    }

    /** The physical mode may be the current one; no HDMI wait is needed in that case. */
    fun releaseIfWaiting(player: PlaybackHandle?) {
        lease?.takeIf { it.player === player && !it.settling }?.let { finish(it, resume = true) }
    }

    /** Only one format-driven effect may take responsibility for the initial play intent. */
    fun claim(player: PlaybackHandle?): DisplayStartupLease? {
        val held = lease?.takeIf { it.player === player && !it.interrupted && !it.settling } ?: return null
        held.settling = true
        timeout?.let(handler::removeCallbacks)
        timeout = null
        return held
    }

    fun isWaiting(player: PlaybackHandle?): Boolean =
        player != null && lease?.let { it.player === player && !it.interrupted } == true

    fun owns(held: DisplayStartupLease): Boolean =
        lease === held && !held.interrupted &&
            BackgroundPlaybackSession.activeHandle() === held.player &&
            BackgroundPlaybackSession.snapshot.value.metadata?.route == held.route

    /** Play/Pause/Seek requested by the user takes precedence over an automatic resume. */
    fun userOverride(player: PlaybackHandle?) {
        val held = lease?.takeIf { it.player === player } ?: return
        held.interrupted = true
        clear()
    }

    fun finish(held: DisplayStartupLease, resume: Boolean) {
        if (lease !== held) return
        val shouldResume = resume && owns(held)
        clear()
        if (shouldResume) held.player.play()
    }

    fun clearFor(player: PlaybackHandle?) {
        if (lease?.player === player) clear()
    }

    private fun clear() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        lease = null
    }
}
