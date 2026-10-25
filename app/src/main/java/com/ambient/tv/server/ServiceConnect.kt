package com.ambient.tv.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Backoff between identity attempts while a server that just woke finishes starting. */
internal val CONNECT_RETRY_BACKOFF_MS = listOf(2_000L, 4_000L, 8_000L)

/**
 * How long a service whose machine already answers may still take to start before it counts
 * as down. Also the minimum time identity attempts are retried after the wait ended.
 */
internal const val SERVICE_START_GRACE_MS = 60_000L

/** Identity retries when no wake ran: a hiccup is retried, a real outage is not stretched. */
internal const val CONNECT_RETRY_GRACE_MS = 10_000L

/** A wake that ended this long ago still explains a service that is not up yet. */
internal const val RECENT_WAKE_MS = 5 * 60_000L

internal fun connectBackoffMs(attempt: Int): Long =
    CONNECT_RETRY_BACKOFF_MS[attempt.coerceIn(0, CONNECT_RETRY_BACKOFF_MS.lastIndex)]

/**
 * Failures a starting server produces: no answer yet (connection refused/reset, timeouts) or
 * "not ready" (5xx, 408, 429). Authentication or "not found" answers are final.
 */
internal fun isTransientConnectFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth++ < 8) {
        when (current) {
            is CancellationException -> return false
            is retrofit2.HttpException -> return current.code().let { it >= 500 || it == 408 || it == 429 }
            is com.ambient.tv.tvheadend.data.TvheadendHttpException -> return current.code.let { it >= 500 || it == 408 || it == 429 }
            is java.io.IOException -> return true
        }
        current = current.cause
    }
    return false
}

/**
 * Repeats [attempt] while it fails with a [transient] error and the next try still starts
 * before [deadlineMs]; the last result is returned either way.
 */
internal suspend fun <T> retryTransient(
    deadlineMs: Long,
    nowMs: () -> Long = System::currentTimeMillis,
    transient: (Throwable) -> Boolean = ::isTransientConnectFailure,
    backoffMs: (Int) -> Long = ::connectBackoffMs,
    sleep: suspend (Long) -> Unit = { delay(it) },
    attempt: suspend () -> Result<T>,
): Result<T> {
    var tries = 0
    while (true) {
        val result = attempt()
        val error = result.exceptionOrNull() ?: return result
        if (error is CancellationException) throw error
        if (!transient(error)) return result
        val wait = backoffMs(tries++)
        if (nowMs() + wait > deadlineMs) return result
        sleep(wait)
    }
}

/**
 * One connect per manager and endpoint at a time. A caller arriving while a connect to the same
 * [endpoint key][joinOrStart] runs joins it instead of waiting behind a lock for a second,
 * identical wake. A caller for a different endpoint (server switched, host edited) cancels the
 * stale run, waits until it has reset its state and then starts its own: it never adopts the
 * result of a connect to the old server. The run belongs to [scope], not to the caller: leaving
 * a screen (cancelling the caller) no longer aborts a wake half-way and leaves its "waking"
 * state behind.
 */
internal class SharedConnectRun(private val scope: CoroutineScope) {
    private val lock = Any()
    private var running: Deferred<Boolean>? = null
    private var runningKey: String? = null
    private var started = 0L

    val active: Boolean get() = synchronized(lock) { running?.isActive == true }

    /** Increments with every started run; a passive check compares it to detect a connect in between. */
    val generation: Long get() = synchronized(lock) { started }

    /** A connect runs now or was started after [generation] was read. */
    fun startedSince(generation: Long): Boolean = synchronized(lock) { running?.isActive == true || started != generation }

