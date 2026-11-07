package com.ambient.tv.ui.youtube

import com.ambient.tv.util.suspendResult
import com.ambient.tv.youtube.YouTubeChannelInfo
import com.ambient.tv.youtube.YouTubeMetadata
import com.ambient.tv.youtube.applyYouTubePlaybackMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Details may arrive after a playable stream, including trailers opened without a YouTube card. */
internal suspend fun updateYouTubePlayerMetadata(
    videoId: String,
    isTrailer: Boolean,
    state: MutableStateFlow<YouTubePlayerState>,
    loadMetadata: suspend () -> YouTubeMetadata?,
    loadChannel: suspend (YouTubeMetadata) -> YouTubeChannelInfo?,
) {
    val detail = suspendResult { loadMetadata() }.getOrNull()
    val channel = if (isTrailer) null else detail?.let { loadChannel(it) }
    state.update { current ->
        val playback = current.playback ?: return@update current
        if (playback.videoId != videoId) return@update current
        current.copy(
            // A resolved start (zero for trailers) stays final. New progress reports belong
            // to the running session, not to the historical start supplied by late metadata.
            playback = if (detail != null) {
                applyYouTubePlaybackMetadata(playback, detail, useCurrentProgress = false)
            } else playback,
            channel = channel,
        )
    }
}
