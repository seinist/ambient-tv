package com.ambient.tv.connectivity

import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.dlna.dlnaWakeEndpoint
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.WakeCancellationJobs
import com.ambient.tv.notifications.WakeNotices
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.wol.MachineWake
import com.ambient.tv.wol.ManualWakeRequest
import com.ambient.tv.wol.WakeMachines
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Actions belong to the operation, so closing the panel never cancels connection work. */
@Singleton
class ConnectionStatusActions @Inject constructor(
    private val gate: UnreachableServerGate,
    private val hosts: HostConnectivity,
    private val preferences: AppPreferences,
    private val dlna: DlnaStore,
    private val machines: WakeMachines,
    private val manualWake: MachineWake,
    private val wireGuard: WireGuardRouteController,
    private val resume: ResumeConnectivityMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    fun cancel(notice: AppNotice) {
        val current = AppNotifications.store.state.value.connectivity.entries
            .firstOrNull { it.notice.key == notice.key }?.notice ?: return
        if (current.phase != NoticePhase.WORKING) return
        connectionNoticeMachine(current)?.let(::cancelMachine)
    }

    fun cancelMachine(machine: String) {
        // Close the producer group first: late packet/result callbacks cannot revive it.
        WakeNotices.cancelMachine(machine)
        synchronized(lock) { jobs[machine] }?.cancel()
        WakeCancellationJobs.cancel(machine)
        gate.cancelMachineWake(machine)
        // A shared VPN stays connected for every other system that still uses it.
    }

    fun canRetry(notice: AppNotice): Boolean = prepare(notice).plan.possible

    fun retry(notice: AppNotice) {
        val prepared = prepare(notice)
        if (!prepared.plan.possible) return
        val key = connectionNoticeMachine(notice) ?: notice.key
        val job = synchronized(lock) {
            val previous = jobs[key]
            if (previous != null && !previous.isCompleted && !previous.isCancelled) return
            scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                currentCoroutineContext().ensureActive()
                if (prepared.plan.checkNetwork && !resume.recheckNetworkForRetry()) return@launch
                if (prepared.plan.restoreVpn && wireGuard.ensureActive().isFailure) return@launch
                currentCoroutineContext().ensureActive()
                // A batch retains every requested failed service and excludes healthy siblings.
                gate.retrySystems(prepared.plan.systems, includeMachineSiblings = false)
                prepared.plan.manualIds.forEach { id ->
                    currentCoroutineContext().ensureActive()
                    prepared.manuals[id]?.let(manualWake::wake)
                }
            }.also { jobs[key] = it }
        }
        job.invokeOnCompletion { synchronized(lock) { if (jobs[key] === job) jobs.remove(key) } }
        job.start()
    }

    private data class Prepared(
        val plan: ConnectionRetryPlan,
        val manuals: Map<String, ManualWakeRequest>,
    )

    private fun prepare(requested: AppNotice): Prepared {
        val snapshot = AppNotifications.store.state.value.connectivity
        val session = snapshot.entries.map { it.notice }
        // The panel may have rendered a frame before a background operation recovered.
        val notice = currentConnectionRetryNotice(requested, snapshot)
            ?: return Prepared(ConnectionRetryPlan(), emptyMap())
        val targets = mutableListOf<ConnectionRetryTarget>()
        val manuals = linkedMapOf<String, ManualWakeRequest>()
        gate.retryableSystems().forEach { system ->
            targets += ConnectionRetryTarget(system, system, hosts.machine(system), requiresVpn = when (system) {
                "plex" -> preferences.wireGuardPlexEnabled
                "jellyfin" -> preferences.wireGuardJellyfinEnabled
                "emby" -> preferences.wireGuardEmbyEnabled
                "tvheadend" -> preferences.wireGuardTvheadendEnabled
                else -> false
            })
        }
        fun manual(id: String, request: ManualWakeRequest, preferred: Boolean = false) {
            val (machine, macs) = machines.groupFor(request.system, request.host, request.mac)
            if (macs.isEmpty()) return
            // DLNA retry must verify its ContentDirectory endpoint, not only send a packet.
            if (request.system == "dlna" &&
                (request.host.isNullOrBlank() || request.probePort?.let { it in 1..65535 } != true)) return
            manuals[id] = request
            targets += ConnectionRetryTarget(id, request.system, machine, manual = true,
                requiresVpn = !request.wireGuardHost.isNullOrBlank(), preferred = preferred)
        }
        preferences.fileSources.filter { it.network }.forEach { source ->
            manual("files:${source.id}", ManualWakeRequest(
                "files", "files:${source.id}", source.name, source.host, source.wolMac, source.port,
                source.wolBroadcast, source.wolPort, source.host.takeIf { source.wireGuard },
            ))
        }
        val vpnHosts = wireGuard.tunnelHosts().mapTo(mutableSetOf()) { NetworkEndpoint.normalizeHost(it).lowercase() }
        dlna.servers().forEach { server ->
            val endpoint = dlnaWakeEndpoint(server) ?: return@forEach
            manual("dlna:${server.id}", ManualWakeRequest(
                "dlna", "dlna:${server.id}", server.name, endpoint.first, server.wolMac, endpoint.second,
                wireGuardHost = endpoint.first.takeIf { NetworkEndpoint.normalizeHost(it).lowercase() in vpnHosts },
            ))
        }
        manualWake.retryRequests().forEach { (machine, request) ->
            manual("manual:$machine", request, preferred = true)
        }
        return Prepared(planConnectionRetry(notice, session, targets, wireGuard.anySystemEnabled()), manuals)
    }
}
