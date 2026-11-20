package com.ambient.tv.wol

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.ConnectivityDependency
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.sanitizeFailureDetail
import com.ambient.tv.notifications.WakeNoticeTracker
import com.ambient.tv.notifications.WakeNotices
import com.ambient.tv.notifications.withCancellableWake
import com.ambient.tv.vpn.WireGuardRouteController
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** One manual wake press: who pressed it and how to reach that service afterwards. */
data class ManualWakeRequest(
    val system: String,
    val member: String = system,
    val subject: String?,
    val host: String?,
    val mac: String?,
    val probePort: Int?,
    val broadcast: String = "255.255.255.255",
    val wolPort: Int = 9,
    val wireGuardHost: String? = null,
)

/**
 * Manual wake of a whole machine: resolves the shared machine key, opens the machine's single
 * wake card (a second press while it is waking does nothing), sends one burst per distinct MAC
 * of the machine and keeps the card until the pressed service answers or the wait ends.
 */
class ManualWakeCoordinator(
    private val notices: WakeNoticeTracker,
    private val group: (system: String, host: String?, mac: String?) -> Pair<String, List<String>>,
    private val send: suspend (mac: String, broadcast: String, port: Int, wireGuardHost: String?, retry: Boolean) -> Result<Unit>,
    private val reachable: suspend (host: String, port: Int) -> Boolean,
    private val retries: () -> Int = { 0 },
    private val windowMs: Long = 60_000L,
    private val pollMs: Long = 2_500L,
    /** The machine answered: stop pending follow-up bursts of its NICs. */
    private val answered: (macs: List<String>) -> Unit = {},
) {
    enum class Outcome { AWAKE, FAILED, ALREADY_WAKING, NO_MAC }

    suspend fun wake(request: ManualWakeRequest): Outcome {
        val (machine, macs) = group(request.system, request.host, request.mac)
        if (macs.isEmpty()) return Outcome.NO_MAC
        if (!notices.tryBegin(machine, request.system, request.subject, request.member)) return Outcome.ALREADY_WAKING
        val own = canonicalWolMacKey(request.mac)
        try {
            val host = request.host?.let(::normalizeWakeGroupHost)
            val port = request.probePort?.takeIf { it in 1..65535 }
            // One burst per NIC of the machine; WolManager coalesces equal MACs across callers.
            suspend fun burst(retry: Boolean) {
                val results = macs.map { mac ->
                    currentCoroutineContext().ensureActive()
                    if (mac == own) send(mac, request.broadcast, request.wolPort, request.wireGuardHost, retry)
                    else send(mac, "255.255.255.255", 9, null, retry)
                }
                if (results.none { it.isSuccess }) results.first().getOrThrow()
            }
            val ok = if (host == null || port == null) {
                // Nothing to probe: a sent burst is the result.
                burst(retry = false)
                currentCoroutineContext().ensureActive()
                notices.sent(machine, 0, 1, 0L)
                true
            } else wakeWithRetries(
                retries = retries(),
                windowMs = windowMs,
                pollMs = pollMs,
                send = { attempt -> burst(retry = attempt > 0) },
                reachable = { reachable(host, port) },
                onAnswered = { answered(macs) },
                onSent = { attempt, attempts ->
                    currentCoroutineContext().ensureActive()
                    notices.sent(machine, attempt, attempts, windowMs)
                },
            )
            currentCoroutineContext().ensureActive()
            notices.finish(machine, request.system, success = ok, member = request.member)
            return if (ok) Outcome.AWAKE else Outcome.FAILED
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            notices.cancel(machine, request.system, request.member)
            throw cancelled
        } catch (error: Exception) {
            notices.finish(machine, request.system, success = false, member = request.member)
            return Outcome.FAILED
        }
    }
}

