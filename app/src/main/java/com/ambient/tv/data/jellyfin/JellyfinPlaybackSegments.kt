package com.ambient.tv.data.jellyfin

import com.ambient.tv.domain.model.PlaybackSegment
import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.withTimeoutOrNull

/** Old servers, absent plugins and failed optional requests must preserve the rest of the timeline. */
internal suspend fun loadJellyfinPlaybackSegments(api: JellyfinApiService, itemId: String): List<PlaybackSegment> =
    withTimeoutOrNull(3_000) {
        suspendResult { api.getMediaSegments(itemId).items }.getOrDefault(emptyList())
    }.orEmpty().map {
        PlaybackSegment(if (it.type == "Outro") "credits" else it.type.lowercase(),
            if (it.startTicks < 0) -1 else it.startTicks / 10_000L,
            if (it.endTicks < 0) -1 else it.endTicks / 10_000L)
    }
