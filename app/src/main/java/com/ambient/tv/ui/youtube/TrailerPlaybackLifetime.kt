package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession

/** One player destination owns both its ready trailer and an unfinished Next/Previous handoff. */
internal class TrailerPlaybackLifetime {
    private var route: String? = null
    private var transitionToken: Long? = null

    fun loaded(videoId: String) {
        route = Destinations.trailerPlayer(videoId)
    }

    fun replacing(token: Long) {
        transitionToken = token
    }

    fun stopIfOwned() {
        val current = BackgroundPlaybackSession.snapshot.value
        val owned = if (current.replacingSource) {
            current.generation == transitionToken
        } else {
            current.metadata?.isTrailer == true && current.metadata.route == route
        }
        if (owned) BackgroundPlaybackSession.stop()
    }
}
