package com.ambient.tv.ui.youtube

import com.ambient.tv.data.local.MediaSelectionPreferences

sealed class YouTubeSelectionRoute {
    data class Details(val videoId: String) : YouTubeSelectionRoute()
    data class Play(val videoId: String) : YouTubeSelectionRoute()
    data class Ask(val videoId: String) : YouTubeSelectionRoute()
}

fun youtubeSelectionRoute(behavior: String?, videoId: String): YouTubeSelectionRoute {
    return when (MediaSelectionPreferences.normalize(behavior, MediaSelectionPreferences.PLAY)) {
        MediaSelectionPreferences.DETAILS -> YouTubeSelectionRoute.Details(videoId)
        MediaSelectionPreferences.ASK -> YouTubeSelectionRoute.Ask(videoId)
        else -> YouTubeSelectionRoute.Play(videoId)
    }
}
