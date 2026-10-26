package com.ambient.tv.livetv

import java.util.concurrent.ConcurrentHashMap

/**
 * The channel last watched per live source (start and every successful zap). Live TV and the
 * Guide take it once when they are entered again, so focus lands on that channel after the
 * player stops - never while the user is browsing next to a running mini-player.
 */
object LiveTvReturnFocus {
    private val watched = ConcurrentHashMap<String, String>()

    fun watched(sourceId: String, channelUuid: String) {
        if (sourceId.isNotBlank() && channelUuid.isNotBlank()) watched[sourceId] = channelUuid
    }

    fun take(sourceId: String): String? = watched.remove(sourceId)

    fun clear() = watched.clear()
}
