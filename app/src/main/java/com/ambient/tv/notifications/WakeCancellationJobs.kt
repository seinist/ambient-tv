package com.ambient.tv.notifications

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Tracks active manual/NAS/DLNA wait jobs by physical-machine key. A wake notification is only
 * presentation; cancelling the actual job stops retries and delayed Magic Packets too.
 *
 * Media-service connects have their own shared jobs, cancelled via UnreachableServerGate.
 */
internal object WakeCancellationJobs {
    private val jobs = mutableMapOf<String, MutableSet<Job>>()

    @Synchronized
    fun register(machine: String, job: Job) {
        jobs.getOrPut(machine) { mutableSetOf() }.add(job)
    }

    @Synchronized
    fun unregister(machine: String, job: Job) {
        jobs[machine]?.let { active ->
            active.remove(job)
            if (active.isEmpty()) jobs.remove(machine)
        }
    }

    fun cancel(machine: String): Boolean {
        val running = synchronized(this) { jobs[machine]?.filter(Job::isActive).orEmpty() }
        running.forEach { it.cancel() }
        return running.isNotEmpty()
    }
}

/** Register only for the lifetime of this wake; never cancel the application's parent scope. */
internal suspend fun <T> withCancellableWake(machine: String, block: suspend () -> T): T {
    val job = currentCoroutineContext()[Job] ?: return block()
    WakeCancellationJobs.register(machine, job)
    try {
        return block()
    } finally {
        WakeCancellationJobs.unregister(machine, job)
    }
}
