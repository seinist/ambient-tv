package com.ambient.tv.ui.player

/** Media3 start/rebuffer thresholds for one playback. */
internal data class Media3BufferThresholds(val startMs: Int, val rebufferMs: Int)

/**
 * Live TV runs close to the edge, where data only arrives in real time. Media3's defaults (start
 * at 1 s, resume at 2 s) turn every small upstream hiccup into a stall and an audio resync.
 * - Local timeshift: the player opens [com.ambient.tv.livetv.LIVE_TIMESHIFT_PREROLL_MS] behind
 *   live, so 2.5 s are there at once and starting stays instant.
 * - Direct live (no timeshift): start as before (zapping stays fast), but resume after a stall
 *   only with a real cushion.
 * Non-live playback keeps the defaults (null).
 */
internal fun media3LiveBufferThresholds(live: Boolean, timeshift: Boolean): Media3BufferThresholds? = when {
    timeshift -> Media3BufferThresholds(startMs = 2_500, rebufferMs = 3_500)
    live -> Media3BufferThresholds(startMs = 1_000, rebufferMs = 3_500)
    else -> null
}

/** VLC caching for the local timeshift loopback; below the live lead so starting stays instant. */
internal const val VLC_TIMESHIFT_NETWORK_CACHING_MS = 3_000

/**
 * Media options for live streams in VLC.
 * - `clock-jitter`: the instance runs with 0 (low latency for files). For live TV that means
 *   every late PCR resets the clock instead of growing the delay a little: an audible "forced
 *   resync". 5000 ms is VLC's own default.
 * - `network-caching`: only for the local timeshift loopback, see [VLC_TIMESHIFT_NETWORK_CACHING_MS].
 */
internal fun vlcLiveMediaOptions(live: Boolean, timeshift: Boolean): List<String> = buildList {
    if (live || timeshift) add(":clock-jitter=5000")
    if (timeshift) add(":network-caching=$VLC_TIMESHIFT_NETWORK_CACHING_MS")
}

/**
 * MPV options for the local timeshift loopback. Without its cache mpv cannot pause for data and
 * runs dry at the live edge (audio underrun, then A/V resync). With the cache it pauses briefly
 * and resumes once a few seconds are buffered again.
 */
internal fun mpvTimeshiftCacheOptions(timeshift: Boolean): List<Pair<String, String>> =
    if (!timeshift) emptyList() else listOf("cache-pause" to "yes", "cache-pause-wait" to "3")
