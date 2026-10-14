package com.ambient.tv.ui.common

fun formatDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0) return null
    val totalMinutes = durationMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}min"
        else -> "${minutes}min"
    }
}

/**
 * Playback clock for the UI: `h:mm:ss` from one hour on, `m:ss` below; negative counts as zero.
 * One format for every player, bar and panel (they used to differ: `05:07` vs `5:07`).
 * ASCII digits on purpose: positions are compared side by side with the seek bar.
 */
fun formatPlaybackClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = total / 60L % 60L
    val seconds = total % 60L
    fun twoDigits(value: Long) = if (value < 10L) "0$value" else value.toString()
    return if (hours > 0L) "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}" else "$minutes:${twoDigits(seconds)}"
}

fun formatResolution(videoResolution: String?): String? {
    // Plex sends "4k", "1080" or "sd"; Jellyfin and Emby send the frame height as "2160p".
    val value = videoResolution?.lowercase()?.removeSuffix("p")?.takeIf { it.isNotBlank() } ?: return null
    return when (value) {
        "4k", "2160" -> "4K"
        "sd" -> "SD"
        else -> "${value}p"
    }
}

/** Approximate pixel count for a Plex `videoResolution` value, for comparing quality tiers. */
fun resolutionPixelCount(videoResolution: String?): Long? = when (videoResolution?.lowercase()) {
    null -> null
    "4k" -> 3840L * 2160L
    "1080" -> 1920L * 1080L
    "720" -> 1280L * 720L
    "576" -> 1024L * 576L
    "480" -> 854L * 480L
    "sd" -> 720L * 480L
    else -> videoResolution.toIntOrNull()?.let { height -> height.toLong() * height * 16L / 9L }
}

/** Drop stack-like / package noise; keep short human-readable network hints when present. */
fun sanitizeUiErrorMessage(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if ("exception" in lower || "at " in lower || "java." in lower || "kotlin." in lower) return null
    if (trimmed.length > 160) return trimmed.take(157).trimEnd() + "…"
    return trimmed
}
