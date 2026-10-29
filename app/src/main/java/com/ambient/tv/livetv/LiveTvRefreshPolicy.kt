package com.ambient.tv.livetv

internal data class LiveTvRefreshRequest(
    val force: Boolean,
    val announceSuccess: Boolean,
    internal val recoveryRevision: Long,
)

/** A recording mutation may interrupt or postpone a reconnect refresh, but must not lose it. */
internal class LiveTvRefreshPolicy {
    private var requestedRecovery = 0L
    private var completedRecovery = 0L

    val recoveryPending: Boolean get() = requestedRecovery > completedRecovery

    fun reconnected() { requestedRecovery++ }

    fun request(force: Boolean, announceSuccess: Boolean, actionInProgress: Boolean): LiveTvRefreshRequest? {
        if (actionInProgress) return null
        return LiveTvRefreshRequest(force || recoveryPending, announceSuccess, requestedRecovery)
    }

    /** Only a completed attempt consumes the request; cancellation leaves it pending. */
    fun completed(request: LiveTvRefreshRequest) {
        completedRecovery = maxOf(completedRecovery, request.recoveryRevision)
    }
}
