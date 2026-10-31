package com.ambient.tv.wol

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.notifications.WakeNoticeTracker
import com.ambient.tv.notifications.WakeNotices
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
                    if (mac == own) send(mac, request.broadcast, request.wolPort, request.wireGuardHost, retry)
                    else send(mac, "255.255.255.255", 9, null, retry)
                }
                if (results.none { it.isSuccess }) results.first().getOrThrow()
            }
            val ok = if (host == null || port == null) {
                // Nothing to probe: a sent burst is the result.
                burst(retry = false); notices.sent(machine, 0, 1, 0L); true
            } else wakeWithRetries(
                retries = retries(),
                windowMs = windowMs,
                pollMs = pollMs,
                send = { attempt -> burst(retry = attempt > 0) },
                reachable = { reachable(host, port) },
                onSent = { attempt, attempts -> notices.sent(machine, attempt, attempts, windowMs) },
            )
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val coordinator = ManualWakeCoordinator(
        notices = WakeNotices,
        group = machines::groupFor,
        send = { mac, broadcast, port, wireGuardHost, retry ->
            wolManager.sendWakeBurst(mac, broadcast, port, wireGuardHost = wireGuardHost, retry = true)
        },
        reachable = { host, port ->
            runInterruptible(Dispatchers.IO) {
                try { Socket().use { it.connect(InetSocketAddress(host, port), 900) }; true } catch (_: java.io.IOException) { false }
            }
        },
        retries = { preferences.wolRetryCount },
    )

    /** Fire-and-forget: progress and result appear on the machine's shared wake card. */
    fun wake(request: ManualWakeRequest) {
        scope.launch { coordinator.wake(request) }
    }
}
