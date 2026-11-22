package com.ambient.tv.connectivity

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A user retry first confirms the actual service, then the physical machine. Only a machine
 * that still does not answer may need manual WOL; an existing recovery owns its own wake.
 * [reconnect] joins that recovery or reconnects the service after its machine comes back.
 *
 * Every suspended callback may outlive cancellation or an endpoint change. Neither a stale
 * result nor a non-cooperative callback may start another wake or reload the new endpoint.
 */
internal suspend fun retryOfflineService(
    checkService: suspend () -> Boolean,
    machineOnline: suspend () -> Boolean,
    reconnect: suspend () -> Boolean,
    wake: (suspend () -> Boolean)? = null,
    reload: () -> Unit,
    stillCurrent: () -> Boolean = { true },
    recoveryInProgress: () -> Boolean = { false },
): Boolean {
    suspend fun canContinue(): Boolean {
        currentCoroutineContext().ensureActive()
        return stillCurrent()
    }

    if (!canContinue()) return false
    val serviceReady = checkService()
    if (!canContinue()) return false
    if (serviceReady) {
        reload()
        return true
    }

    if (!recoveryInProgress()) {
        val hostReady = machineOnline()
        if (!canContinue()) return false
        // Another recovery can start while the passive probe is waiting for a reply.
        if (!hostReady && !recoveryInProgress() && wake != null) {
            val woke = wake()
            if (!canContinue()) return false
            // A failed manual wake must not start a second automatic wake via reconnect.
            if (!woke) return false
        }
    }

    if (!canContinue()) return false
    val connected = reconnect()
    if (!canContinue()) return false
    if (connected) reload()
    return connected
}
