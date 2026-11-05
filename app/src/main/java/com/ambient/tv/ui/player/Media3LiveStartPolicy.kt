package com.ambient.tv.ui.player

/**
 * A live HLS/DASH window slides independently of historical playback clocks: leave the
 * initial seek unset so the manifest selects its live edge. An explicit timeshift session
 * owns a stable timeline, therefore retains its supplied position.
 */
internal fun media3InitialSeekPositionMs(
    startPositionMs: Long,
    liveStream: Boolean,
    timeshift: Boolean,
): Long? = if (liveStream && !timeshift) null else startPositionMs.coerceAtLeast(0L)
