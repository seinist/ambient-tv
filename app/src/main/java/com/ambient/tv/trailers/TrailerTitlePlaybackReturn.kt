package com.ambient.tv.trailers

import com.ambient.tv.ui.youtube.YouTubeBrowsePlaybackContext

internal data class TrailerTitlePlaybackOrigin(
    val sessionToken: Long,
    val requestKey: String,
    val title: String,
    val videos: List<TrailerVideo>,
)

internal data class PreparedTrailerTitleReturn(
    val snapshot: YouTubeBrowsePlaybackContext.ReturnSnapshot,
    val title: String,
    val videos: List<TrailerVideo>,
) {
    val token: String get() = "youtube:${snapshot.revision}"
    val focusKey: String get() = "trailer-video:${snapshot.target.videoId}"
}

/** No old title/account/settings snapshot is allowed to supply the first returning layout. */
internal fun prepareTrailerTitleReturn(
    scope: String,
    requestKey: String,
    origin: TrailerTitlePlaybackOrigin?,
    pending: YouTubeBrowsePlaybackContext.ReturnSnapshot?,
    enabled: Boolean,
): PreparedTrailerTitleReturn? {
    if (!enabled || origin == null || origin.requestKey != requestKey) return null
    val returned = pending?.takeIf { it.scope == scope && it.sessionToken == origin.sessionToken } ?: return null
    if (origin.videos.none { it.key == returned.target.videoId }) return null
    return PreparedTrailerTitleReturn(returned, origin.title, origin.videos)
}