    suspend fun joinOrStart(key: String, block: suspend () -> Boolean): Boolean {
        val run = synchronized(lock) {
            val current = running?.takeIf { it.isActive }
            if (current != null && runningKey == key) current
            else {
                current?.cancel()
                started++
                runningKey = key
                scope.async {
                    // The stale run resets its own in-flight state on cancel; let it finish first.
                    current?.join()
                    block()
                }.also { running = it }
            }
        }
        return try {
            run.await()
        } catch (cancelled: CancellationException) {
            // Our own cancellation propagates; a run superseded by another endpoint is not a success.
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /** Stops the running connect (its state is reset by the connect itself). */
    fun cancel() = synchronized(lock) { running?.cancel() }
}

/**
 * The state to fall back to when a connect is cancelled: the last settled state from before it,
 * never a "waking"/"checking" state nothing would ever end.
 */
internal fun settledStateAfterCancel(before: ServerConnectionState): ServerConnectionState = when (before) {
    ServerConnectionState.NotConfigured, ServerConnectionState.Offline, ServerConnectionState.WakeTimedOut,
    is ServerConnectionState.Online, is ServerConnectionState.Error -> before
    else -> ServerConnectionState.Offline
}

/** Whether [state] is one a cancelled connect must not leave behind. */
internal fun isInFlightConnectState(state: ServerConnectionState): Boolean = when (state) {
    ServerConnectionState.Checking, ServerConnectionState.SendingWakePacket, ServerConnectionState.Connecting,
    is ServerConnectionState.Waking -> true
    else -> false
}

/** How a [waitForService] ended. [failure] null: go on and request the identity until [identityDeadlineMs]. */
internal data class ServiceWaitResult(
    val failure: ServerConnectionState?,
    val identityDeadlineMs: Long,
    /** A wake or a "service starting" wait ran (identity answers may still be transient). */
    val waited: Boolean,
)

/**
 * Before a connection manager asks for its identity: is the service itself up, and if not,
 * wake its machine and/or wait until the service answers its own health request.
 *
 *  - service answers: connect right away;
 *  - machine down: wake it (when [canWake]) and wait for the service, not just for any port;
 *  - machine up but service not (yet): when the machine just woke ([hostHealth] was OFFLINE or
 *    a magic packet went out recently) the service is still starting, so wait for it without
 *    sending packets ("machine awake, service starting"); otherwise it is simply down and the
 *    identity request reports why.
 */
internal suspend fun waitForService(
    serviceReady: suspend () -> Boolean,
    machineAwake: suspend () -> Boolean,
    hostHealth: () -> com.ambient.tv.connectivity.HostHealth,
    canWake: Boolean,
    send: suspend (attempt: Int) -> Unit,
    retries: Int,
    windowMs: Long,
    pollMs: Long,
    setState: (ServerConnectionState) -> Unit,
    lastWakeAtMs: () -> Long = { com.ambient.tv.wol.WolManager.lastWakeBurstAtMs },
    nowMs: () -> Long = System::currentTimeMillis,
): ServiceWaitResult {
    val hostWasOffline = hostHealth() == com.ambient.tv.connectivity.HostHealth.OFFLINE
    if (serviceReady()) return ServiceWaitResult(null, nowMs() + CONNECT_RETRY_GRACE_MS, waited = false)
    val machineUp = machineAwake()
    if (!machineUp && !canWake) return ServiceWaitResult(ServerConnectionState.Offline, 0L, waited = false)
    val recentlyWoken = hostWasOffline || nowMs() - lastWakeAtMs() in 0 until RECENT_WAKE_MS
    if (machineUp && !recentlyWoken) return ServiceWaitResult(null, nowMs() + CONNECT_RETRY_GRACE_MS, waited = false)

    val window = if (machineUp) SERVICE_START_GRACE_MS else windowMs
    val budgetMs = if (machineUp) window else com.ambient.tv.wol.wakeBudgetMs(retries, windowMs)
    val started = nowMs()
    var awake = machineUp
    val ready = com.ambient.tv.wol.awaitServiceAfterWake(
        retries = retries,
        windowMs = window,
        pollMs = pollMs,
        serviceReady = serviceReady,
        machineAwake = machineAwake,
        send = send,
        sendPackets = !machineUp,
        nowMs = nowMs,
        onProgress = { progress ->
            when (progress) {
                is com.ambient.tv.wol.ServiceWakeProgress.Sending -> setState(ServerConnectionState.SendingWakePacket)
                is com.ambient.tv.wol.ServiceWakeProgress.Waiting -> {
                    awake = progress.machineAwake
                    setState(ServerConnectionState.Waking(
                        elapsedSeconds = progress.elapsedSeconds,
                        totalSeconds = (budgetMs / 1000).toInt(),
                        packetsSent = progress.packetsSent,
                        packets = progress.packets,
                        windowSeconds = if (progress.packets > 0) (window / 1000).toInt() else 0,
                        serviceStarting = progress.machineAwake,
                    ))
                }
            }
        },
    )
    return when {
        ready -> ServiceWaitResult(null, maxOf(started + budgetMs, nowMs() + CONNECT_RETRY_GRACE_MS), waited = true)
        !awake -> ServiceWaitResult(ServerConnectionState.WakeTimedOut, 0L, waited = true)
        // The machine woke but the service never answered: one identity request says why.
        else -> ServiceWaitResult(null, nowMs(), waited = true)
    }
}

/** Runs [block] and calls [onWaking] whenever [state] shows a wake meanwhile (also for a joined run). */
internal suspend fun <T> watchWaking(
    state: kotlinx.coroutines.flow.StateFlow<ServerConnectionState>,
    onWaking: (() -> Unit)?,
    block: suspend () -> T,
): T = kotlinx.coroutines.coroutineScope {
    val watcher = onWaking?.let { callback ->
        launch {
            state.collect { if (it == ServerConnectionState.SendingWakePacket || it is ServerConnectionState.Waking) callback() }
        }
    }
    try { block() } finally { watcher?.cancel() }
}
