package com.ambient.tv.youtube

import java.time.Instant
import java.time.OffsetDateTime

data class YouTubeLiveEvent(val title: String, val scheduledStartMs: Long?)

/** Expected service state, distinct from transport, authentication and decoder failures. */
class YouTubeLiveOfflineException(val event: YouTubeLiveEvent) : Exception("YouTube live stream is offline")

internal fun youtubeScheduledStartMs(seconds: String?, timestamp: String?): Long? {
    seconds?.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }
        ?.let { return it * 1000 }
    return timestamp?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }?.takeIf { it > 0 }
}

internal fun youtubeLiveCountdown(startMs: Long, nowMs: Long): String? {
    if (startMs <= nowMs) return null
    val seconds = (startMs - nowMs + 999) / 1000
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
