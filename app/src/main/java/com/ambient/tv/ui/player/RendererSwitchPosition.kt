package com.ambient.tv.ui.player

/** A positive live clock is not a seekable resume point for a newly opened stream. */
internal fun rendererReopenPositionMs(isLive: Boolean, positionMs: Long): Long =
    if (isLive) 0L else positionMs.coerceAtLeast(0L)

/** A renderer that is still preparing can report zero even after playback was requested later. */
internal fun rendererSwitchPositionMs(
    reportedMs: Long?,
    screenMs: Long,
    reportedZeroReady: Boolean = false,
): Long = reportedMs?.takeIf { it > 0L || (it == 0L && reportedZeroReady) } ?: screenMs.coerceAtLeast(0L)

/** Capture before detach/release: a queued VLC seek to zero is an authoritative request. */
internal fun rendererZeroPositionReady(handle: PlaybackHandle?): Boolean = when (handle) {
    is com.ambient.tv.ui.player.backend.VlcPlaybackController -> handle.hasPendingSeek() || handle.canSeekNow()
    is Media3PlaybackHandle -> handle.player.playbackState == androidx.media3.common.Player.STATE_READY
    else -> false
}
