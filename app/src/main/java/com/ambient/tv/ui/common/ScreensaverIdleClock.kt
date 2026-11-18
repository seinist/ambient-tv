package com.ambient.tv.ui.common

/**
 * Idle time for the app screensaver. Playback counts as activity: the wait starts at the later
 * of the last user input and the moment playback stopped being active. Without this, a short
 * renderer rebuild or stream reload (playback briefly inactive) after a long, input-free viewing
 * session started the screensaver at once, because the timeout since the last key had long expired.
 *
 * Main-thread only; [observe] is called whenever the playback flag may have changed.
 */
internal class ScreensaverIdleClock(initialPlaybackActive: Boolean = false) {
    private var playbackActive = initialPlaybackActive
    private var playbackEndedAtMs = Long.MIN_VALUE

    /** Records the playback flag; a true → false transition restarts the idle wait at [nowMs]. */
    fun observe(playbackActive: Boolean, nowMs: Long) {
        if (this.playbackActive && !playbackActive) playbackEndedAtMs = nowMs
        this.playbackActive = playbackActive
    }

    /** Start of the current idle period. */
    fun idleSince(lastInteractionMs: Long): Long = maxOf(lastInteractionMs, playbackEndedAtMs)

    /** Milliseconds until the screensaver may start; 0 or less means it is due. */
    fun remainingMs(lastInteractionMs: Long, nowMs: Long, timeoutMs: Long): Long =
        timeoutMs - (nowMs - idleSince(lastInteractionMs))
}
