package com.ambient.tv.wol

import kotlinx.coroutines.delay

/** What a service wake reports while it waits; drives the connection state and the wake card. */
internal sealed interface ServiceWakeProgress {
    /** A magic-packet burst is going out: [attempt] (0-based) of [attempts]. */
    data class Sending(val attempt: Int, val attempts: Int) : ServiceWakeProgress
    /**
     * Still waiting [elapsedSeconds] after the start. [packetsSent] bursts are out (0 when the
     * machine was already awake and none was needed). [machineAwake]: another port of the machine
     * answers, so only this service is still starting.
     */
    data class Waiting(
        val elapsedSeconds: Int,
        val packetsSent: Int,
        val packets: Int,
        val machineAwake: Boolean,
    ) : ServiceWakeProgress
}

/**
 * Waits until the service itself answers its own health request ([serviceReady], e.g. Plex
 * `/identity`), not merely until some port of its machine opens. [machineAwake] (a TCP check of
 * the machine's ports) only tells the wake to stop sending magic packets and to report
 * "machine awake, service starting"; the wait goes on until the service answers or the budget
 * of [cycles] windows of [windowMs] is used up.
 *
 * [sendPackets] false: the machine is known to be awake already (no burst is sent at all) and
 * the loop only waits for the service, at most [windowMs] in total.
 */
internal suspend fun awaitServiceAfterWake(
    retries: Int,
    windowMs: Long,
    pollMs: Long,
    serviceReady: suspend () -> Boolean,
    machineAwake: suspend () -> Boolean,
    send: suspend (attempt: Int) -> Unit,
    onProgress: suspend (ServiceWakeProgress) -> Unit = {},
    sendPackets: Boolean = true,
    nowMs: () -> Long = System::currentTimeMillis,
): Boolean {
    val started = nowMs()
    val cycles = if (sendPackets) wakeCycles(retries) else 1
    var sent = 0
    var awake = !sendPackets
    for (attempt in 0 until cycles) {
        if (!awake) {
            onProgress(ServiceWakeProgress.Sending(attempt, cycles))
            send(attempt)
            sent = attempt + 1
        }
        val windowStart = nowMs()
        while (nowMs() - windowStart < windowMs) {
            if (serviceReady()) return true
            // Sticky: once the machine answered, more bursts are pointless and only its service is pending.
            if (!awake && machineAwake()) awake = true
            onProgress(ServiceWakeProgress.Waiting(((nowMs() - started) / 1000).toInt(), sent, if (sendPackets) cycles else 0, awake))
            delay(pollMs)
        }
    }
    return serviceReady()
}
