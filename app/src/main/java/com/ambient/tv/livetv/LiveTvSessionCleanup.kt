package com.ambient.tv.livetv

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A reporting timeout must not cancel tuner/transcoder release. Each endpoint has its own
 * bounded attempt, so one unavailable endpoint cannot prevent the others from being released. */
internal suspend fun releaseLiveTvResources(vararg releases: suspend () -> Unit): Boolean = withContext(NonCancellable) {
    var complete = true
    for (release in releases) {
        val success = withTimeoutOrNull(3_000) {
            try { release(); true }
            catch (e: LiveTvHttpException) { e.status == 404 || e.status == 410 }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        } == true
        complete = success && complete
    }
    complete
}
