package com.ambient.tv.youtube

import kotlinx.coroutines.CancellationException

/**
 * [allowOtf]: SmartTube's generated MPD addresses OTF streams by segment (Media3 plays them like
 * SmartTube does); the bare stream URL VLC/MPV would open only carries the first segment.
 */
internal fun youtubeHasPlayableDashTracks(formats: List<YouTubeAdaptiveFormat>, allowOtf: Boolean = false): Boolean {
    val usable = formats.filter { !it.url.isNullOrBlank() && (allowOtf || !it.isOtf) }
    return usable.any { it.mimeType?.startsWith("video/") == true } &&
        usable.any { it.mimeType?.startsWith("audio/") == true }
}

/**
 * Prefer complete adaptive playback; never wait for DASH when live HLS is already ready.
 * A video+audio DASH response topping out at 480p gets one additional client attempt:
 * some YouTube clients expose only 360p while another offers the video's real 1080p.
 */
internal fun youtubePlayableFormatScore(
    unplayable: Boolean,
    live: Boolean,
    hasHls: Boolean,
    formats: List<YouTubeAdaptiveFormat>,
    preferLiveHls: Boolean = true,
    allowOtf: Boolean = false,
    requestedMaxHeight: Int = 0,
    hasProgressive: () -> Boolean,
): Int {
    if (unplayable) return 0
    // VLC's YouTube manifest path is deliberately disabled. Preserve recovery for a muxed
    // live fallback instead of calling an HLS-only response playable for that renderer.
    if (live && !preferLiveHls) return if (hasProgressive()) 4 else 0
    if (live && hasHls) return 4
    if (youtubeHasPlayableDashTracks(formats, allowOtf)) {
        val playableVideos = formats.filter {
            it.mimeType?.startsWith("video/", ignoreCase = true) == true &&
                !it.url.isNullOrBlank() && (allowOtf || !it.isOtf)
        }
        // Unknown sizes may already be high quality; don't delay them. A known low-only DASH
        // response is playable (score 3) but gets exactly one quality upgrade attempt.
        val lowOnly = playableVideos.isNotEmpty() && playableVideos.all { it.qualityHeight() in 1..480 }
        val meetsExplicitCap = requestedMaxHeight in 1..480 &&
            playableVideos.any { it.qualityHeight() == requestedMaxHeight }
        return if (lowOnly && !meetsExplicitCap) 3 else 4
    }
    if (hasProgressive()) return 2
    return if (hasHls) 1 else 0
}

/**
 * One quality upgrade with a playable fallback, up to five recovery switches without one.
 * A failed upgrade must not discard an already playable response. Calls stay sequential:
 * SmartTube's client selector is shared state, not a set of independent parallel requests.
 */
internal fun <T : Any> resolveYouTubeFormatInfo(
    load: (allowInnertube: Boolean) -> T,
    switchClient: () -> Boolean,
    score: (T) -> Int,
    checkActive: () -> Unit = {},
    isTerminal: (T) -> Boolean = { false },
    onAttempt: (attempt: Int, score: Int, startedNs: Long) -> Unit = { _, _, _ -> },
): T {
    var best: T? = null
    var bestScore = -1
    var lastError: Exception? = null
    var upgradesWithFallback = 0
    for (attempt in 0..5) {
        checkActive()
        if (attempt > 0 && bestScore > 0 && upgradesWithFallback++ >= 1) break
        val started = System.nanoTime()
        try {
            if (attempt > 0 && !switchClient()) break
            val candidate = load(attempt == 0)
            checkActive()
            if (isTerminal(candidate)) return best?.takeIf { bestScore > 0 } ?: candidate
            val candidateScore = score(candidate)
            onAttempt(attempt, candidateScore, started)
            if (candidateScore > bestScore) { best = candidate; bestScore = candidateScore }
            if (bestScore >= 4) return checkNotNull(best)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            onAttempt(attempt, -1, started)
        }
    }
    checkActive()
    return best ?: throw (lastError ?: IllegalStateException("No YouTube format response"))
}
