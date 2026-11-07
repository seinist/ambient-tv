package com.ambient.tv.ui.youtube

import com.ambient.tv.companion.CompanionPlaybackControls

/** Trailer transport must stay in the current destination, with its source queue and return target. */
internal fun youtubeCompanionControls(
    state: YouTubePlayerState,
    isTrailer: Boolean,
    streamControls: CompanionPlaybackControls.SourceControls?,
    onNext: (String) -> Unit,
    onPrevious: (String) -> Unit,
): CompanionPlaybackControls.SourceControls? {
    if (!isTrailer) return streamControls
    val playable = state.playback?.isLive == false
    return (streamControls ?: CompanionPlaybackControls.SourceControls()).copy(
        // The old metadata remains visible during a handoff. Keep consuming its remote
        // transport commands: a missing callback would launch an ordinary YouTube route.
        next = state.nextVideo?.videoId?.takeIf { playable }?.let { id -> { if (!state.loading) onNext(id) } },
        previous = state.previousVideoId?.takeIf { playable }?.let { id -> { if (!state.loading) onPrevious(id) } },
    )
}
