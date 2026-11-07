package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem

/** Keeps player navigation and its return address on the source that launched it. */
internal class YouTubePlayerNavigation(
    private val browseContext: YouTubeBrowsePlaybackContext,
    private val browseSession: Long?,
    private val isTrailer: Boolean,
) {
    private val history = YouTubePlaybackHistory()

    fun fromBrowse(videoId: String): Boolean = browseSession?.let { token ->
        if (isTrailer) browseContext.containsTrailer(token, videoId)
        else browseContext.contains(token, videoId)
    } == true

    fun previousVideoId(videoId: String): String? = when {
        fromBrowse(videoId) -> browseContext.previousLoaded(requireNotNull(browseSession), videoId)?.videoId
        isTrailer -> null
        else -> history.previousVideoId
    }

    fun nextVideo(videoId: String): YouTubeItem? = when {
        fromBrowse(videoId) -> browseContext.nextLoaded(requireNotNull(browseSession), videoId)
        isTrailer -> null
        else -> history.nextVideo
    }

    fun requestNext(currentId: String, nextId: String): Boolean {
        // A restored or replaced TMDB session must not borrow another row or stale history.
        if (isTrailer && nextVideo(currentId)?.videoId != nextId) return false
        return history.requestNext(currentId, nextId)
    }

    fun requestPrevious(currentId: String, previousId: String): Boolean {
        if (isTrailer && previousVideoId(currentId) != previousId) return false
        val sourcePrevious = if (fromBrowse(currentId)) {
            browseContext.previousLoaded(requireNotNull(browseSession), currentId)?.videoId
        } else null
        return history.requestPrevious(currentId, previousId, fromSource = sourcePrevious == previousId)
    }

    fun startsAtBeginning(videoId: String): Boolean = history.startsAtBeginning(videoId)

    /** Only a successful load moves focus; a failed Next keeps the last playable selection. */
    fun loaded(video: YouTubeItem): Boolean {
        if (!history.loaded(video)) return false
        if (fromBrowse(video.videoId)) browseContext.played(requireNotNull(browseSession), video.videoId)
        return true
    }
}
