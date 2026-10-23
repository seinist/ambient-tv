package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.PlaybackSegment

/** An open marker is a provider claim; a bounded marker must reach the actual end. */
internal fun finalCreditsAt(positionMs: Long, durationMs: Long, segments: List<PlaybackSegment>): PlaybackSegment? {
    if (durationMs <= 0 || positionMs < 0) return null
    val credits = com.ambient.tv.domain.model.normalizePlaybackSegments(segments, durationMs)
        .filter { it.type == "credits" }
    val last = credits.lastOrNull() ?: return null
    return last.takeIf { positionMs >= it.startMs &&
        (it.endMs == -1L || it.endMs >= durationMs) && positionMs <= durationMs }
}

internal fun playbackIsComplete(positionMs: Long, durationMs: Long, ended: Boolean,
    segments: List<PlaybackSegment>): Boolean = ended ||
    (durationMs > 0 && positionMs.toDouble() / durationMs >= .9) ||
    finalCreditsAt(positionMs, durationMs, segments) != null

/** Count only observed playback time. Seeking restarts the countdown; rewinding cancels it. */
internal class CreditsCountdown {
    private var marker: PlaybackSegment? = null
    private var previous: Long? = null
    private var wasPlaying = false
    private var elapsed = 0L
    private val cancelled = mutableSetOf<PlaybackSegment>()

    fun update(active: PlaybackSegment?, positionMs: Long, playing: Boolean, delaySeconds: Int): Long? {
        val delta = previous?.let { positionMs - it }
        if (delta != null && delta < -1_000) marker?.let(cancelled::add)
        val sameMarker = active == marker
        if (!sameMarker || active == null || delaySeconds <= 0) elapsed = 0
        if (sameMarker && active != null && delta != null) {
            when {
                delta > 2_000 -> elapsed = 0
                playing && wasPlaying && delta > 0 -> elapsed += delta
            }
        }
        marker = active
        previous = positionMs
        wasPlaying = playing
        return if (active == null || delaySeconds <= 0 || active in cancelled) null
        else (delaySeconds * 1_000L - elapsed).coerceAtLeast(0)
    }
}