/** App-scoped manual wake: survives leaving the Settings page while the machine boots. */
@Singleton
class MachineWake @Inject constructor(
    private val wolManager: WolManager,
    private val machines: WakeMachines,
    private val preferences: AppPreferences,
    private val wireGuardRoute: WireGuardRouteController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val lastRequests = linkedMapOf<String, ManualWakeRequest>()

    /** Bounded, process-local endpoint data; no credentials and no Activity references. */
    fun retryRequests(): Map<String, ManualWakeRequest> = synchronized(lock) { lastRequests.toMap() }

    private val coordinator = ManualWakeCoordinator(
        notices = WakeNotices,
        group = machines::groupFor,
        send = { mac, broadcast, port, wireGuardHost, retry ->
            wolManager.sendWakeBurst(mac, broadcast, port, wireGuardHost = wireGuardHost, retry = retry)
        },
        reachable = { host, port ->
            runInterruptible(Dispatchers.IO) {
                try { Socket().use { it.connect(InetSocketAddress(host, port), 900) }; true } catch (_: java.io.IOException) { false }
            }
        },
        retries = { preferences.wolRetryCount },
        answered = { macs -> macs.forEach(wolManager::machineAnswered) },
    )

    fun machineKey(system: String, host: String?, mac: String?): String =
        machines.keyFor(system, host, mac)

    /** Fire-and-forget: progress and result appear on the machine's shared wake card. */
    fun wake(request: ManualWakeRequest) = enqueue(request, null)

    /** Wake a machine, then reconnect the originally selected service and its offline siblings. */
    fun wakeThen(request: ManualWakeRequest, onAwake: () -> Unit) = enqueue(request, onAwake)

    private fun enqueue(request: ManualWakeRequest, onAwake: (() -> Unit)?) {
        val machine = machines.keyFor(request.system, request.host, request.mac)
        val job = synchronized(lock) {
            val previous = jobs[machine]
            if ((previous != null && !previous.isCompleted && !previous.isCancelled) || WakeNotices.isWaking(machine)) return
            lastRequests.remove(machine)
            lastRequests[machine] = request
            while (lastRequests.size > 32) lastRequests.remove(lastRequests.keys.first())
            scope.launch(start = CoroutineStart.LAZY) {
                withCancellableWake(machine) {
                    // A quick retry cannot be overwritten by the previous attempt's cleanup.
                    previous?.join()
                    var coordinated = false
                    fun report(phase: NoticePhase, message: Int, blockedBy: ConnectivityDependency? = null,
                        cancelled: Boolean = false, args: List<String> = emptyList()) = AppNotifications.post(AppNotice(
                        key = WakeNotices.noticeKey(machine), message = message, systems = listOf(request.system),
                        subject = request.subject, phase = phase, kind = NoticeKind.WAKE,
                        machine = machine, blockedBy = blockedBy, cancelled = cancelled, args = args,
                    ))
                    try {
                        val vpnHost = request.wireGuardHost?.takeIf(String::isNotBlank)
                        report(NoticePhase.WORKING, R.string.notice_connecting,
                            ConnectivityDependency.VPN.takeIf { vpnHost != null })
                        if (vpnHost != null) {
                            val activation = wireGuardRoute.ensureForWakeHost(vpnHost)
                            if (activation.isFailure) {
                                val reason = activation.exceptionOrNull()?.message?.let(::sanitizeFailureDetail)?.takeIf(String::isNotBlank)
                                report(NoticePhase.ERROR,
                                    if (reason != null) R.string.notice_vpn_failed_reason else R.string.notice_vpn_failed,
                                    ConnectivityDependency.VPN, args = listOfNotNull(reason))
                                return@withCancellableWake
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!request.mac.isNullOrBlank() && !wolManager.wakePossible(request.mac,
                                request.host?.takeIf(String::isNotBlank), request.wireGuardHost, request.broadcast)) {
                            val outside = wolManager.serverUnreachableFromHere(request.host, request.wireGuardHost)
                            report(NoticePhase.ERROR,
                                if (outside) R.string.wol_not_local_network else R.string.wol_network_unavailable)
                            return@withCancellableWake
                        }
                        currentCoroutineContext().ensureActive()
                        coordinated = true
                        when (coordinator.wake(request)) {
                            ManualWakeCoordinator.Outcome.NO_MAC ->
                                report(NoticePhase.ERROR, R.string.notice_wake_failed)
                            ManualWakeCoordinator.Outcome.AWAKE -> onAwake?.invoke()
                            else -> Unit
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        if (!coordinated) report(NoticePhase.INFO, R.string.notice_wake_cancelled, cancelled = true)
                        throw cancelled
                    } catch (_: Exception) {
                        if (!currentCoroutineContext().isActive) {
                            if (!coordinated) report(NoticePhase.INFO, R.string.notice_wake_cancelled, cancelled = true)
                            currentCoroutineContext().ensureActive()
                        }
                        report(NoticePhase.ERROR, R.string.notice_wake_failed)
                    }
                }
            }.also { jobs[machine] = it }
        }
        job.invokeOnCompletion { synchronized(lock) { if (jobs[machine] === job) jobs.remove(machine) } }
        job.start()
    }
}
