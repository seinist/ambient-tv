package com.ambient.tv.connectivity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Each NAS check owns the job registered by its wake operation. Cancelling that machine
 * must not cancel the Files task, sibling services, or the permanent resume worker.
 * A later source on the same machine is skipped for this sweep, including its probe.
 */
internal class ResumeWakeSweep(private val onCancelled: (String) -> Unit = {}) {
    private val cancelledMachines = mutableSetOf<String>()

    suspend fun check(machine: String, block: suspend () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (machine in cancelledMachines) return false
        return try {
            coroutineScope { block() }
        } catch (cancelled: CancellationException) {
            // Only the operation was cancelled by the wake action. A real cancellation of
            // the caller must still stop this sweep and propagate to its owning worker.
            currentCoroutineContext().ensureActive()
            cancelledMachines += machine
            onCancelled(machine)
            false
        }
    }
}

/** Wake/probe configuration only: renaming a source or changing a share does not resume WOL. */
internal data class ResumeWakeEndpoint(
    val host: String,
    val port: Int,
    val mac: String,
    val broadcast: String,
    val wakePort: Int,
    val automatic: Boolean,
    val wireGuard: Boolean,
)

/**
 * The serialized resume worker retains a cancelled outage across queued network rechecks.
 * Passive probes and explicit wake actions stay available; confirmed reachability or changed
 * endpoint configuration releases the automatic-wake pause.
 */
internal class ResumeWakePauses {
    private val paused = mutableMapOf<String, Set<ResumeWakeEndpoint>>()

    fun sweep(endpoints: Map<String, Set<ResumeWakeEndpoint>>): ResumeWakeSweep {
        val snapshot = endpoints.mapValues { it.value.toSet() }
        paused.entries.removeAll { (machine, configured) -> snapshot[machine] != configured }
        return ResumeWakeSweep { machine -> snapshot[machine]?.let { paused[machine] = it } }
    }

    fun isPaused(machine: String): Boolean = machine in paused

    fun online(machine: String) { paused.remove(machine) }
}
