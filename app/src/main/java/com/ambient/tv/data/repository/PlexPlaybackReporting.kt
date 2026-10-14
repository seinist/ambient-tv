package com.ambient.tv.data.repository

import com.ambient.tv.data.remote.PlexApiService
import kotlinx.coroutines.CancellationException

/** Keep Now Playing updated, and explicitly persist the resume offset on Stop. */
internal suspend fun reportPlexPlayback(
    api: PlexApiService,
    ratingKey: String,
    positionMs: Long,
    state: String,
    durationMs: Long?,
) {
    val timelineSaved = try {
        api.reportTimeline(
            ratingKey = ratingKey, key = "/library/metadata/$ratingKey",
            timeMs = positionMs, state = state, durationMs = durationMs?.takeIf { it > 0 },
        )
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!timelineSaved || state == "stopped") {
        api.reportProgress(ratingKey = ratingKey, timeMs = positionMs, state = state)
    }
}
