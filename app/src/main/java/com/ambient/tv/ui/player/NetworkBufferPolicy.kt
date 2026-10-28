package com.ambient.tv.ui.player

import android.app.ActivityManager
import android.content.Context

/**
 * Settings › Playback "Network buffer": one level for Media3, MPV and VLC. SmartTube keeps its own
 * buffer setting for its Media3 player. "medium" is what every player did before the setting.
 */
const val NETWORK_BUFFER_DEFAULT = "medium"
val NETWORK_BUFFER_KEYS = MEDIA3_BUFFER_KEYS

internal fun normalizeNetworkBufferKey(key: String?): String = key?.takeIf { it in NETWORK_BUFFER_KEYS } ?: NETWORK_BUFFER_DEFAULT

/** mpv demuxer cache; only used where mpv caches at all (remote servers, timeshift, YouTube). */
internal data class MpvNetworkCache(val maxBytes: String, val readaheadSecs: Int)

/** Native memory, so a 1 GB device gets a smaller "large" cache. */
internal fun mpvNetworkCache(key: String, lowRam: Boolean): MpvNetworkCache = when (normalizeNetworkBufferKey(key)) {
    "low" -> MpvNetworkCache("32MiB", 10)
    "high" -> if (lowRam) MpvNetworkCache("96MiB", 40) else MpvNetworkCache("160MiB", 60)
    else -> MpvNetworkCache("64MiB", 20)
}

/** LibVLC's network-caching: a larger value smooths dips but also delays the first frame and seeks. */
internal fun vlcNetworkCachingMs(key: String): Int = when (normalizeNetworkBufferKey(key)) {
    "low" -> 800
    "high" -> 3_000
    else -> 1_200
}

/** Low-RAM devices and anything below 1.5 GB of RAM. */
internal fun lowRamDevice(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    if (manager.isLowRamDevice) return true
    val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    return info.totalMem in 1 until 1_536L * 1024 * 1024
}
