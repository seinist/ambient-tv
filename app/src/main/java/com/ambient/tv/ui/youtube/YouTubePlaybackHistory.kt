package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem

/** The videos opened by this player destination, independent of its decoder and browse history. */
internal class YouTubePlaybackHistory {
    private data class Request(val videoId: String, val index: Int?)

    private val videos = mutableListOf<YouTubeItem>()
    private var index = -1
    private var pending: Request? = null

    val currentVideoId: String? get() = videos.getOrNull(index)?.videoId
    val previousVideoId: String? get() = videos.getOrNull(index - 1)?.videoId
    val nextVideo: YouTubeItem? get() = videos.getOrNull(index + 1)

    fun requestPrevious(currentId: String, previousId: String): Boolean {
        if (pending != null || currentVideoId != currentId || previousVideoId != previousId) return false
        pending = Request(previousId, index - 1)
        return true
    }

    fun requestNext(currentId: String, nextId: String): Boolean {
        if (pending != null || currentVideoId != currentId || nextId.isBlank() || nextId == currentId) return false
        // After Previous, Next follows the same path instead of replacing it with a fresh suggestion.
        val forward = nextVideo
        if (forward != null && forward.videoId != nextId) return false
        pending = Request(nextId, (index + 1).takeIf { forward != null })
        return true
    }

    /** Revisited videos start at zero, including ones whose saved position is already at EOF. */
    fun startsAtBeginning(videoId: String): Boolean = pending?.let { it.videoId == videoId && it.index != null } == true

    /** Confirm only successful loads. Retries and renderer remounts never add another history entry. */
    fun loaded(video: YouTubeItem): Boolean {
        val request = pending
        if (request == null) {
            if (index < 0) {
                videos += video
                index = 0
            } else {
                if (currentVideoId != video.videoId) return false
                videos[index] = video
            }
            return true
        }
        if (request.videoId != video.videoId) return false
        if (request.index == null) {
            videos += video
            index = videos.lastIndex
        } else {
            index = request.index
            videos[index] = video
        }
        pending = null
        return true
    }
}
