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

/** Small platform boundary so HDMI ownership and cancellation are unit-testable. */
internal interface GameDisplayHost {
    fun actualMode(): SupportedDisplayMode?
    fun modes(): List<SupportedDisplayMode>
    fun preferenceId(): Int?
    fun videoOwnsDisplay(): Boolean
    fun captureOrigin(): DisplayModeOrigin?
    fun releaseOrigin(origin: DisplayModeOrigin)
    fun handoff(origin: DisplayModeOrigin)
    fun request(mode: SupportedDisplayMode, settleMs: Int)
    fun restore(modeId: Int?)
    suspend fun confirm(key: String, timeoutMs: Long): Boolean
}

private class AndroidGameDisplayHost(private val activity: Activity?) : GameDisplayHost {
    override fun actualMode() = DisplayRefreshRateMatcher.currentMode(activity)
    override fun modes() = activity?.let(DisplayRefreshRateMatcher::supportedModes).orEmpty()
    override fun preferenceId() = activity?.window?.attributes?.preferredDisplayModeId
    override fun videoOwnsDisplay(): Boolean {
        val snapshot = BackgroundPlaybackSession.snapshot.value
        return snapshot.active || snapshot.replacingSource
    }
    override fun captureOrigin(): DisplayModeOrigin? = activity?.let {
        DisplayModeHandoff.origin(it).also(DisplayModeHandoff::register)
    }
    override fun releaseOrigin(origin: DisplayModeOrigin) = DisplayModeHandoff.unregister(origin)
    override fun handoff(origin: DisplayModeOrigin) {
        activity?.let { DisplayModeHandoff.defer(it, origin.claim) }
    }
    override fun request(mode: SupportedDisplayMode, settleMs: Int) {
        DisplayRefreshRateMatcher.requestDisplayMode(activity, mode, settleMs.toLong())
    }
    override fun restore(modeId: Int?) = DisplayRefreshRateMatcher.restoreMode(activity, modeId)
    override suspend fun confirm(key: String, timeoutMs: Long) = awaitDisplayMode(activity, key, timeoutMs)
}

/**
 * The route, not a Libretro core or AndroidView, owns the physical mode.
 * A TV resolution-host reattachment can replace the native game session.
 * Keep the original desktop mode and pending target across that restart.
 * All access is on Main; disposal never schedules a late restore over a new player.
 */
internal class GameDisplaySession(private val host: GameDisplayHost) {
    constructor(activity: Activity?) : this(AndroidGameDisplayHost(activity))

    private var origin: DisplayModeOrigin? = null
    private var original: SupportedDisplayMode? = null
    private var originalPreference: Int? = null
    private var requested: SupportedDisplayMode? = null
    private var failedKey: String? = null
    val hasLease: Boolean get() = origin != null

    fun actualMode(): SupportedDisplayMode? = host.actualMode()

    fun baseline(): SupportedDisplayMode? {
        if (host.videoOwnsDisplay()) return null
        if (origin == null) {
            val current = actualMode() ?: return null
            val captured = host.captureOrigin() ?: return null
            origin = captured
            originalPreference = host.preferenceId()
            original = host.modes().firstOrNull { it.key == captured.claim.modeKey } ?: current
        }
        return restoreTarget() ?: original
    }

    suspend fun apply(target: SupportedDisplayMode, settleMs: Int): Boolean {
        if (target.key == failedKey || host.videoOwnsDisplay() || origin == null) return false
        failedKey = null
        if (actualMode()?.key == target.key && requested == null) return true
        // A new core after a host reattachment waits for the original request instead
        // of sending the same HDMI change again. The route owns it until it exits.
        if (requested?.key != target.key || requested?.modeId != target.modeId || host.preferenceId() != target.modeId) {
            val previous = requested
            requested = target
            val accepted = runCatching { host.request(target, settleMs) }
                .onFailure { Timber.w(it, "Game display request failed") }.isSuccess
            if (!accepted) {
                if (!ownsRequest()) requested = previous
                failedKey = target.key
                if (!host.videoOwnsDisplay()) restoreOwned()?.let { host.confirm(it.key, SWITCH_TIMEOUT_MS) }
                return false
            }
        }
        val confirmed = host.confirm(target.key, SWITCH_TIMEOUT_MS)
        if (!confirmed) {
            Timber.w("Game display mode not confirmed: %s", target.key)
            failedKey = target.key
            // Roll back the window request and hold playback through the return transition too.
            if (!host.videoOwnsDisplay()) restoreOwned()?.let { host.confirm(it.key, SWITCH_TIMEOUT_MS) }
            return false
        }
        if (settleMs > 0) delay(settleMs.toLong())
        return true
    }

    /** Restore a lease on policy disable; wait only if the physical output was changed. */
    suspend fun reset() {
        if (host.videoOwnsDisplay()) {
            close()
            return
        }
        val mode = restoreOwned()
        finish()
        if (mode != null) host.confirm(mode.key, SWITCH_TIMEOUT_MS)
    }

    private fun restoreTarget(): SupportedDisplayMode? {
        val key = origin?.claim?.modeKey ?: original?.key ?: return null
        return host.modes().firstOrNull { it.key == key }
    }

    private fun ownsRequest(): Boolean = requested?.let { host.preferenceId() == it.modeId } == true

    private fun ownsInheritedMode(): Boolean =
        requested == null && origin?.inherited == true && host.preferenceId() == originalPreference

    private fun restoreOwned(): SupportedDisplayMode? {
        if (origin == null) return null
        val owns = ownsRequest() || ownsInheritedMode()
        requested = null
        if (!owns || host.videoOwnsDisplay()) return null
        // IDs are display-specific; never request an old ID that is no longer advertised.
        val restore = restoreTarget()
        val accepted = runCatching { host.restore(restore?.modeId) }
            .onFailure { Timber.w(it, "Game display restore failed") }.isSuccess
        // With an inherited lease, avoid a second restore on later disposal.
        origin?.inherited = false
        return restore.takeIf { accepted }
    }

    private fun finish() {
        origin?.let(host::releaseOrigin)
        origin = null
        original = null
        originalPreference = null
        requested = null
        failedKey = null
    }

    fun close() {
        val captured = origin
        if (captured != null && host.videoOwnsDisplay()) {
            val owns = ownsRequest() || ownsInheritedMode()
            finish()
            if (owns) host.handoff(captured)
            return
        }
        restoreOwned()
        finish()
    }

    private companion object {
        const val SWITCH_TIMEOUT_MS = 3_500L
    }
}
