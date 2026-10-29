package com.ambient.tv.ui.detail

/** Serializes detail reads with user actions; confined to the ViewModel's main thread. */
internal class DetailRefreshPolicy {
    private var generation = 0L
    private var actionInProgress = false
    private var refreshPending = false

    /** Automatic entry/recovery reads wait for the action instead of overtaking its result. */
    fun beginLoad(): Long? {
        if (actionInProgress) {
            refreshPending = true
            return null
        }
        return ++generation
    }

    fun isCurrent(readGeneration: Long): Boolean =
        !actionInProgress && readGeneration == generation

    /** An interrupted progressive read is resumed even if no new refresh arrives during the action. */
    fun beginAction(readInProgress: Boolean): Boolean {
        if (actionInProgress) return false
        actionInProgress = true
        refreshPending = refreshPending || readInProgress
        generation++
        return true
    }

    /** Both successful and failed actions release at most one pending refresh. */
    fun finishAction(): Boolean {
        actionInProgress = false
        return refreshPending.also { refreshPending = false }
    }
}
