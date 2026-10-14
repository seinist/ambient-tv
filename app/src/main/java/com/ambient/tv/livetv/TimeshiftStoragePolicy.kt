package com.ambient.tv.livetv

import android.content.Context
import com.ambient.tv.data.local.AppPreferences

internal fun recommendedTimeshiftMb(freeBytes: Long): Int {
    val freeMb = freeBytes.coerceAtLeast(0L) / (1024L * 1024L)
    val budget = minOf(maxOf(512L, freeMb / 10L), (freeMb - 1024L).coerceAtLeast(0L))
    return AppPreferences.LIVE_TIMESHIFT_SIZES.last { it <= budget }
}

/** Uses the same volume as the actual timeshift buffer. Existing user/backup values win. */
internal fun AppPreferences.initializeTimeshiftStorage(context: Context) {
    if (hasLiveTimeshiftSize) return
    val freeBytes = runCatching { (context.externalCacheDir ?: context.cacheDir).usableSpace }.getOrNull() ?: return
    if (freeBytes > 0L) initializeLiveTimeshiftSize(recommendedTimeshiftMb(freeBytes))
}
