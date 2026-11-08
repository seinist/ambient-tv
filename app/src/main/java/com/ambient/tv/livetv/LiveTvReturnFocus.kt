package com.ambient.tv.livetv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The channel last watched per live source (start and every successful zap). Live TV and the
 * Guide take it once when they are entered again, so focus lands on that channel after the
 * player stops - never while the user is browsing next to a running mini-player.
 */
object LiveTvReturnFocus {
    data class ReturnTarget(val channelUuid: String, val revision: Long)
    private var nextRevision = 0L
    private val watched = MutableStateFlow<Map<String, ReturnTarget>>(emptyMap())
    val pending = watched.asStateFlow()

    @Synchronized
    fun watched(sourceId: String, channelUuid: String) {
        if (sourceId.isNotBlank() && channelUuid.isNotBlank()) {
            watched.value = watched.value + (sourceId to ReturnTarget(channelUuid, ++nextRevision))
        }
    }

    @Synchronized
    fun take(sourceId: String): String? {
        val saved = watched.value[sourceId] ?: return null
        watched.value = watched.value - sourceId
        return saved.channelUuid
    }

    @Synchronized
    fun commit(sourceId: String, revision: Long, apply: (ReturnTarget) -> Unit): Boolean {
        val saved = watched.value[sourceId]?.takeIf { it.revision == revision } ?: return false
        apply(saved)
        watched.value = watched.value - sourceId
        return true
    }

    @Synchronized
    fun clear() { watched.value = emptyMap() }
}
