package com.ambient.tv.domain.model

/** Absolute times on the original media timeline; endMs = -1 denotes credits with no known end. */
data class PlaybackSegment(val type: String, val startMs: Long, val endMs: Long)

fun normalizePlaybackSegments(segments: List<PlaybackSegment>, durationMs: Long): List<PlaybackSegment> =
    segments.filter { it.type in setOf("intro", "credits") && it.startMs >= 0 &&
        (it.endMs > it.startMs || (it.type == "credits" && it.endMs == -1L)) &&
        (durationMs <= 0 || (it.startMs < durationMs && it.endMs <= durationMs)) }
        .distinct().sortedBy { it.startMs }.take(100)

/** Typed chapter markers only; chapter titles are not reliable intro boundaries. */
fun playbackSegmentsFromMarkers(markers: List<Pair<String?, Long>>): List<PlaybackSegment> {
    var start: Long? = null
    return buildList {
        for ((type, time) in markers.sortedBy { it.second }) {
            if (time < 0) continue
            when (type) {
                "IntroStart" -> start = time
                "CreditsStart" -> add(PlaybackSegment("credits", time, -1L))
                "IntroEnd" -> {
                    start?.takeIf { it < time }?.let { add(PlaybackSegment("intro", it, time)) }
                    start = null
                }
            }
        }
    }
}
