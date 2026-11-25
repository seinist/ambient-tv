package com.ambient.tv.ui.games

import android.app.Activity
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.DisplayModeHandoff
import com.ambient.tv.ui.player.DisplayModeOrigin
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import com.ambient.tv.ui.player.awaitDisplayMode
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * The route, not a Libretro core or AndroidView, owns the physical mode.
 * A TV resolution-host reattachment can replace the native game session.
 * Keep the original desktop mode and the pending target across that restart.
 * All access is on Main; a cancelled native session must not restore a newer player's mode.
 */
internal class GameDisplaySession(private val activity: Activity?) {
    private var origin: DisplayModeOrigin? = null
    private var original: SupportedDisplayMode? = null
    private var requested: SupportedDisplayMode? = null
    private var failedKey: String? = null
    val hasLease: Boolean get() = origin != null

    fun actualMode(): SupportedDisplayMode? = DisplayRefreshRateMatcher.currentMode(activity)

    fun baseline(): SupportedDisplayMode? {
        val host = activity ?: return null
        if (BackgroundPlaybackSession.snapshot.value.active) return null
        if (origin == null) {
            val current = actualMode() ?: return null
            val captured = DisplayModeHandoff.origin(host)
            DisplayModeHandoff.register(captured)
            origin = captured
            original = DisplayRefreshRateMatcher.supportedModes(host)
                .firstOrNull { it.key == captured.claim.modeKey } ?: current
        }
        return original
    }

    suspend fun apply(target: SupportedDisplayMode, settleMs: Int): Boolean {
        val host = activity ?: return false
        if (target.key == failedKey) return false
        if (BackgroundPlaybackSession.snapshot.value.active) return false
        if (actualMode()?.key == target.key && requested == null) return true
        // A new core after a host reattachment waits for the original request instead
        // of sending the same HDMI change again. The route owns it until it exits.
        if (requested?.key != target.key || host.window.attributes.preferredDisplayModeId != target.modeId) {
            requested = target
            DisplayRefreshRateMatcher.requestDisplayMode(host, target, settleMs.toLong())
        }
        val confirmed = awaitDisplayMode(host, target.key, 3_500L)
        if (!confirmed) {
            Timber.w("Game display mode not confirmed: %s", target.key)
            failedKey = target.key
            restoreRequested()
            return false
        }
        if (settleMs > 0) delay(settleMs.toLong())
        return true
    }

    /** Restore a lease on policy disable; wait only if the physical output was changed. */
    suspend fun reset() {
        val mode = restoreRequested()
        finish()
        if (mode != null) awaitDisplayMode(activity, mode.key, 3_500L)
    }

    private fun restoreRequested(): SupportedDisplayMode? {
        val host = activity ?: return null
        val target = requested ?: return null
        requested = null
        if (host.window.attributes.preferredDisplayModeId != target.modeId) return null
        val captured = origin ?: return null
        val mode = DisplayRefreshRateMatcher.supportedModes(host)
            .firstOrNull { it.key == captured.claim.modeKey } ?: original
        // Mode IDs are display-specific; never request a mode no longer advertised.
        val restore = mode?.let { DisplayRefreshRateMatcher.preferredMode(host, it.key) }
        DisplayRefreshRateMatcher.restoreMode(host, restore?.modeId)
        return restore
    }

    private fun finish() {
        origin?.let(DisplayModeHandoff::unregister)
        origin = null
        original = null
        requested = null
        failedKey = null
    }

    /** Disposal is synchronous: no late coroutine may write over the next player. */
    fun close() {
        val host = activity
        val captured = origin
        val previous = requested
        val video = BackgroundPlaybackSession.snapshot.value
        if (host != null && captured != null && (video.active || video.replacingSource)) {
            val owns = previous != null && host.window.attributes.preferredDisplayModeId == previous.modeId
            finish()
            if (owns || captured.inherited) DisplayModeHandoff.defer(host, captured.claim)
            return
        }
        restoreRequested()
        // A deferred video hand-off might have been adopted without needing a new request.
        if (host != null && captured?.inherited == true && previous == null) {
            val restore = captured.claim.modeKey?.let { DisplayRefreshRateMatcher.preferredMode(host, it) }
            DisplayRefreshRateMatcher.restoreMode(host, restore?.modeId)
        }
        finish()
    }
}
