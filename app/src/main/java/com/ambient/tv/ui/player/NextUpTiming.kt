package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.PlaybackSegment

internal data class NextUpTiming(
    val remainingMs: Long,
    val credits: PlaybackSegment?,
    val offer: Boolean,
    val progressWindowSeconds: Int,
)

/** Markers control the preview, never the end of playback or the autoplay deadline. */
internal fun nextUpTiming(
    positionMs: Long,
    durationMs: Long,
    segments: List<PlaybackSegment>,
    useCredits: Boolean,
    leadSeconds: Int,
): NextUpTiming {
    val remaining = (durationMs - positionMs).coerceAtLeast(0)
    val credits = if (useCredits) segments.filter { it.type == "credits" } else emptyList()
    val active = credits.firstOrNull { positionMs >= it.startMs && (it.endMs < 0 || positionMs < it.endMs) }
    // A gap after a marked credits section may contain a scene: keep the preview out of it.
    val offer = active != null || (credits.isEmpty() && remaining <= leadSeconds.coerceAtLeast(1) * 1_000L)
    val window = active?.let { ((durationMs - it.startMs).coerceAtLeast(1) + 999) / 1_000 }
        ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: leadSeconds.coerceAtLeast(1)
    return NextUpTiming(remaining, active, offer, window)
}
